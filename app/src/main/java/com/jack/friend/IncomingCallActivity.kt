package com.jack.friend

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.jack.friend.ui.theme.FriendTheme
import java.io.Serializable

class IncomingCallActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: android.os.Vibrator? = null
    private var callStatusListener: ValueEventListener? = null
    private var roomName: String? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.jack.friend.ACTION_CLOSE_INCOMING_CALL") {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configurações críticas para aparecer sobre a tela de bloqueio e acordar o CPU
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Registrar BroadcastReceiver para fechar a atividade remotamente
        LocalBroadcastManager.getInstance(this).registerReceiver(
            closeReceiver,
            IntentFilter("com.jack.friend.ACTION_CLOSE_INCOMING_CALL")
        )

        val callMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("callMessage", Message::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("callMessage") as? Message
        }

        if (callMessage == null) {
            finish()
            return
        }

        roomName = if (callMessage.isGroup) {
            "WapiCall_Group_${callMessage.receiverId.lowercase()}"
        } else {
            val ids = listOf(callMessage.senderId, callMessage.receiverId).sorted()
            "WapiCall_Private_${ids[0].lowercase()}_${ids[1].lowercase()}"
        }

        observeCallStatus()
        startRingtoneAndVibration()

        setContent {
            FriendTheme {
                val viewModel: ChatViewModel = viewModel()
                ModernIncomingCallOverlay(callMessage, viewModel)
            }
        }
    }

    private fun observeCallStatus() {
        val room = roomName ?: return
        callStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val status = snapshot.child("status").getValue(String::class.java)
                    if (status != "RINGING") {
                        finish()
                    }
                } else {
                    finish()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        FirebaseDatabase.getInstance().reference.child("calls").child(room)
            .addValueEventListener(callStatusListener!!)
    }

    private fun startRingtoneAndVibration() {
        try {
            val notification: android.net.Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, notification)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRingtoneAndVibration() {
        ringtone?.stop()
        vibrator?.cancel()
    }

    override fun onDestroy() {
        stopRingtoneAndVibration()
        roomName?.let { room ->
            callStatusListener?.let { listener ->
                FirebaseDatabase.getInstance().reference.child("calls").child(room)
                    .removeEventListener(listener)
            }
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(closeReceiver)
        super.onDestroy()
    }
}
