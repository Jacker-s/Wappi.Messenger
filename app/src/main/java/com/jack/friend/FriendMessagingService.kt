package com.jack.friend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson

class FriendMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "MESSAGES_CHANNEL_V7" 
        private const val TAG = "FriendMessagingService"
        private const val GROUP_KEY = "com.jack.friend.MESSAGES_GROUP"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        Log.d(TAG, "Mensagem recebida do FCM: ${remoteMessage.data}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val type = data["type"]
            val messageJson = data["message"]
            
            if (messageJson != null) {
                try {
                    val message = Gson().fromJson(messageJson, Message::class.java)
                    
                    // Verifica se é uma chamada - Notificação de sistema removida conforme solicitado
                    if (type == "CALL" || (message.callType != null && message.callStatus == "STARTING")) {
                        // Tenta abrir a activity diretamente se possível
                        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
                            putExtra("callMessage", message)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        try {
                            startActivity(fullScreenIntent)
                        } catch (e: Exception) {
                            Log.d(TAG, "Não foi possível abrir a Activity de chamada diretamente do background")
                        }
                    } else {
                        // Notificação de mensagem corrigida e melhorada
                        showNotification(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao processar JSON: ${e.message}")
                }
            }
        }
    }

    private fun showNotification(message: Message) {
        createChannels()

        val chatId = if (message.isGroup) message.receiverId else message.senderId
        val senderName = if (message.isGroup) message.senderName ?: "Grupo" else message.senderName ?: message.senderId
        
        val contentText = when {
            message.isDeleted -> "🚫 Mensagem apagada"
            message.imageUrl != null -> "📷 Foto"
            message.audioUrl != null -> "🎤 Áudio"
            message.isSticker -> "🖼 Figurinha"
            else -> message.text
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("targetId", chatId)
            putExtra("isGroup", message.isGroup)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 
            chatId.hashCode(), 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        // Notificação individual por chat
        notificationManager.notify(chatId.hashCode(), builder.build())
        
        // Sumário para agrupar as notificações
        val summaryNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(0, summaryNotification)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val msgChannel = NotificationChannel(CHANNEL_ID, "Mensagens", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notificações de conversas e grupos"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC 
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(msgChannel)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        sendRegistrationToServer(token)
    }

    private fun sendRegistrationToServer(token: String?) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().reference.child("uid_to_username").child(uid).get()
            .addOnSuccessListener { snapshot ->
                val username = snapshot.getValue(String::class.java)
                if (username != null && token != null) {
                    FirebaseDatabase.getInstance().reference
                        .child("fcmTokens")
                        .child(username)
                        .child("token")
                        .setValue(token)
                }
            }
    }
}
