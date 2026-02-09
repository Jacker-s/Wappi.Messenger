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
        // Apenas áudio é necessário agora
        val permissions = listOf(Manifest.permission.RECORD_AUDIO)
        val toRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 101)
    }
    
    private fun toggleMute() {
        val currentStatus = callStatusState.value
        val isCurrentlyMuted = currentStatus == "MUTED"
        val newMutedState = !isCurrentlyMuted
        
        webRTCManager?.toggleMute(newMutedState)
        
        // Opcional: Atualizar status no Firebase se quiser que o outro lado saiba que você silenciou
        // database.child("calls").child(roomId).child("status").setValue(if (newMutedState) "MUTED" else "CONNECTED")
    }

    private fun listenForCallStatus() {
        database.child("calls").child(roomId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: "RINGING"
                if (status != "MUTED") { // Não sobrescrever o estado local de silenciado se vier do Firebase
                    callStatusState.value = status
                }
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
    
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Avatar Placeholder
            Surface(
                modifier = Modifier.size(140.dp),
                shape = CircleShape,
                color = Color.DarkGray
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.padding(35.dp),
                    tint = Color.LightGray
                )
            }
            
            Spacer(Modifier.height(40.dp))
            
            Text(
                text = targetId,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(Modifier.height(8.dp))
            
            val statusText = when (status) {
                "RINGING" -> if (isOutgoing) "Chamando..." else "Chamada de Voz..."
                "CONNECTED" -> "Chamada em andamento"
                "MUTED" -> "Microfone silenciado"
                else -> "Conectando..."
            }
            
            Text(
                text = statusText,
                color = Color.Gray,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Painel de Controle Inferior
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp, start = 30.dp, end = 30.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(40.dp),
            color = Color.White.copy(alpha = 0.1f)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botão Mudo
                IconButton(
                    onClick = { 
                        isMuted = !isMuted
                        onMute()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (isMuted) Color.White else Color.Transparent)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = if (isMuted) Color.Black else Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Botão Encerrar
                FloatingActionButton(
                    onClick = onHangUp,
                    containerColor = Color(0xFFFF3B30), // Apple Red
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Botão Viva-voz (Placeholder por enquanto)
                IconButton(
                    onClick = { /* Implementar toggle de áudio se necessário */ },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Speaker",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
