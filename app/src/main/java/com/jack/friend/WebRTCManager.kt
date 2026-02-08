package com.jack.friend

import android.content.Context
import android.util.Log
import com.google.firebase.database.*
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRTCManager(
    private val context: Context,
    private val roomId: String,
    private val isCaller: Boolean,
    private val onLocalStream: () -> Unit,
    private val onRemoteStream: () -> Unit
) {
    private val database = FirebaseDatabase.getInstance().reference.child("calls").child(roomId)
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    private val localCandidatesPath = if (isCaller) "callerCandidates" else "receiverCandidates"
    private val remoteCandidatesPath = if (isCaller) "receiverCandidates" else "callerCandidates"
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    fun startCall() {
        setupLocalStream()
        createPeerConnection()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        database.child("offer").setValue(mapOf("type" to sdp?.type?.canonicalForm(), "sdp" to sdp?.description))
                    }
                }, sdp)
            }
        }, constraints)
        listenForAnswer()
        listenForIceCandidates()
    }

    fun answerCall() {
        setupLocalStream()
        createPeerConnection()
        database.child("offer").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val type = snapshot.child("type").getValue(String::class.java)
                val sdp = snapshot.child("sdp").getValue(String::class.java)
                if (type != null && sdp != null) {
                    val sessionDescription = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
                    peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            drainIceCandidates()
                            val constraints = MediaConstraints().apply {
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                            }
                            peerConnection?.createAnswer(object : SimpleSdpObserver() {
                                override fun onCreateSuccess(answerDescription: SessionDescription?) {
                                    peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                                        override fun onSetSuccess() {
                                            database.child("answer").setValue(mapOf("type" to answerDescription?.type?.canonicalForm(), "sdp" to answerDescription?.description))
                                            database.child("status").setValue("ANSWERED")
                                        }
                                    }, answerDescription)
                                }
                            }, constraints)
                        }
                    }, sessionDescription)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        listenForIceCandidates()
    }

    private fun setupLocalStream() {
        val audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)
        onLocalStream()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    database.child(localCandidatesPath).push().setValue(mapOf("sdpMid" to it.sdpMid, "sdpMLineIndex" to it.sdpMLineIndex, "candidate" to it.sdp))
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) { if (transceiver?.receiver?.track()?.kind() == "audio") onRemoteStream() }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) { Log.d("WebRTC", "ICE: $state") }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
        localAudioTrack?.let { peerConnection?.addTrack(it) }
    }

    private fun listenForAnswer() {
        database.child("answer").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val type = snapshot.child("type").getValue(String::class.java)
                val sdp = snapshot.child("sdp").getValue(String::class.java)
                if (type != null && sdp != null) {
                    peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() { drainIceCandidates() }
                    }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForIceCandidates() {
        database.child(remoteCandidatesPath).addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                val candidate = s.child("candidate").getValue(String::class.java)
                val mid = s.child("sdpMid").getValue(String::class.java)
                val idx = s.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                if (candidate != null && mid != null) {
                    val iceCandidate = IceCandidate(mid, idx, candidate)
                    if (peerConnection?.remoteDescription != null) peerConnection?.addIceCandidate(iceCandidate) else pendingIceCandidates.add(iceCandidate)
                }
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun drainIceCandidates() { pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }; pendingIceCandidates.clear() }
    fun toggleMute(isMuted: Boolean) { localAudioTrack?.setEnabled(!isMuted) }
    fun onDestroy() { peerConnection?.close(); peerConnectionFactory?.dispose(); audioDeviceModule?.release() }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
