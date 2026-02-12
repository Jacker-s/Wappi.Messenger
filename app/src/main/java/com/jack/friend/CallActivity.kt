package com.jack.friend

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.jack.friend.ui.theme.*
import kotlinx.coroutines.*
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class CallActivity : ComponentActivity() {
    private val database = FirebaseDatabase.getInstance().reference
    private var roomId: String = ""
    private var targetId: String = ""
    private var targetPhotoUrl: String? = null
    private var isOutgoing: Boolean = false
    private var isVideo: Boolean = false
    private val callStatusState = mutableStateOf("RINGING")
    private var webRTCManager: WebRTCManager? = null
    private var callStatusListener: ValueEventListener? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var isEnding = false
    
    private var localVideoView: SurfaceViewRenderer? = null
    private var remoteVideoView: SurfaceViewRenderer? = null
    private val eglBase = EglBase.create()

    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(1002)
        
        roomId = intent.getStringExtra("roomId") ?: ""
        targetId = intent.getStringExtra("targetId") ?: ""
        targetPhotoUrl = intent.getStringExtra("targetPhotoUrl")
        isOutgoing = intent.getBooleanExtra("isOutgoing", false)
        isVideo = intent.getBooleanExtra("isVideo", false)
        val isAcceptedFromNotification = intent.getBooleanExtra("isAcceptedFromNotification", false)

        if (roomId.isEmpty()) { finish(); return }

        // If accepted from notification, ensure status is CONNECTED in database immediately
        if (isAcceptedFromNotification) {
            database.child("calls").child(roomId).child("status").setValue("CONNECTED")
            callStatusState.value = "CONNECTED"
        }

        if (isVideo) {
            localVideoView = SurfaceViewRenderer(this).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(true)
            }
            remoteVideoView = SurfaceViewRenderer(this).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            }
            setSpeakerphoneOn(true)
        } else {
            setSpeakerphoneOn(false)
        }

        checkPermissions()

        setContent {
            FriendTheme {
                MetaCallScreen(
                    targetId = targetId,
                    targetPhotoUrl = targetPhotoUrl,
                    isOutgoing = isOutgoing,
                    isVideo = isVideo,
                    status = callStatusState.value,
                    localVideoView = localVideoView,
                    remoteVideoView = remoteVideoView,
                    onHangUp = { endCall() },
                    onMute = { muted -> toggleMute(muted) },
                    onSpeakerToggle = { speakerOn -> setSpeakerphoneOn(speakerOn) },
                    onCameraToggle = { toggleCamera() }
                )
            }
        }

        activityScope.launch {
            delay(1000)
            if (!isEnding) {
                try {
                    webRTCManager = WebRTCManager(
                        context = this@CallActivity,
                        roomId = roomId,
                        isCaller = isOutgoing,
                        isVideo = isVideo,
                        localVideoView = localVideoView,
                        remoteVideoView = remoteVideoView,
                        onLocalStream = { },
                        onRemoteStream = {
                            callStatusState.value = "CONNECTED"
                        }
                    )
                    if (isOutgoing) webRTCManager?.startCall() else webRTCManager?.answerCall()
                } catch (e: Exception) {
                    Log.e("CallActivity", "WebRTC Error: ${e.message}")
                }
            }
        }
        
        listenForCallStatus()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (isVideo) permissions.add(Manifest.permission.CAMERA)
        
        val toRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 101)
    }
    
    private fun toggleMute(isMuted: Boolean) {
        webRTCManager?.toggleMute(isMuted)
    }

    private fun setSpeakerphoneOn(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val speakerDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val earpieceDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            
            if (on && speakerDevice != null) {
                audioManager.setCommunicationDevice(speakerDevice)
            } else if (!on && earpieceDevice != null) {
                audioManager.setCommunicationDevice(earpieceDevice)
            } else {
                audioManager.clearCommunicationDevice()
            }
        }
    }

    private fun toggleCamera() {
        webRTCManager?.toggleVideo(isVideo)
    }

    private fun listenForCallStatus() {
        callStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isEnding) return
                val status = snapshot.child("status").getValue(String::class.java) ?: "RINGING"
                
                // Important: Always update local state to match database
                callStatusState.value = status
                
                if (status == "ENDED" || status == "REJECTED") {
                    cleanupAndFinish()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("calls").child(roomId).addValueEventListener(callStatusListener!!)
    }

    private fun endCall() {
        if (roomId.isNotEmpty()) {
            database.child("calls").child(roomId).child("status").setValue("ENDED")
        }
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        if (isEnding) return
        isEnding = true
        
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }

        activityScope.cancel()
        roomId.takeIf { it.isNotEmpty() }?.let { id ->
            callStatusListener?.let { database.child("calls").child(id).removeEventListener(it) }
        }
        webRTCManager?.onDestroy()
        webRTCManager = null
        
        localVideoView?.release()
        remoteVideoView?.release()
        eglBase.release()
        
        finish()
    }

    override fun onDestroy() {
        cleanupAndFinish()
        super.onDestroy()
    }
}

