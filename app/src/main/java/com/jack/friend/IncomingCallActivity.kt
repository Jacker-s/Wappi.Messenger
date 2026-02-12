package com.jack.friend

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.jack.friend.ui.theme.*

class IncomingCallActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var callStatusListener: ValueEventListener? = null
    private var roomId: String? = null
    private var isAccepted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    callerPhotoUrl = callMessage.senderPhotoUrl,
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
                    if (isAccepted) return
                    val status = snapshot.child("status").getValue(String::class.java)
                    if (status == "ENDED" || status == "REJECTED") {
                        cleanupAndFinish()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            FirebaseDatabase.getInstance().reference.child("calls").child(id).addValueEventListener(callStatusListener!!)
        }
    }

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {}
    }

    private fun acceptCall(message: Message) {
        if (isAccepted) return
        isAccepted = true
        
        stopRingtone()
        roomId?.let { id -> callStatusListener?.let { FirebaseDatabase.getInstance().reference.child("calls").child(id).removeEventListener(it) } }
        
        roomId?.let { FirebaseDatabase.getInstance().reference.child("calls").child(it).child("status").setValue("CONNECTED") }
        
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("roomId", message.callRoomId)
            putExtra("targetId", message.senderId)
            putExtra("targetPhotoUrl", message.senderPhotoUrl)
            putExtra("isOutgoing", false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun rejectCall() {
        roomId?.let { FirebaseDatabase.getInstance().reference.child("calls").child(it).child("status").setValue("REJECTED") }
        cleanupAndFinish()
    }

    private fun stopRingtone() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (e: Exception) {}
        mediaPlayer = null
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(1002)
    }

    private fun cleanupAndFinish() {
        stopRingtone()
        roomId?.let { id -> callStatusListener?.let { FirebaseDatabase.getInstance().reference.child("calls").child(id).removeEventListener(it) } }
        finish()
    }

    override fun onDestroy() {
        cleanupAndFinish()
        super.onDestroy()
    }
}

@Composable
fun IncomingCallScreen(callerName: String, callerPhotoUrl: String?, onAccept: () -> Unit, onReject: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(iOSSystemBackgroundDark)) {
        // Subtle background gradient
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black, iOSBlue.copy(0.15f), Color.Black))))
        
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 120.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (callerPhotoUrl != null) {
                        AsyncImage(
                            model = callerPhotoUrl,
                            contentDescription = null,
                            modifier = Modifier.size(120.dp).clip(CircleShape).background(Color(0xFF1C1C1E)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, null, modifier = Modifier.size(60.dp), tint = Color.Gray)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(callerName, color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(" Audio...", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge)
            }
            
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 100.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(onClick = onReject, containerColor = iOSRed, contentColor = Color.White, shape = CircleShape, modifier = Modifier.size(75.dp)) { Icon(Icons.Rounded.CallEnd, null, modifier = Modifier.size(35.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Text("Recusar", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(onClick = onAccept, containerColor = iOSGreen, contentColor = Color.White, shape = CircleShape, modifier = Modifier.size(75.dp)) { Icon(Icons.Rounded.Call, null, modifier = Modifier.size(35.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Text("Atender", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
