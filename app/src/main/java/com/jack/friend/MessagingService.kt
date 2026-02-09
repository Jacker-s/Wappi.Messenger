package com.jack.friend

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
    private var myUsername: String? = null
    
    // Lista de listeners ativos para limpar notificações de chamadas encerradas
    private val activeCallListeners = mutableMapOf<String, ValueEventListener>()

    companion object {
        private const val CALL_CHANNEL_ID = "CALL_CHANNEL_V7"
        private const val SILENT_CHANNEL_ID = "silent_service_channel"
        private const val FOREGROUND_ID = 1001
        private const val CALL_NOTIF_ID = 1002
        private const val TAG = "MessagingService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startSilentForeground()
        
        val user = auth.currentUser
        if (user != null) {
            setupUserListener(user.uid)
        }
    }

    private fun startSilentForeground() {
        val notification = NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Friend")
            .setContentText("Ligado para receber chamadas")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun setupUserListener(uid: String) {
        database.child("uid_to_username").child(uid).get().addOnSuccessListener { snapshot ->
            val username = snapshot.getValue(String::class.java) ?: return@addOnSuccessListener
            myUsername = username
            listenForCallSignals(username)
        }
    }

    private fun listenForCallSignals(username: String) {
        val signalsRef = database.child("call_notifications").child(username)
        
        callNotificationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val m = snapshot.getValue(Message::class.java) ?: return
                    
                    if (m.isCall && m.callStatus == "STARTING") {
                        showIncomingCall(m)
                        // Limpa o sinal para não disparar novamente
                        signalsRef.removeValue()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao processar sinal de chamada: ${e.message}")
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        
        signalsRef.addValueEventListener(callNotificationListener!!)
    }

    private fun showIncomingCall(message: Message) {
        val roomId = message.callRoomId ?: return
        
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("callMessage", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, message.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Chamada de ${message.senderName ?: message.senderId}")
            .setContentText("Toque para atender")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(CALL_NOTIF_ID, builder.build())
        
        // Monitorar o status da chamada para remover a notificação se for encerrada
        monitorCallStatus(roomId)
        
        try { startActivity(intent) } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar IncomingCallActivity: ${e.message}")
        }
    }

    private fun monitorCallStatus(roomId: String) {
        // Evita duplicar listeners para a mesma sala
        if (activeCallListeners.containsKey(roomId)) return

        val callRef = database.child("calls").child(roomId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                if (status == "ENDED" || status == "REJECTED" || status == "CONNECTED") {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(CALL_NOTIF_ID)
                    
                    // Remover listener após o encerramento
                    callRef.removeEventListener(this)
                    activeCallListeners.remove(roomId)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        
        activeCallListeners[roomId] = listener
        callRef.addValueEventListener(listener)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            
            val callChannel = NotificationChannel(CALL_CHANNEL_ID, "Chamadas", NotificationManager.IMPORTANCE_HIGH).apply {
                val attrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs)
                enableVibration(true)
            }
            
            val silentChannel = NotificationChannel(SILENT_CHANNEL_ID, "Serviço", NotificationManager.IMPORTANCE_MIN)
            
            nm.createNotificationChannel(callChannel)
            nm.createNotificationChannel(silentChannel)
        }
    }

    override fun onDestroy() {
        // Limpar todos os listeners ativos
        myUsername?.let { username ->
            callNotificationListener?.let {
                database.child("call_notifications").child(username).removeEventListener(it)
            }
        }
        activeCallListeners.forEach { (roomId, listener) ->
            database.child("calls").child(roomId).removeEventListener(listener)
        }
        activeCallListeners.clear()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?) = null
}
