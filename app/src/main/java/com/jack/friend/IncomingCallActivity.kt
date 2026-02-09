package com.jack.friend

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.jack.friend.ui.theme.FriendTheme

class IncomingCallActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var callStatusListener: ValueEventListener? = null
    private var roomId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configurações para aparecer sobre a tela de bloqueio
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val callMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("callMessage", Message::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("callMessage") as? Message
        }

        if (callMessage == null) { finish(); return }
        roomId = callMessage.callRoomId

        observeCallStatus()
        startRingtone()

        setContent {
            FriendTheme {
                IncomingCallScreen(
                    callerName = callMessage.senderName ?: callMessage.senderId,
                    onAccept = { acceptCall(callMessage) },
                    onReject = { rejectCall() }
                )
            }
        }
    }

    private fun observeCallStatus() {
        roomId?.let { id ->
            callStatusListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.child("status").getValue(String::class.java)
                    if (status == "ENDED" || status == "REJECTED" || status == "CONNECTED") {
                        cancelCallNotification()
                        finish()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            FirebaseDatabase.getInstance().reference.child("calls").child(id)
                .addValueEventListener(callStatusListener!!)
        }
    }

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (e: Exception) {}
    }

    private fun acceptCall(message: Message) {
        cancelCallNotification()
        ringtone?.stop()
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("roomId", message.callRoomId)
            putExtra("targetId", message.senderId)
            putExtra("isOutgoing", false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun rejectCall() {
        roomId?.let { FirebaseDatabase.getInstance().reference.child("calls").child(it).child("status").setValue("REJECTED") }
        cancelCallNotification()
        ringtone?.stop()
        finish()
    }

    private fun cancelCallNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1002) // ID usado no MessagingService
    }

    override fun onDestroy() {
        ringtone?.stop()
        roomId?.let { id -> 
            callStatusListener?.let { 
                FirebaseDatabase.getInstance().reference.child("calls").child(id).removeEventListener(it) 
            } 
        }
        super.onDestroy()
    }
}

@Composable
fun IncomingCallScreen(callerName: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 100.dp)) {
            Surface(modifier = Modifier.size(120.dp), shape = CircleShape, color = Color.DarkGray) {
                Icon(Icons.Default.Person, null, modifier = Modifier.padding(30.dp), tint = Color.LightGray)
            }
            Spacer(Modifier.height(24.dp))
            Text(callerName, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Chamada de áudio...", color = Color.Gray, fontSize = 18.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 100.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onReject, 
                containerColor = Color(0xFFFF3B30), 
                contentColor = Color.White, 
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(36.dp))
            }
            FloatingActionButton(
                onClick = onAccept, 
                containerColor = Color(0xFF34C759), 
                contentColor = Color.White, 
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(Icons.Default.Call, null, modifier = Modifier.size(36.dp))
            }
        }
    }
}