@Composable
fun MetaCallScreen(
    targetId: String, 
    targetPhotoUrl: String?,
    isOutgoing: Boolean, 
    isVideo: Boolean,
    status: String,
    localVideoView: SurfaceViewRenderer?,
    remoteVideoView: SurfaceViewRenderer?,
    onHangUp: () -> Unit, 
    onMute: (Boolean) -> Unit,
    onSpeakerToggle: (Boolean) -> Unit,
    onCameraToggle: () -> Unit
) {
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(isVideo) }
    var isCameraOff by remember { mutableStateOf(!isVideo) }
    
    Box(modifier = Modifier.fillMaxSize().background(MetaBlack)) {
        if (isVideo && status == "CONNECTED") {
            AndroidView(
                factory = { remoteVideoView!! },
                modifier = Modifier.fillMaxSize()
            )
            
            if (!isCameraOff) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 60.dp, end = 20.dp)
                        .size(120.dp, 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { localVideoView!! },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MetaBlack, MetaDarkSurface, MetaBlack))))
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                    if (targetPhotoUrl != null) {
                        AsyncImage(
                            model = targetPhotoUrl,
                            contentDescription = null,
                            modifier = Modifier.size(160.dp).clip(CircleShape).background(MetaDarkSurface.copy(0.5f)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(modifier = Modifier.size(160.dp).clip(CircleShape).background(MetaDarkSurface.copy(0.5f)))
                        Icon(Icons.Default.Person, null, modifier = Modifier.size(80.dp), tint = MetaGray4)
                    }
                }
                Spacer(Modifier.height(40.dp))
                Text(targetId, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                val statusText = when (status) {
                    "RINGING" -> if (isOutgoing) "Chamando..." else "Recebendo chamada..."
                    "CONNECTED" -> "Chamada em andamento"
                    "MUTED" -> "Microfone silenciado"
                    else -> "Conectando..."
                }
                Text(statusText, color = MetaGray4, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).padding(horizontal = 20.dp).fillMaxWidth(),
            shape = RoundedCornerShape(35.dp),
            color = MetaDarkSurface.copy(alpha = 0.8f)
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { 
                    isMuted = !isMuted
                    onMute(isMuted) 
                }, modifier = Modifier.size(50.dp).clip(CircleShape).background(if (isMuted) Color.White else Color.Transparent)) {
                    Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, null, tint = if (isMuted) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                }
                
                IconButton(onClick = { 
                    isSpeakerOn = !isSpeakerOn
                    onSpeakerToggle(isSpeakerOn)
                }, modifier = Modifier.size(50.dp).clip(CircleShape).background(if (isSpeakerOn) Color.White else Color.Transparent)) {
                    Icon(if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null, tint = if (isSpeakerOn) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                }

                if (isVideo) {
                    IconButton(onClick = { isCameraOff = !isCameraOff; onCameraToggle() }, modifier = Modifier.size(50.dp).clip(CircleShape).background(if (isCameraOff) Color.White else Color.Transparent)) {
                        Icon(if (isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam, null, tint = if (isCameraOff) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                    }
                }

                FloatingActionButton(onClick = onHangUp, containerColor = Color(0xFFFA3E3E), contentColor = Color.White, shape = CircleShape, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
