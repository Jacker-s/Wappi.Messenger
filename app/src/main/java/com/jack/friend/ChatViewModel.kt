package com.jack.friend

import android.media.MediaRecorder
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

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

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _activeChats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val activeChats: StateFlow<List<ChatSummary>> = _activeChats

    private val _targetId = MutableStateFlow("")
    val targetId: StateFlow<String> = _targetId

    private val _targetProfile = MutableStateFlow<UserProfile?>(null)
    val targetProfile: StateFlow<UserProfile?> = _targetProfile

    private val _targetGroup = MutableStateFlow<Group?>(null)
    val targetGroup: StateFlow<Group?> = _targetGroup

    private val _isTargetTyping = MutableStateFlow(false)
    val isTargetTyping: StateFlow<Boolean> = _isTargetTyping

    private val _searchResults = MutableStateFlow<List<UserProfile>>(emptyList())
    val searchResults: StateFlow<List<UserProfile>> = _searchResults

    private val _statuses = MutableStateFlow<List<UserStatus>>(emptyList())
    val statuses: StateFlow<List<UserStatus>> = _statuses

    private var chatsListener: ValueEventListener? = null
    private var messagesListener: ValueEventListener? = null
    private var targetProfileListener: ValueEventListener? = null
    private var typingListener: ValueEventListener? = null
    private var currentChatPath: String? = null
    private val presenceListeners = mutableMapOf<String, ValueEventListener>()

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    init {
        if (auth.currentUser != null) setupUserSession()
    }

    private fun setupUserSession() {
        val uid = auth.currentUser?.uid ?: return
        db.child("uid_to_username").child(uid).get().addOnSuccessListener { snapshot ->
            val username = snapshot.getValue(String::class.java) ?: ""
            _myUsername.value = username
            if (username.isNotEmpty()) {
                loadMyProfile(username)
                listenToChats(username)
                listenToStatuses()
                setupPresence(username)
            }
        }
    }

    private fun loadMyProfile(username: String) {
        db.child("users").child(username).get().addOnSuccessListener { snapshot ->
            val profile = snapshot.getValue(UserProfile::class.java)
            _myName.value = profile?.name ?: username
            _myPhotoUrl.value = profile?.photoUrl
        }
    }

    private fun setupPresence(username: String) {
        val statusRef = db.child("users").child(username).child("isOnline")
        db.child(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) == true) {
                    statusRef.setValue(true)
                    statusRef.onDisconnect().setValue(false)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
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
        if (_myUsername.value.isNotEmpty()) {
            chatsListener?.let { db.child("chats").child(_myUsername.value).removeEventListener(it) }
        }
        currentChatPath?.let { path ->
            messagesListener?.let { l -> db.child(path).removeEventListener(l) }
        }
        presenceListeners.forEach { (uid, listener) -> db.child("users").child(uid).child("isOnline").removeEventListener(listener) }
        presenceListeners.clear()
    }

    fun setTargetId(id: String, isGroup: Boolean = false) {
        if (_targetId.value == id && id.isNotEmpty()) return

        _targetId.value.takeIf { it.isNotEmpty() }?.let { oldId ->
            targetProfileListener?.let { db.child("users").child(oldId).removeEventListener(it) }
            typingListener?.let { db.child("typing").child(chatPathFor(_myUsername.value, oldId)).child(oldId).removeEventListener(it) }
        }

        _targetId.value = id

        if (id.isEmpty()) {
            _targetProfile.value = null
            _targetGroup.value = null
            _messages.value = emptyList()
            _isTargetTyping.value = false
            return
        }

        if (!isGroup) {
            targetProfileListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _targetProfile.value = snapshot.getValue(UserProfile::class.java)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            db.child("users").child(id).addValueEventListener(targetProfileListener!!)
            listenToTyping(id)
        } else {
            db.child("groups").child(id).get().addOnSuccessListener { _targetGroup.value = it.getValue(Group::class.java) }
        }

        listenToMessages(id, isGroup)
    }

    private fun listenToTyping(target: String) {
        val path = chatPathFor(_myUsername.value, target)
        typingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _isTargetTyping.value = snapshot.getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("typing").child(path).child(target).addValueEventListener(typingListener!!)
    }

    fun setTyping(isTyping: Boolean) {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return
        db.child("typing").child(chatPathFor(me, target)).child(me).setValue(isTyping)
    }

    fun sendMessage(text: String, isGroup: Boolean) {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return

        val msgId = db.push().key ?: return
        val msg = Message(id = msgId, senderId = me, receiverId = target, text = text, timestamp = System.currentTimeMillis(), isGroup = isGroup, senderName = _myName.value)

        val path = if (isGroup) "group_messages/$target" else "messages/${chatPathFor(me, target)}"
        db.child(path).child(msgId).setValue(msg)
        updateChatSummary(msg)
        setTyping(false)
    }

    fun markAsRead() {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return

        val path = "messages/${chatPathFor(me, target)}"
        db.child(path).orderByChild("receiverId").equalTo(me).get().addOnSuccessListener { snapshot ->
            snapshot.children.forEach {
                if (it.child("isRead").getValue(Boolean::class.java) != true) {
                    it.ref.child("isRead").setValue(true)
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
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun stopRecording(isGroup: Boolean) {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            audioFile?.let { uploadAudio(it, isGroup) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun uploadAudio(file: File, isGroup: Boolean) {
        val me = _myUsername.value
        val target = _targetId.value
        if (me.isEmpty() || target.isEmpty()) return

        viewModelScope.launch {
            try {
                val msgId = db.push().key ?: return@launch
                val ref = storage.child("chat_audios/$msgId.m4a")
                ref.putFile(Uri.fromFile(file)).await()
                val url = ref.downloadUrl.await().toString()

                val msg = Message(id = msgId, senderId = me, receiverId = target, audioUrl = url, timestamp = System.currentTimeMillis(), isGroup = isGroup, senderName = _myName.value)
                sendMessageObject(msg)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun uploadImage(uri: Uri, isGroup: Boolean) {
        val me = _myUsername.value
        val tid = _targetId.value
        if (me.isEmpty() || tid.isEmpty()) return
        viewModelScope.launch {
            try {
                val msgId = db.push().key ?: return@launch
                val ref = storage.child("chat_images/$msgId.jpg")
                ref.putFile(uri).await()
                val url = ref.downloadUrl.await().toString()
                sendMessageObject(Message(id = msgId, senderId = me, receiverId = tid, imageUrl = url, timestamp = System.currentTimeMillis(), isGroup = isGroup, senderName = _myName.value))
            } catch (e: Exception) {}
        }
    }

    private fun sendMessageObject(msg: Message) {
        val path = if (msg.isGroup) "group_messages/${msg.receiverId}" else "messages/${chatPathFor(msg.senderId, msg.receiverId)}"
        db.child(path).child(msg.id).setValue(msg)
        updateChatSummary(msg)
    }

    private fun updateChatSummary(msg: Message) {
        viewModelScope.launch {
            val me = msg.senderId
            val friend = msg.receiverId
            val friendProf = db.child("users").child(friend).get().await().getValue(UserProfile::class.java)
            val summary = ChatSummary(friendId = friend, lastMessage = if (msg.audioUrl != null) "Áudio" else if (msg.imageUrl != null) "Imagem" else msg.text, timestamp = msg.timestamp, lastSenderId = me, friendName = friendProf?.name ?: friend, friendPhotoUrl = friendProf?.photoUrl, isGroup = msg.isGroup, isOnline = friendProf?.isOnline ?: false, hasUnread = false)
            db.child("chats").child(me).child(friend).setValue(summary)
            if (!msg.isGroup) {
                val meProf = db.child("users").child(me).get().await().getValue(UserProfile::class.java)
                db.child("chats").child(friend).child(me).setValue(summary.copy(friendId = me, friendName = meProf?.name ?: me, friendPhotoUrl = meProf?.photoUrl, hasUnread = true))
            }
        }
    }

    private fun chatPathFor(u1: String, u2: String) = if (u1 < u2) "${u1}_$u2" else "${u2}_$u1"

    private fun listenToChats(username: String) {
        chatsListener?.let { db.child("chats").child(username).removeEventListener(it) }
        chatsListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val chats = s.children.mapNotNull { it.getValue(ChatSummary::class.java) }.sortedByDescending { it.timestamp }
                _activeChats.value = chats
                chats.forEach { chat -> syncFriendPresence(chat.friendId) }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.child("chats").child(username).addValueEventListener(chatsListener!!)
    }

    private fun syncFriendPresence(friendUsername: String) {
        if (presenceListeners.containsKey(friendUsername)) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.getValue(Boolean::class.java) ?: false
                _activeChats.value = _activeChats.value.map {
                    if (it.friendId == friendUsername) it.copy(isOnline = isOnline) else it
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        presenceListeners[friendUsername] = listener
        db.child("users").child(friendUsername).child("isOnline").addValueEventListener(listener)
    }

    private fun listenToMessages(target: String, isGroup: Boolean) {
        val me = _myUsername.value
        if (me.isEmpty()) return
        currentChatPath?.let { path -> messagesListener?.let { l -> db.child(path).removeEventListener(l) } }
        val path = if (isGroup) "group_messages/$target" else "messages/${chatPathFor(me, target)}"
        currentChatPath = path
        messagesListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { _messages.value = s.children.mapNotNull { it.getValue(Message::class.java) } }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.child(path).addValueEventListener(messagesListener!!)
    }

    fun signUp(email: String, password: String, username: String, imageUri: Uri?, callback: (Boolean, String?) -> Unit) {
        val upper = username.uppercase().trim()
        db.child("users").child(upper).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) return@addOnSuccessListener callback(false, "Username já existe")
            auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: ""
                    _myId.value = uid
                    viewModelScope.launch {
                        try {
                            var photoUrl: String? = null
                            imageUri?.let {
                                val ref = storage.child("profiles/$upper.jpg")
                                ref.putFile(it).await()
                                photoUrl = ref.downloadUrl.await().toString()
                            }
                            val profile = UserProfile(id = upper, uid = uid, name = username, photoUrl = photoUrl, isOnline = true)
                            db.child("uid_to_username").child(uid).setValue(upper).await()
                            db.child("users").child(upper).setValue(profile).await()
                            _isUserLoggedIn.value = true
                            setupUserSession()
                            callback(true, null)
                        } catch (e: Exception) { callback(false, e.message) }
                    }
                } else callback(false, task.exception?.message)
            }
        }
    }

    fun login(email: String, password: String, callback: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            if (task.isSuccessful) { _isUserLoggedIn.value = true; setupUserSession(); callback(true, null) }
            else callback(false, task.exception?.message)
        }
    }

    fun signInWithGoogle(idToken: String, callback: (Boolean, String?) -> Unit) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener { task ->
            if (task.isSuccessful) { _isUserLoggedIn.value = true; setupUserSession(); callback(true, null) }
            else callback(false, task.exception?.message)
        }
    }

    fun updateProfile(name: String, imageUri: Uri?) {
        val username = _myUsername.value
        if (username.isEmpty()) return
        viewModelScope.launch {
            try {
                var photoUrl = _myPhotoUrl.value
                if (imageUri != null) {
                    val ref = storage.child("profiles/$username.jpg")
                    ref.putFile(imageUri).await()
                    photoUrl = ref.downloadUrl.await().toString()
                }
                val updates = mapOf("name" to name, "photoUrl" to photoUrl)
                db.child("users").child(username).updateChildren(updates).await()
                _myName.value = name; _myPhotoUrl.value = photoUrl
            } catch (e: Exception) {}
        }
    }

    fun deleteAccount(callback: (Boolean, String?) -> Unit) {
        val user = auth.currentUser ?: return
        val username = _myUsername.value
        user.delete().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                if (username.isNotEmpty()) { db.child("users").child(username).removeValue(); db.child("chats").child(username).removeValue() }
                db.child("uid_to_username").child(user.uid).removeValue()
                logout(); callback(true, null)
            } else callback(false, task.exception?.message)
        }
    }

    fun updatePresence(online: Boolean) {
        val user = _myUsername.value
        if (user.isNotEmpty()) db.child("users").child(user).child("isOnline").setValue(online)
    }

    fun uploadStatus(uri: Uri) {
        val uid = _myUsername.value
        if (uid.isEmpty()) return
        viewModelScope.launch {
            try {
                val statusId = db.push().key ?: return@launch
                val ref = storage.child("statuses/$statusId.jpg")
                ref.putFile(uri).await()
                val url = ref.downloadUrl.await().toString()
                val status = UserStatus(id = statusId, userId = uid, username = _myName.value, imageUrl = url, userPhotoUrl = _myPhotoUrl.value, timestamp = System.currentTimeMillis())
                db.child("status").child(statusId).setValue(status)
            } catch (e: Exception) {}
        }
    }

    fun startCall(isGroup: Boolean, isVideo: Boolean) {
        val me = _myUsername.value; val tid = _targetId.value
        if (me.isEmpty() || tid.isEmpty()) return
        val callKey = "WapiCall_Private_${if (me < tid) "${me.lowercase()}_${tid.lowercase()}" else "${tid.lowercase()}_${me.lowercase()}"}"
        
        val callMessage = Message(
            id = db.push().key ?: "",
            senderId = me,
            receiverId = tid,
            senderName = _myName.value,
            timestamp = System.currentTimeMillis(),
            isCall = true,
            callType = if (isVideo) "VIDEO" else "AUDIO",
            callStatus = "STARTING",
            callRoomId = callKey,
            isGroup = isGroup
        )
        
        val callData = mapOf(
            "callerId" to me,
            "targetId" to tid,
            "isVideo" to isVideo,
            "status" to "STARTING",
            "timestamp" to System.currentTimeMillis(),
            "callerName" to _myName.value,
            "callMessage" to callMessage
        )
        
        db.child("calls").child(callKey).setValue(callData)
        
        // Também envia como mensagem para garantir o acionamento do FCM
        val path = if (isGroup) "group_messages/$tid" else "messages/${chatPathFor(me, tid)}"
        db.child(path).child(callMessage.id).setValue(callMessage)
    }

    private fun listenToStatuses() {
        db.child("status").limitToLast(50).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val now = System.currentTimeMillis()
                _statuses.value = s.children.mapNotNull { it.getValue(UserStatus::class.java) }.filter { now - it.timestamp < 86400000 }
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    fun searchUsers(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        db.child("users").get().addOnSuccessListener { snapshot ->
            val users = snapshot.children.mapNotNull { it.getValue(UserProfile::class.java) }
                .filter { it.id != _myUsername.value }
                .filter { it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
                .distinctBy { it.id }
            _searchResults.value = users
        }
    }
}
