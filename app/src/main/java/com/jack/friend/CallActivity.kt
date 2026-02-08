package com.jack.friend

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.jack.friend.ui.theme.*

class CallActivity : ComponentActivity() {
    private val database = FirebaseDatabase.getInstance().reference
    private var roomId: String = ""
    private var targetId: String = ""
    private var isOutgoing: Boolean = false
    private val callStatusState = mutableStateOf("RINGING")
    private var webRTCManager: WebRTCManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        roomId = intent.getStringExtra("roomId") ?: ""
        targetId = intent.getStringExtra("targetId") ?: ""
        isOutgoing = intent.getBooleanExtra("isOutgoing", false)

        if (roomId.isEmpty()) { finish(); return }

        checkPermissions()

        try {
            webRTCManager = WebRTCManager(this, roomId, isOutgoing, {}, {
                callStatusState.value = "CONNECTED"
            })
            if (isOutgoing) webRTCManager?.startCall() else webRTCManager?.answerCall()
        } catch (e: Exception) {
            Log.e("CallActivity", "WebRTC Error: ${e.message}"); finish(); return
        }

        setContent {
            FriendTheme {
                SwiftCallScreen(targetId, isOutgoing, callStatusState.value, { endCall() }, { toggleMute() })
            }
        }
        listenForCallStatus()
    }

    private fun checkPermissions() {
        val permissions = listOf(Manifest.permission.RECORD_AUDIO)
        val toRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 101)
    }
    
    private fun toggleMute() {
        // Obtenha o estado atual do mudo antes de alternar
        val currentMuted = callStatusState.value == "MUTED" 
        val isNowMuted = webRTCManager?.toggleMute(!currentMuted) ?: false
        // O toggleMute do manager deve retornar o novo estado do mudo
    }

    private fun listenForCallStatus() {
        database.child("calls").child(roomId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: "RINGING"
                callStatusState.value = status
                if (status == "ENDED" || status == "REJECTED") finish()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun endCall() {
        database.child("calls").child(roomId).child("status").setValue("ENDED")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        webRTCManager?.onDestroy()
    }
}

@Composable
fun SwiftCallScreen(targetId: String, isOutgoing: Boolean, status: String, onHangUp: () -> Unit, onMute: () -> Unit) {
    var isMuted by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(modifier = Modifier.size(120.dp), shape = CircleShape, color = SystemGray4) { Icon(Icons.Default.Person, null, modifier = Modifier.padding(32.dp), tint = Color.White) }
            Spacer(Modifier.height(32.dp))
            Text(targetId, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(if (status == "RINGING") (if (isOutgoing) "chamando..." else "chamada recebida") else "em conversa", color = SystemGray, style = MaterialTheme.typography.bodyLarge)
        }
        Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 50.dp, start = 20.dp, end = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(35.dp), color = Color.White.copy(0.15f)) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { isMuted = !isMuted; onMute() }, modifier = Modifier.size(50.dp).clip(CircleShape).background(if (isMuted) Color.White else Color.Transparent)) {
                    Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, null, tint = if (isMuted) Color.Black else Color.White)
                }
                FloatingActionButton(onClick = onHangUp, containerColor = AppleRed, contentColor = Color.White, shape = CircleShape, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.size(50.dp))
            }
        }
    }
}
