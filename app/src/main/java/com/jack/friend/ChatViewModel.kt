package com.jack.friend

import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ChatViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance().reference
    private val storage = FirebaseStorage.getInstance().reference

    private val _isUserLoggedIn = MutableStateFlow(auth.currentUser != null)
    val isUserLoggedIn: StateFlow<Boolean> = _isUserLoggedIn

    private val _myUsername = MutableStateFlow("")
    val myUsername: StateFlow<String> = _myUsername

    private val _myId = MutableStateFlow(auth.currentUser?.uid ?: "")
    val myId: StateFlow<String> = _myId

    private val _myName = MutableStateFlow("")
    val myName: StateFlow<String> = _myName

    private val _myPhotoUrl = MutableStateFlow<String?>(null)
    val myPhotoUrl: StateFlow<String?> = _myPhotoUrl

    private val _myStatus = MutableStateFlow("Olá! Estou usando o Friend.")
    val myStatus: StateFlow<String> = _myStatus

    private val _myPresenceStatus = MutableStateFlow("Online")
    val myPresenceStatus: StateFlow<String> = _myPresenceStatus

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _activeChats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val activeChats: StateFlow<List<ChatSummary>> = _activeChats

    private val _contacts = MutableStateFlow<List<UserProfile>>(emptyList())
    val contacts: StateFlow<List<UserProfile>> = _contacts

    private val _targetId = MutableStateFlow("")
    val targetId: StateFlow<String> = _targetId

    private val _targetProfile = MutableStateFlow<UserProfile?>(null)
    val targetProfile: StateFlow<UserProfile?> = _targetProfile

    private val _targetGroup = MutableStateFlow<Group?>(null)
    val targetGroup: StateFlow<Group?> = _targetGroup

    private val _pinnedMessage = MutableStateFlow<Message?>(null)
    val pinnedMessage: StateFlow<Message?> = _pinnedMessage

    private val _isTargetTyping = MutableStateFlow(false)
    val isTargetTyping: StateFlow<Boolean> = _isTargetTyping

    private val _searchResults = MutableStateFlow<List<UserProfile>>(emptyList())
    val searchResults: StateFlow<List<UserProfile>> = _searchResults

    private val _statuses = MutableStateFlow<List<UserStatus>>(emptyList())
    val statuses: StateFlow<List<UserStatus>> = _statuses

    private val _blockedUsers = MutableStateFlow<List<String>>(emptyList())
    val blockedUsers: StateFlow<List<String>> = _blockedUsers

    private val _blockedProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    val blockedProfiles: StateFlow<List<UserProfile>> = _blockedProfiles

    private var chatsListener: ValueEventListener? = null
    private var contactsListener: ValueEventListener? = null
    private var messagesListener: ValueEventListener? = null
    private var targetProfileListener: ValueEventListener? = null
    private var statusesListener: ValueEventListener? = null
    private var typingListener: ValueEventListener? = null
    private var pinnedMessageListener: ValueEventListener? = null
    private var blockedListener: ValueEventListener? = null
    private var currentChatPath: String? = null
    private val presenceListeners = mutableMapOf<String, ValueEventListener>()
    private val typingListeners = mutableMapOf<String, ValueEventListener>()

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recordingStartTime: Long = 0
    private var searchJob: Job? = null

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration
    private var timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    init {
        if (auth.currentUser != null) setupUserSession()
    }

    private fun setupUserSession() {
        val uid = auth.currentUser?.uid ?: return
        db.child("uid_to_username").child(uid).get().addOnSuccessListener { snapshot ->
            val username = snapshot.getValue(String::class.java) ?: ""
            if (username.isNotEmpty()) {
                _myUsername.value = username
                loadMyProfile(username)
                listenToBlockedUsers(username)
                listenToChats(username)
                listenToContacts(username)
                listenToStatuses(username) 
                setupPresence(username)
            } else {
                Log.e("ChatViewModel", "Username não encontrado para o UID: $uid")
            }
        }.addOnFailureListener { Log.e("ChatViewModel", "Erro setupUserSession: ${it.message}") }
    }

    private fun loadMyProfile(username: String) {
        db.child("users").child(username).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val profile = snapshot.getValue(UserProfile::class.java)
                _myName.value = profile?.name ?: username
                _myPhotoUrl.value = profile?.photoUrl
                _myStatus.value = profile?.status ?: "Olá! Estou usando o Friend."
                _myPresenceStatus.value = profile?.presenceStatus ?: "Online"
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun setupPresence(username: String) {
        val statusRef = db.child("users").child(username).child("isOnline")
        db.child(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) == true) {
                    statusRef.onDisconnect().setValue(false)
                    updatePresence(FriendApplication.isAppInForeground)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        
        viewModelScope.launch {
            _myPresenceStatus.collect { 
                updatePresence(FriendApplication.isAppInForeground)
            }
        }

        viewModelScope.launch {
            FriendApplication.instance.isForeground.collect { isForeground ->
                updatePresence(isForeground)
            }
        }
    }

    fun logout() {
        val user = _myUsername.value
        if (user.isNotEmpty()) db.child("users").child(user).child("isOnline").setValue(false)
        removeListeners()
        auth.signOut()
        _isUserLoggedIn.value = false
        _myUsername.value = ""
        _activeChats.value = emptyList()
        _messages.value = emptyList()
        _targetId.value = ""
    }

    private fun removeListeners() {
        val me = _myUsername.value
        if (me.isNotEmpty()) {
            chatsListener?.let { db.child("chats").child(me).removeEventListener(it) }
            contactsListener?.let { db.child("contacts").child(me).removeEventListener(it) }
            blockedListener?.let { db.child("blocks").child(me).removeEventListener(it) }
            statusesListener?.let { db.child("status").removeEventListener(it) }
        }
        currentChatPath?.let { path ->
            messagesListener?.let { l -> db.child(path).removeEventListener(l) }
            pinnedMessageListener?.let { l -> db.child("pinned").child(path).removeEventListener(l) }
        }
        presenceListeners.forEach { (uid, listener) -> db.child("users").child(uid).child("isOnline").removeEventListener(listener) }
        presenceListeners.clear()
        
        typingListeners.forEach { (friendId, listener) -> 
            val path = chatPathFor(me, friendId)
            db.child("typing").child(path).child(friendId).removeEventListener(listener) 
        }
        typingListeners.clear()
    }

    fun setTargetId(id: String, isGroup: Boolean = false) {
        if (_targetId.value == id && id.isNotEmpty()) return

        val me = _myUsername.value
        _targetId.value.takeIf { it.isNotEmpty() }?.let { oldId ->
            if (me.isNotEmpty()) {
                db.child("typing").child(if (_targetGroup.value != null) oldId else chatPathFor(me, oldId)).child(me).setValue(false)
            }
            targetProfileListener?.let { db.child("users").child(oldId).removeEventListener(it) }
            pinnedMessageListener?.let { db.child("pinned").child(if (_targetGroup.value != null) oldId else chatPathFor(me, oldId)).removeEventListener(it) }
        }

        _targetId.value = id

        if (id.isEmpty()) {
            _targetProfile.value = null
            _targetGroup.value = null
            _messages.value = emptyList()
            _isTargetTyping.value = false
            _pinnedMessage.value = null
            return
        }

        _isTargetTyping.value = _activeChats.value.find { it.friendId == id }?.isTyping ?: false

        val path = if (isGroup) id else chatPathFor(me, id)
        
        pinnedMessageListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _pinnedMessage.value = snapshot.getValue(Message::class.java)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("pinned").child(path).addValueEventListener(pinnedMessageListener!!)

        if (!isGroup) {
            targetProfileListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _targetProfile.value = snapshot.getValue(UserProfile::class.java)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            db.child("users").child(id).addValueEventListener(targetProfileListener!!)
        } else {
            db.child("groups").child(id).get().addOnSuccessListener { _targetGroup.value = it.getValue(Group::class.java) }
        }

        listenToMessages(id, isGroup)
    }

    private fun syncTypingStatus(friendUsername: String, isGroup: Boolean = false) {
        if (typingListeners.containsKey(friendUsername)) return
        val me = _myUsername.value
        if (me.isEmpty()) return
        
        val path = if (isGroup) friendUsername else chatPathFor(me, friendUsername)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isTyping = if (isGroup) {
                    snapshot.children.any { it.key != me && it.getValue(Boolean::class.java) == true }
                } else {
                    snapshot.getValue(Boolean::class.java) ?: false
                }
                
                _activeChats.value = _activeChats.value.map {
                    if (it.friendId == friendUsername) it.copy(isTyping = isTyping) else it
                }
                
                if (friendUsername == _targetId.value) {
                    _isTargetTyping.value = isTyping
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        typingListeners[friendUsername] = listener
        val ref = if (isGroup) db.child("typing").child(path) else db.child("typing").child(path).child(friendUsername)
        ref.addValueEventListener(listener)
    }

    fun setTyping(isTyping: Boolean) {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return
        val isGroup = _targetGroup.value != null
        val path = if (isGroup) target else chatPathFor(me, target)
        val typingRef = db.child("typing").child(path).child(me)
        typingRef.setValue(isTyping)
        if (isTyping) typingRef.onDisconnect().setValue(false)
    }

    fun sendMessage(text: String, isGroup: Boolean, tempDurationHours: Int = 0, replyingTo: Message? = null) {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return
        if (!isGroup && _blockedUsers.value.contains(target)) return

        val msgId = db.push().key ?: return
        val msg = Message(
            id = msgId, 
            senderId = me, 
            receiverId = target, 
            text = text, 
            timestamp = System.currentTimeMillis(), 
            isGroup = isGroup, 
            senderName = _myName.value,
            replyToId = replyingTo?.id,
            replyToText = replyingTo?.text ?: if (replyingTo?.imageUrl != null) "📷 Imagem" else if (replyingTo?.audioUrl != null) "🎤 Áudio" else null,
            replyToName = replyingTo?.senderName ?: replyingTo?.senderId,
            expiryTime = if (tempDurationHours > 0) System.currentTimeMillis() + (tempDurationHours * 3600000) else null
        )

        sendMessageObject(msg)
        setTyping(false)
    }

    fun editMessage(message: Message, newText: String) {
        val me = _myUsername.value
        if (me != message.senderId) return
        val path = if (message.isGroup) "group_messages/${message.receiverId}" else "messages/${chatPathFor(message.senderId, message.receiverId)}"
        db.child(path).child(message.id).updateChildren(mapOf("text" to newText, "isEdited" to true))
    }

    fun pinMessage(message: Message) {
        val path = if (message.isGroup) message.receiverId else chatPathFor(message.senderId, message.receiverId)
        db.child("pinned").child(path).setValue(message)
    }

    fun unpinMessage() {
        val me = _myUsername.value
        val target = _targetId.value
        val isGroup = _targetGroup.value != null
        val path = if (isGroup) target else chatPathFor(me, target)
        db.child("pinned").child(path).removeValue()
    }

    fun addReaction(message: Message, emoji: String) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        val path = if (message.isGroup) "group_messages/${message.receiverId}" else "messages/${chatPathFor(message.senderId, message.receiverId)}"
        db.child(path).child(message.id).child("reactions").child(me).setValue(emoji)
    }

    fun markAsRead() {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return
        val isGroup = _targetGroup.value != null

        if (!isGroup) {
            val path = "messages/${chatPathFor(me, target)}"
            db.child(path).orderByChild("receiverId").equalTo(me).get().addOnSuccessListener { snapshot ->
                snapshot.children.forEach {
                    if (it.child("isRead").getValue(Boolean::class.java) != true) {
                        it.ref.child("isRead").setValue(true)
                    }
                }
            }
        }
        db.child("chats").child(me).child(target).child("hasUnread").setValue(false)
    }

    fun startRecording(cacheDir: File) {
        try {
            audioFile = File.createTempFile("audio_", ".m4a", cacheDir)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            recordingStartTime = System.currentTimeMillis()
            _recordingDuration.value = 0L
            startTimer()
        } catch (e: Exception) { 
            Log.e("ChatViewModel", "Start recording error: ${e.message}")
        }
    }

    private fun startTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                _recordingDuration.value = System.currentTimeMillis() - recordingStartTime
                timerHandler.postDelayed(this, 100)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        _recordingDuration.value = 0L
    }

    fun stopRecording(isGroup: Boolean, tempDurationHours: Int = 0, cancel: Boolean = false) {
        stopTimer()
        val duration = System.currentTimeMillis() - recordingStartTime
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            
            if (cancel || duration < 800) {
                audioFile?.delete()
                audioFile = null
                return
            }
            
            audioFile?.let { uploadAudio(it, isGroup, tempDurationHours) }
        } catch (e: Exception) { 
            Log.e("ChatViewModel", "Stop recording failed: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            audioFile?.delete()
        }
    }

    private suspend fun uploadToCloudinary(uri: Uri, folder: String): String? = suspendCoroutine { continuation ->
        MediaManager.get().upload(uri)
            .option("folder", folder)
            .callback(object : UploadCallback {
                override fun onStart(requestId: String) {}
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
                override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                    continuation.resume(resultData["secure_url"] as? String)
                }
                override fun onError(requestId: String, error: ErrorInfo) {
                    Log.e("Cloudinary", "Upload error: ${error.description}")
                    continuation.resume(null)
                }
                override fun onReschedule(requestId: String, error: ErrorInfo) {
                    continuation.resume(null)
                }
            }).dispatch()
    }

    private fun uploadAudio(file: File, isGroup: Boolean, tempDurationHours: Int) {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return

        viewModelScope.launch {
            try {
                val audioRef = storage.child("audios/${UUID.randomUUID()}.m4a")
                audioRef.putFile(Uri.fromFile(file)).await()
                val url = audioRef.downloadUrl.await().toString()
                
                if (url != null) {
                    val msgId = db.push().key ?: return@launch
                    val msg = Message(id = msgId, senderId = me, receiverId = target, audioUrl = url, timestamp = System.currentTimeMillis(), isGroup = isGroup, senderName = _myName.value, expiryTime = if (tempDurationHours > 0) System.currentTimeMillis() + (tempDurationHours * 3600000) else null)
                    msg.localAudioPath = file.absolutePath
                    sendMessageObject(msg)
                }
            } catch (e: Exception) { 
                Log.e("ChatViewModel", "Upload áudio Firebase falhou: ${e.message}")
            }
        }
    }

    fun uploadImage(uri: Uri, isGroup: Boolean, tempDurationHours: Int = 0) {
        val me = _myUsername.value
        val tid = _targetId.value
        if (me.isEmpty() || tid.isEmpty()) return
        viewModelScope.launch {
            try {
                val url = uploadToCloudinary(uri, "chat_images")
                if (url != null) {
                    val msgId = db.push().key ?: return@launch
                    sendMessageObject(Message(id = msgId, senderId = me, receiverId = tid, imageUrl = url, timestamp = System.currentTimeMillis(), isGroup = isGroup, senderName = _myName.value, expiryTime = if (tempDurationHours > 0) System.currentTimeMillis() + (tempDurationHours * 3600000) else null))
                }
            } catch (e: Exception) {}
        }
    }

    private fun sendMessageObject(msg: Message) {
        if (msg.isGroup) {
            val path = "group_messages/${msg.receiverId}"
            db.child(path).child(msg.id).setValue(msg)
            updateChatSummary(msg)
            return
        }

        viewModelScope.launch {
            try {
                // Se eu bloqueei o alvo, não envio
                if (_blockedUsers.value.contains(msg.receiverId)) return@launch
                
                // IMPORTANTE: Não tentamos ler a lista de bloqueios do alvo diretamente, 
                // pois isso causa erro de permissão. Simplesmente tentamos escrever a mensagem.
                val path = "messages/${chatPathFor(msg.senderId, msg.receiverId)}"
                db.child(path).child(msg.id).setValue(msg)
                updateChatSummary(msg)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Erro ao enviar mensagem: ${e.message}")
            }
        }
    }

    private fun updateChatSummary(msg: Message) {
        viewModelScope.launch {
            try {
                val me = msg.senderId
                val friend = msg.receiverId
                val isGroup = msg.isGroup
                
                if (isGroup) {
                    val groupSnapshot = db.child("groups").child(friend).get().await()
                    val group = groupSnapshot.getValue(Group::class.java) ?: return@launch
                    val summary = ChatSummary(
                        friendId = friend, 
                        lastMessage = if (msg.audioUrl != null) "Áudio" else if (msg.imageUrl != null) "Imagem" else "${msg.senderName}: ${msg.text}", 
                        timestamp = msg.timestamp, 
                        lastSenderId = me, 
                        friendName = group.name, 
                        friendPhotoUrl = group.photoUrl, 
                        isGroup = true, 
                        hasUnread = false
                    )
                    group.members.keys.forEach { memberUsername ->
                        db.child("chats").child(memberUsername).child(friend).setValue(summary.copy(hasUnread = memberUsername != me))
                    }
                } else {
                    val friendProf = db.child("users").child(friend).get().await().getValue(UserProfile::class.java)
                    val summary = ChatSummary(friendId = friend, lastMessage = if (msg.audioUrl != null) "Áudio" else if (msg.imageUrl != null) "Imagem" else msg.text, timestamp = msg.timestamp, lastSenderId = me, friendName = friendProf?.name ?: friend, friendPhotoUrl = friendProf?.photoUrl, isGroup = false, isOnline = friendProf?.isOnline ?: false, hasUnread = false, presenceStatus = friendProf?.presenceStatus ?: "Online")
                    db.child("chats").child(me).child(friend).setValue(summary)
                    
                    val meProf = db.child("users").child(me).get().await().getValue(UserProfile::class.java)
                    db.child("chats").child(friend).child(me).setValue(summary.copy(friendId = me, friendName = meProf?.name ?: me, friendPhotoUrl = meProf?.photoUrl, hasUnread = true, presenceStatus = meProf?.presenceStatus ?: "Online"))
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Erro ao atualizar resumo: ${e.message}")
            }
        }
    }

    fun createGroup(name: String, members: List<String>, photoUri: Uri?, callback: (Boolean, String?) -> Unit) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        
        viewModelScope.launch {
            try {
                val groupId = "GROUP_${UUID.randomUUID()}"
                val photoUrl = photoUri?.let { uploadToCloudinary(it, "group_profiles") }
                
                val membersMap = (members + me).associateWith { true }
                val group = Group(
                    id = groupId,
                    name = name,
                    photoUrl = photoUrl,
                    members = membersMap,
                    createdBy = me,
                    timestamp = System.currentTimeMillis()
                )
                
                db.child("groups").child(groupId).setValue(group).await()
                
                val welcomeMsg = Message(
                    id = db.push().key ?: "",
                    senderId = "SYSTEM",
                    receiverId = groupId,
                    text = "Grupo criado",
                    timestamp = System.currentTimeMillis(),
                    isGroup = true,
                    senderName = "Sistema"
                )
                db.child("group_messages/$groupId").child(welcomeMsg.id).setValue(welcomeMsg).await()
                updateChatSummary(welcomeMsg)
                
                callback(true, groupId)
            } catch (e: Exception) {
                callback(false, e.message)
            }
        }
    }

    fun updatePresence(online: Boolean) {
        val user = _myUsername.value
        if (user.isEmpty()) return
        val isVisible = _myPresenceStatus.value != "Invisível"
        db.child("users").child(user).child("isOnline").setValue(online && isVisible)
    }

    fun deleteGroup(groupId: String, callback: (Boolean, String?) -> Unit) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        
        db.child("groups").child(groupId).get().addOnSuccessListener { snapshot ->
            val group = snapshot.getValue(Group::class.java)
            if (group != null && group.createdBy == me) {
                viewModelScope.launch {
                    try {
                        db.child("group_messages").child(groupId).removeValue()
                        group.members.keys.forEach { member ->
                            db.child("chats").child(member).child(groupId).removeValue()
                        }
                        db.child("groups").child(groupId).removeValue().await()
                        callback(true, null)
                    } catch (e: Exception) {
                        callback(false, e.message)
                    }
                }
            } else {
                callback(false, "Apenas o criador pode excluir o grupo")
            }
        }.addOnFailureListener {
            callback(false, it.message)
        }
    }

    private fun chatPathFor(u1: String, u2: String): String {
        val user1 = u1.uppercase().trim()
        val user2 = u2.uppercase().trim()
        return if (user1 < user2) "${user1}_$user2" else "${user2}_$user1"
    }

    private fun listenToChats(username: String) {
        chatsListener?.let { db.child("chats").child(username).removeEventListener(it) }
        chatsListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val blocked = _blockedUsers.value
                val chats = s.children.mapNotNull { it.getValue(ChatSummary::class.java) }
                    .filter { it.friendId.isNotBlank() && !blocked.contains(it.friendId) } 
                    .sortedByDescending { it.timestamp }
                _activeChats.value = chats
                chats.forEach { chat -> 
                    if (!chat.isGroup) syncFriendPresence(chat.friendId) 
                    syncTypingStatus(chat.friendId, chat.isGroup)
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.child("chats").child(username).addValueEventListener(chatsListener!!)
    }

    private fun listenToContacts(username: String) {
        contactsListener?.let { db.child("contacts").child(username).removeEventListener(it) }
        contactsListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val contactIds = s.children.mapNotNull { it.key }
                val blocked = _blockedUsers.value.toSet()
                viewModelScope.launch {
                    try {
                        val contactProfiles = contactIds.filter { it.isNotBlank() && !blocked.contains(it) }.mapNotNull { id ->
                            db.child("users").child(id).get().await().getValue(UserProfile::class.java)
                        }
                        _contacts.value = contactProfiles
                        contactProfiles.forEach { syncFriendPresence(it.id) }
                    } catch (e: Exception) {}
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.child("contacts").child(username).addValueEventListener(contactsListener!!)
    }

    fun addContact(targetUsername: String, callback: (Boolean, String?) -> Unit) {
        val me = _myUsername.value
        val target = targetUsername.uppercase().trim()
        if (me == target) return callback(false, "Não pode adicionar a si mesmo")
        
        db.child("users").child(target).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                db.child("contacts").child(me).child(target).setValue(true).addOnCompleteListener { task ->
                    if (task.isSuccessful) callback(true, null)
                    else callback(false, task.exception?.message)
                }
            } else callback(false, "Usuário não encontrado")
        }
    }

    private fun syncFriendPresence(friendUsername: String) {
        if (presenceListeners.containsKey(friendUsername)) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val user = snapshot.getValue(UserProfile::class.java)
                val isOnline = user?.isOnline ?: false
                val presenceStatus = user?.presenceStatus ?: "Online"
                _activeChats.value = _activeChats.value.map {
                    if (it.friendId == friendUsername) it.copy(isOnline = isOnline, presenceStatus = presenceStatus) else it
                }
                _contacts.value = _contacts.value.map {
                    if (it.id == friendUsername) it.copy(isOnline = isOnline, presenceStatus = presenceStatus) else it
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        presenceListeners[friendUsername] = listener
        db.child("users").child(friendUsername).addValueEventListener(listener)
    }

    private fun listenToMessages(target: String, isGroup: Boolean) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        currentChatPath?.let { path -> messagesListener?.let { l -> db.child(path).removeEventListener(l) } }
        val path = if (isGroup) "group_messages/$target" else "messages/${chatPathFor(me, target)}"
        currentChatPath = path
        messagesListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { 
                val now = System.currentTimeMillis()
                val blocked = _blockedUsers.value
                val msgs = s.children.mapNotNull { it.getValue(Message::class.java) }
                val filtered = msgs.filter { 
                    (it.expiryTime == null || it.expiryTime!! > now) && 
                    !blocked.contains(it.senderId) // Filtrar mensagens de quem eu bloqueei
                } 
                
                filtered.forEach { msg ->
                    if (msg.audioUrl != null) downloadAudioIfNeeded(msg)
                }

                _messages.value = filtered
                msgs.forEach { if (it.expiryTime != null && it.expiryTime!! < now) db.child(path).child(it.id).removeValue() }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.child(path).addValueEventListener(messagesListener!!)
    }

    private fun downloadAudioIfNeeded(msg: Message) {
        val audioUrl = msg.audioUrl ?: return
        val fileName = "audio_${msg.id}.m4a"
        val cacheFile = File(FriendApplication.instance.cacheDir, fileName)
        
        if (cacheFile.exists()) {
            msg.localAudioPath = cacheFile.absolutePath
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                URL(audioUrl).openStream().use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    msg.localAudioPath = cacheFile.absolutePath
                    _messages.value = _messages.value.toList()
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Download audio failed: ${e.message}")
            }
        }
    }

    fun signUp(email: String, password: String, username: String, imageUri: Uri?, callback: (Boolean, String?) -> Unit) {
        val upper = username.uppercase().trim()
        
        // 1. Criar no Auth primeiro para autenticar
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val uid = auth.currentUser?.uid ?: ""
                
                // 2. Agora autenticados, verificamos se o username está disponível
                db.child("users").child(upper).get().addOnSuccessListener { snapshot ->
                    if (snapshot.exists()) {
                        auth.currentUser?.delete()
                        callback(false, "Este nome de usuário já está em uso.")
                    } else {
                        viewModelScope.launch {
                            try {
                                val photoUrl = imageUri?.let { uploadToCloudinary(it, "profiles") }
                                val profile = UserProfile(id = upper, uid = uid, name = username, photoUrl = photoUrl, isOnline = true)
                                
                                db.child("uid_to_username").child(uid).setValue(upper).await()
                                db.child("users").child(upper).setValue(profile).await()
                                
                                _myUsername.value = upper
                                _isUserLoggedIn.value = true
                                setupUserSession()
                                callback(true, null)
                            } catch (e: Exception) {
                                auth.currentUser?.delete()
                                callback(false, "Erro ao salvar perfil: ${e.message}")
                            }
                        }
                    }
                }.addOnFailureListener {
                    auth.currentUser?.delete()
                    callback(false, "Erro de permissão no banco: ${it.message}")
                }
            } else {
                callback(false, task.exception?.message ?: "Erro ao criar conta")
            }
        }
    }

    fun login(email: String, password: String, callback: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val uid = auth.currentUser?.uid ?: ""
                db.child("uid_to_username").child(uid).get().addOnSuccessListener { snapshot ->
                    val username = snapshot.getValue(String::class.java)
                    if (username != null) {
                        _myUsername.value = username
                        _isUserLoggedIn.value = true
                        setupUserSession()
                        callback(true, null)
                    } else {
                        auth.signOut()
                        callback(false, "Perfil não encontrado no banco de dados.")
                    }
                }.addOnFailureListener {
                    auth.signOut()
                    callback(false, "Erro ao carregar dados do perfil: ${it.message}")
                }
            } else {
                callback(false, task.exception?.message ?: "E-mail ou senha incorretos")
            }
        }
    }

    fun resetPassword(email: String, callback: (Boolean, String?) -> Unit) {
        auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
            if (task.isSuccessful) callback(true, null)
            else callback(false, task.exception?.message)
        }
    }

    fun signInWithGoogle(idToken: String, callback: (Boolean, String?) -> Unit) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener { task ->
            if (task.isSuccessful) { 
                val user = auth.currentUser
                val uid = user?.uid ?: ""
                db.child("uid_to_username").child(uid).get().addOnSuccessListener { snapshot ->
                    if (snapshot.exists()) {
                        _isUserLoggedIn.value = true
                        setupUserSession()
                        callback(true, null)
                    } else {
                        val emailBase = user?.email?.split("@")?.get(0)?.uppercase() ?: UUID.randomUUID().toString().take(8).uppercase()
                        val profile = UserProfile(
                            id = emailBase,
                            uid = uid,
                            name = user?.displayName ?: emailBase,
                            photoUrl = user?.photoUrl?.toString(),
                            isOnline = true
                        )
                        viewModelScope.launch {
                            try {
                                db.child("uid_to_username").child(uid).setValue(emailBase).await()
                                db.child("users").child(emailBase).setValue(profile).await()
                                _isUserLoggedIn.value = true
                                setupUserSession()
                                callback(true, null)
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
            else callback(false, task.exception?.message)
        }
    }

    fun updateProfile(name: String, imageUri: Uri?, status: String? = null, presenceStatus: String? = null) {
        val username = _myUsername.value
        if (username.isEmpty()) return
        viewModelScope.launch {
            try {
                var photoUrl = _myPhotoUrl.value
                if (imageUri != null) {
                    photoUrl = uploadToCloudinary(imageUri, "profiles")
                }
                val updates = mutableMapOf<String, Any?>("name" to name)
                if (photoUrl != null) updates["photoUrl"] = photoUrl
                if (status != null) updates["status"] = status
                if (presenceStatus != null) updates["presenceStatus"] = presenceStatus
                
                db.child("users").child(username).updateChildren(updates).await()
                _myName.value = name
                if (photoUrl != null) _myPhotoUrl.value = photoUrl
                if (status != null) _myStatus.value = status
                if (presenceStatus != null) {
                    _myPresenceStatus.value = presenceStatus
                    updatePresence(FriendApplication.isAppInForeground)
                }
            } catch (e: Exception) {}
        }
    }

    fun deleteAccount(callback: (Boolean, String?) -> Unit) {
        val user = auth.currentUser ?: return
        val username = _myUsername.value
        
        viewModelScope.launch {
            try {
                // 1. Notificar todos que a conta foi excluída nos resumos de conversa
                val chatsSnapshot = db.child("chats").child(username).get().await()
                chatsSnapshot.children.forEach { chatSnap ->
                    val summary = chatSnap.getValue(ChatSummary::class.java) ?: return@forEach
                    val friendId = summary.friendId
                    
                    if (!summary.isGroup) {
                        val update = mapOf(
                            "friendName" to "Conta Excluída",
                            "lastMessage" to "Esta conta foi excluída",
                            "friendPhotoUrl" to null,
                            "isOnline" to false,
                            "presenceStatus" to "Offline"
                        )
                        db.child("chats").child(friendId).child(username).updateChildren(update)
                    }
                }

                // 2. Excluir do Firebase Auth
                user.delete().await()

                // 3. Limpar dados do banco de dados
                if (username.isNotEmpty()) {
                    db.child("users").child(username).removeValue()
                    db.child("chats").child(username).removeValue()
                    db.child("contacts").child(username).removeValue()
                    db.child("blocks").child(username).removeValue()
                    db.child("status").orderByChild("userId").equalTo(username).get().addOnSuccessListener { 
                        it.children.forEach { s -> s.ref.removeValue() }
                    }
                }
                db.child("uid_to_username").child(user.uid).removeValue()
                
                logout()
                callback(true, null)
            } catch (e: Exception) {
                callback(false, e.message)
            }
        }
    }

    fun uploadStatus(uri: Uri) {
        val uid = _myUsername.value
        if (uid.isEmpty()) return
        viewModelScope.launch {
            try {
                val url = uploadToCloudinary(uri, "statuses")
                if (url != null) {
                    val statusId = db.push().key ?: return@launch
                    val status = UserStatus(id = statusId, userId = uid, username = _myName.value, imageUrl = url, userPhotoUrl = _myPhotoUrl.value, timestamp = System.currentTimeMillis())
                    db.child("status").child(statusId).setValue(status)
                }
            } catch (e: Exception) {}
        }
    }

    fun deleteStatus(statusId: String) {
        db.child("status").child(statusId).removeValue()
    }

    fun markStatusAsViewed(statusId: String) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        db.child("status").child(statusId).child("viewers").child(me).setValue(System.currentTimeMillis())
    }

    private fun listenToStatuses(myUsername: String) {
        statusesListener?.let { db.child("status").removeEventListener(it) }
        statusesListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val now = System.currentTimeMillis()
                val blocked = _blockedUsers.value.toSet()
                val list = s.children.mapNotNull { it.getValue(UserStatus::class.java) }
                    .filter { now - it.timestamp < 86400000 && !blocked.contains(it.userId) }
                
                db.child("contacts").child(myUsername).get().addOnSuccessListener { contactSnapshot ->
                    val contactIds = contactSnapshot.children.mapNotNull { it.key }.toSet()
                    _statuses.value = list.filter { it.userId == myUsername || contactIds.contains(it.userId) }
                        .sortedByDescending { it.timestamp }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.child("status").limitToLast(50).addValueEventListener(statusesListener!!)
    }

    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch {
            delay(300) 
            try {
                val blocked = _blockedUsers.value.toSet()
                val snapshot = db.child("users").get().await()
                val users = snapshot.children.mapNotNull { it.getValue(UserProfile::class.java) }
                    .filter { it.id != _myUsername.value && !blocked.contains(it.id) }
                    .filter { 
                        it.name.contains(query, ignoreCase = true) || 
                        it.id.contains(query, ignoreCase = true) 
                    }
                    .distinctBy { it.id }
                _searchResults.value = users
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Search error: ${e.message}")
            }
        }
    }

    fun deleteChat(friendId: String) {
        val me = _myUsername.value
        if (me.isEmpty() || friendId.isBlank()) return // Proteção crítica
        
        db.child("chats").child(me).child(friendId).get().addOnSuccessListener { snapshot ->
            val summary = snapshot.getValue(ChatSummary::class.java)
            if (summary?.isGroup == true) {
                val summaryUpdate = mapOf("lastMessage" to "Conversa excluída", "hasUnread" to false)
                db.child("chats").child(me).child(friendId).updateChildren(summaryUpdate)
            } else {
                db.child("chats").child(me).child(friendId).removeValue()
            }
        }
    }

    fun clearChat(friendId: String, isGroup: Boolean) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        
        if (isGroup) {
            val summaryUpdate = mapOf("lastMessage" to "Conversa limpa", "timestamp" to System.currentTimeMillis(), "hasUnread" to false)
            db.child("chats").child(me).child(friendId).updateChildren(summaryUpdate)
        } else {
            val path = "messages/${chatPathFor(me, friendId)}"
            db.child(path).removeValue()
            
            val summaryUpdate = mapOf("lastMessage" to "Conversa limpa", "timestamp" to System.currentTimeMillis(), "hasUnread" to false)
            db.child("chats").child(me).child(friendId).updateChildren(summaryUpdate)
            db.child("chats").child(friendId).child(me).updateChildren(summaryUpdate)
        }
    }

    fun blockUser(targetId: String) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        db.child("blocks").child(me).child(targetId).setValue(true)
    }

    fun unblockUser(targetId: String) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        db.child("blocks").child(me).child(targetId).removeValue()
    }

    private fun listenToBlockedUsers(username: String) {
        blockedListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val ids = s.children.mapNotNull { it.key }
                _blockedUsers.value = ids
                
                // Forçar atualização imediata das outras listas
                listenToChats(username)
                listenToContacts(username)
                listenToStatuses(username)

                viewModelScope.launch {
                    try {
                        val profiles = ids.mapNotNull { id ->
                            db.child("users").child(id).get().await().getValue(UserProfile::class.java)
                        }
                        _blockedProfiles.value = profiles
                    } catch (e: Exception) {}
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("blocks").child(username).addValueEventListener(blockedListener!!)
    }

    fun deleteMessage(messageId: String, friendId: String, isGroup: Boolean) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        val path = if (isGroup) "group_messages/$friendId" else "messages/${chatPathFor(me, friendId)}"
        
        db.child(path).child(messageId).removeValue().addOnSuccessListener {
            db.child(path).orderByChild("timestamp").limitToLast(1).get().addOnSuccessListener { snapshot ->
                val lastMsg = snapshot.children.firstOrNull()?.getValue(Message::class.java)
                val summaryRefMe = db.child("chats").child(me).child(friendId)
                val summaryRefFriend = db.child("chats").child(friendId).child(me)

                if (lastMsg != null) {
                    val updatedText = if (lastMsg.audioUrl != null) "Áudio" else if (lastMsg.imageUrl != null) "Imagem" else lastMsg.text
                    summaryRefMe.child("lastMessage").setValue(updatedText)
                    summaryRefMe.child("timestamp").setValue(lastMsg.timestamp)
                    if (!isGroup) {
                        summaryRefFriend.child("lastMessage").setValue(updatedText)
                        summaryRefFriend.child("timestamp").setValue(lastMsg.timestamp)
                    }
                } else {
                    summaryRefMe.child("lastMessage").setValue("Conversa vazia")
                    if (!isGroup) summaryRefFriend.child("lastMessage").setValue("Conversa vazia")
                }
            }
        }
    }

    fun startCall(isVideo: Boolean, isGroup: Boolean, customRoomId: String) {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return
        
        viewModelScope.launch {
            try {
                if (!isGroup) {
                    if (_blockedUsers.value.contains(target)) return@launch
                }

                val roomId = customRoomId
                val callData = mapOf(
                    "callerId" to me,
                    "receiverId" to target,
                    "status" to "RINGING",
                    "isVideo" to isVideo,
                    "timestamp" to ServerValue.TIMESTAMP
                )

                db.child("calls").child(roomId).setValue(callData)

                val msgId = db.push().key ?: return@launch
                val msg = Message(
                    id = msgId,
                    senderId = me,
                    receiverId = target,
                    text = if (isVideo) "Chamada de vídeo" else "Chamada de áudio",
                    timestamp = System.currentTimeMillis(),
                    isGroup = isGroup,
                    senderName = _myName.value,
                    senderPhotoUrl = _myPhotoUrl.value,
                    callRoomId = roomId,
                    callType = if (isVideo) "VIDEO" else "AUDIO",
                    callStatus = "STARTING",
                    isCall = true
                )
                
                db.child("call_notifications").child(target).setValue(msg)
                
                val path = if (isGroup) "group_messages/$target" else "messages/${chatPathFor(me, target)}"
                db.child(path).child(msgId).setValue(msg)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Erro ao iniciar chamada: ${e.message}")
            }
        }
    }
}
