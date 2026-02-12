package com.jack.friend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MessagingService : Service() {

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var callNotificationListener: ValueEventListener? = null
    private var blockedListener: ValueEventListener? = null
    private var myUsername: String? = null
    private var blockedUsers = mutableSetOf<String>()

    companion object {
        private const val CALL_CHANNEL_ID = "CALL_CHANNEL_V14" 
        private const val MSG_CHANNEL_ID = "MESSAGE_CHANNEL_V1"
        private const val SILENT_CHANNEL_ID = "silent_service_channel"
        private const val FOREGROUND_ID = 1001
        private const val CALL_NOTIF_ID = 1002
        private const val TAG = "MessagingService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startSilentForeground()
        auth.currentUser?.let { setupUserListener(it.uid) }
    }

    private fun startSilentForeground() {
        val notification = NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Friend").setContentText("Ativo para chamadas e mensagens").setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(FOREGROUND_ID, notification)
    }

    private fun setupUserListener(uid: String) {
        database.child("uid_to_username").child(uid).get().addOnSuccessListener { snapshot ->
            myUsername = snapshot.getValue(String::class.java)
            myUsername?.let { 
                listenToBlockedUsers(it)
                listenForCallSignals(it)
                listenForNewMessages(it)
            }
        }
    }

    private fun listenToBlockedUsers(username: String) {
        blockedListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                blockedUsers = s.children.mapNotNull { it.key }.toMutableSet()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("blocks").child(username).addValueEventListener(blockedListener!!)
    }

    private fun listenForNewMessages(username: String) {
        database.child("chats").child(username).addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleMessageSnapshot(snapshot, username)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                handleMessageSnapshot(snapshot, username)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun handleMessageSnapshot(snapshot: DataSnapshot, username: String) {
        val summary = snapshot.getValue(ChatSummary::class.java) ?: return
        
        // Ignorar se o remetente estiver bloqueado
        if (blockedUsers.contains(summary.friendId)) return

        if (!FriendApplication.isAppInForeground) {
            if (summary.hasUnread && summary.lastSenderId != username) {
                showNewMessageNotification(summary)
            }
        }
    }

    private fun showNewMessageNotification(summary: ChatSummary) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(this, summary.friendId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, MSG_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(summary.friendName ?: summary.friendId)
            .setContentText(summary.lastMessage)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(summary.friendId.hashCode(), builder.build())
    }

    private fun listenForCallSignals(username: String) {
        val signalsRef = database.child("call_notifications").child(username)
        callNotificationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val m = snapshot.getValue(Message::class.java) ?: return
                
                // Ignorar chamada se o autor estiver bloqueado
                if (blockedUsers.contains(m.senderId)) {
                    signalsRef.removeValue()
                    return
                }

                if (m.isCall && m.callStatus == "STARTING") {
                    showIncomingCall(m)
                    signalsRef.removeValue()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        signalsRef.addValueEventListener(callNotificationListener!!)
    }

    private fun showIncomingCall(message: Message) {
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("callMessage", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        if (FriendApplication.isAppInForeground) {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao abrir Activity: ${e.message}")
            }
            return
        }

        val acceptIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("roomId", message.callRoomId)
            putExtra("targetId", message.senderId)
            putExtra("targetPhotoUrl", message.senderPhotoUrl)
            putExtra("isOutgoing", false)
            putExtra("isAcceptedFromNotification", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val acceptPI = PendingIntent.getActivity(this, 1, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val rejectIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = "ACTION_REJECT"
            putExtra("roomId", message.callRoomId)
        }
        val rejectPI = PendingIntent.getBroadcast(this, 2, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        
        val builder = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Chamada de ${message.senderName ?: message.senderId}")
            .setContentText("Toque para atender")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(ringtoneUri)
            .addAction(R.drawable.ic_call, "Atender", acceptPI)
            .addAction(R.drawable.ic_call_end, "Recusar", rejectPI)

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(CALL_NOTIF_ID, builder.build())
        message.callRoomId?.let { monitorCallStatus(it) }
    }

    private fun monitorCallStatus(roomId: String) {
        val callRef = database.child("calls").child(roomId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                if (status == "ENDED" || status == "REJECTED" || status == "CONNECTED") {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(CALL_NOTIF_ID)
                    callRef.removeEventListener(this)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        callRef.addValueEventListener(listener)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            
            val callChannel = NotificationChannel(CALL_CHANNEL_ID, "Chamadas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notificações de chamadas recebidas"
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), audioAttributes)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(callChannel)
            
            nm.createNotificationChannel(NotificationChannel(MSG_CHANNEL_ID, "Mensagens", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notificações de novas mensagens de chat"
                enableVibration(true)
            })
            
            nm.createNotificationChannel(NotificationChannel(SILENT_CHANNEL_ID, "Serviço", NotificationManager.IMPORTANCE_MIN))
        }
    }

    override fun onDestroy() {
        myUsername?.let { 
            database.child("call_notifications").child(it).removeEventListener(callNotificationListener!!)
            blockedListener?.let { l -> database.child("blocks").child(it).removeEventListener(l) }
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?) = null
}
