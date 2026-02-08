package com.jack.friend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MessagingService : Service() {

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var globalListener: ChildEventListener? = null
    private var myUsername: String? = null

    companion object {
        private const val CHANNEL_ID = "messages_channel"
        private const val CALL_CHANNEL_ID = "CALL_CHANNEL"
        private const val SILENT_CHANNEL_ID = "silent_service_channel"
        private const val FOREGROUND_ID = 1001
        private const val GROUP_KEY = "com.jack.friend.MESSAGES"
        private const val SUMMARY_ID = 0
        private const val TAG = "MessagingService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startSilentForeground()
        observeMessages()
    }

    private fun startSilentForeground() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Friend")
            .setContentText("Serviço de mensagens ativo")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(FOREGROUND_ID, notification)
    }

    private fun observeMessages() {
        val user = auth.currentUser
        if (user != null) {
            fetchUsernameAndSetup(user.uid)
        }
        
        auth.addAuthStateListener { firebaseAuth ->
            val newUser = firebaseAuth.currentUser
            if (newUser != null) {
                fetchUsernameAndSetup(newUser.uid)
            } else {
                removeListener()
                myUsername = null
            }
        }
    }

    private fun fetchUsernameAndSetup(uid: String) {
        database.child("uid_to_username").child(uid).get().addOnSuccessListener { snapshot ->
            val username = snapshot.getValue(String::class.java)
            if (username != null && username != myUsername) {
                myUsername = username
                setupListener(username)
            }
        }
    }

    private fun removeListener() {
        globalListener?.let { 
            database.child("messages").removeEventListener(it)
            globalListener = null
        }
    }

    private fun setupListener(username: String) {
        removeListener()
        val startTime = System.currentTimeMillis()
        
        globalListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val m = snapshot.getValue(Message::class.java) ?: return
                
                if (m.receiverId == username && m.timestamp >= startTime) {
                    if (m.callType != null && m.callStatus == "STARTING") {
                        showIncomingCallNotification(m)
                        return
                    }

                    database.child("users").child(username).child("isOnline").get().addOnSuccessListener { onlineSnapshot ->
                        val isOnline = onlineSnapshot.getValue(Boolean::class.java) ?: false
                        
                        if (!isOnline) {
                            database.child("users").child(m.senderId).child("name").get().addOnSuccessListener { nameSnapshot ->
                                val senderName = nameSnapshot.getValue(String::class.java) ?: m.senderId
                                val content = when {
                                    m.isDeleted -> "🚫 Mensagem apagada"
                                    m.imageUrl != null -> "📷 Foto"
                                    m.audioUrl != null -> "🎤 Áudio"
                                    else -> m.text
                                }
                                showNotification(senderName, content)
                            }
                        }
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val m = snapshot.getValue(Message::class.java) ?: return
                if (m.receiverId == username && m.callStatus != "STARTING") {
                    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(1002)
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        
        database.child("messages")
            .orderByChild("timestamp")
            .startAt(startTime.toDouble())
            .addChildEventListener(globalListener!!)
    }

    private fun showIncomingCallNotification(message: Message) {
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("callMessage", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Chamada de ${message.senderName ?: "Alguém"}")
            .setContentText("Toque para atender")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true) // Isso faz a tela abrir direto
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        // Tenta abrir a activity direto se o app tiver a permissão SYSTEM_ALERT_WINDOW
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível abrir a Activity diretamente: ${e.message}")
        }
        
        notificationManager.notify(1002, builder.build())
    }

    private fun showNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 
            System.currentTimeMillis().toInt(), 
            intent, 
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(pendingIntent)

        val summaryBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Friend")
            .setContentText("Novas mensagens")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.apply {
            notify(System.currentTimeMillis().toInt(), builder.build())
            notify(SUMMARY_ID, summaryBuilder.build())
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            
            val msgChannel = NotificationChannel(CHANNEL_ID, "Mensagens", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notificações de novas mensagens"
                enableLights(true)
                enableVibration(true)
            }
            
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
            
            val silentChannel = NotificationChannel(SILENT_CHANNEL_ID, "Conexão", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Mantém o app conectado"
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannel(msgChannel)
            notificationManager.createNotificationChannel(callChannel)
            notificationManager.createNotificationChannel(silentChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        removeListener()
        super.onDestroy()
    }
}
