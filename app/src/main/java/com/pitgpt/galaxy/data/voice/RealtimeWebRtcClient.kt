package com.pitgpt.galaxy.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Native WebRTC transport for an already-authorized, short-lived Realtime client secret.
 *
 * Microphone permission must be granted by the UI before connect() is called.
 * The WebRTC audio device module handles capture and playback; no raw audio is
 * copied through Kotlin or persisted by this class.
 */
class RealtimeWebRtcClient(
    context: Context,
    private val onEvent: (String) -> Unit = {},
    private val onStateChanged: (State) -> Unit = {}
) {
    enum class State { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val iceEverConnected = AtomicBoolean(false)
    @Volatile private var state = State.NEW
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var microphoneSource: AudioSource? = null
    private var microphoneTrack: AudioTrack? = null
    private var events: DataChannel? = null

    suspend fun connect(clientSecret: String, expiresAtEpochMillis: Long) {
        check(state == State.NEW) { "Realtime client can only connect once" }
        require(clientSecret.isNotBlank()) { "Client secret is missing" }
        check(expiresAtEpochMillis - System.currentTimeMillis() > MINIMUM_TOKEN_LIFETIME_MS) {
            "Realtime client secret is expired or too close to expiry"
        }
        check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }
        updateState(State.CONNECTING)
        try {
            initializeFactory()
            val iceComplete = CompletableDeferred<Unit>()
            val iceConnected = CompletableDeferred<Unit>()
            val dataChannelOpened = CompletableDeferred<Unit>()
            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(value: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(value: PeerConnection.IceConnectionState) {
                    when (value) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            iceEverConnected.set(true)
                            iceConnected.complete(Unit)
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            iceConnected.completeExceptionally(
                                IllegalStateException("Realtime ICE connection failed")
                            )
                            if (state == State.CONNECTED || iceEverConnected.get()) {
                                updateState(State.DISCONNECTED)
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            if (iceEverConnected.get()) updateState(State.DISCONNECTED)
                        }
                        else -> Unit
                    }
                }
                override fun onIceConnectionReceivingChange(value: Boolean) = Unit
                override fun onIceGatheringChange(value: PeerConnection.IceGatheringState) {
                    if (value == PeerConnection.IceGatheringState.COMPLETE && !iceComplete.isCompleted) {
                        iceComplete.complete(Unit)
                    }
                }
                override fun onIceCandidate(candidate: IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
                override fun onConnectionChange(value: PeerConnection.PeerConnectionState) {
                    if (value == PeerConnection.PeerConnectionState.FAILED) {
                        iceConnected.completeExceptionally(
                            IllegalStateException("Realtime peer connection failed")
                        )
                        if (state == State.CONNECTED || iceEverConnected.get()) {
                            updateState(State.DISCONNECTED)
                        }
                    } else if (value == PeerConnection.PeerConnectionState.CONNECTED) {
                        iceEverConnected.set(true)
                    }
                }
                override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
            }
            val configuration = PeerConnection.RTCConfiguration(emptyList())
            peer = requireNotNull(factory).createPeerConnection(configuration, observer)
                ?: error("Could not create WebRTC peer connection")

            microphoneSource = requireNotNull(factory).createAudioSource(MediaConstraints())
            microphoneTrack = requireNotNull(factory).createAudioTrack("galaxy-microphone", microphoneSource)
            requireNotNull(peer).addTrack(requireNotNull(microphoneTrack), listOf("galaxy-audio"))

            events = requireNotNull(peer).createDataChannel("oai-events", DataChannel.Init())
                ?: error("Could not create Realtime event channel")
            events?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    when (events?.state()) {
                        DataChannel.State.OPEN -> dataChannelOpened.complete(Unit)
                        DataChannel.State.CLOSED -> dataChannelOpened.completeExceptionally(
                            IllegalStateException("Realtime event channel closed before opening")
                        )
                        else -> Unit
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (!buffer.binary) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        onEvent(String(bytes, StandardCharsets.UTF_8))
                    }
                }
            })
            if (events?.state() == DataChannel.State.OPEN) dataChannelOpened.complete(Unit)

            val offer = createOffer(requireNotNull(peer))
            setLocalDescription(requireNotNull(peer), offer)
            withTimeout(ICE_GATHERING_TIMEOUT_MS) { iceComplete.await() }
            val localSdp = requireNotNull(peer?.localDescription?.description) {
                "WebRTC did not produce a local SDP offer"
            }
            val answerSdp = exchangeSdp(clientSecret, localSdp)
            setRemoteDescription(requireNotNull(peer), SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
            withTimeout(PEER_CONNECTION_TIMEOUT_MS) {
                iceConnected.await()
                dataChannelOpened.await()
            }
            check(state == State.CONNECTING) { "Realtime connection closed before becoming ready" }
            updateState(State.CONNECTED)
        } catch (failure: Exception) {
            closeResources()
            updateState(State.FAILED)
            throw failure
        }
    }

    fun sendEvent(json: String): Boolean {
        if (state != State.CONNECTED || closed.get()) return false
        val bytes = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
        return events?.send(DataChannel.Buffer(bytes, false)) == true
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneTrack?.setEnabled(enabled)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeResources()
        updateState(State.CLOSED)
    }

    private fun initializeFactory() {
        synchronized(FACTORY_LOCK) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
                )
                initialized = true
            }
        }
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(requireNotNull(audioDeviceModule))
            .createPeerConnectionFactory()
    }

    private suspend fun exchangeSdp(secret: String, offer: String): String = withContext(Dispatchers.IO) {
        val connection = URL(REALTIME_CALLS_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $secret")
            connection.setRequestProperty("Content-Type", "application/sdp")
            connection.setRequestProperty("Accept", "application/sdp")
            connection.outputStream.use { it.write(offer.toByteArray(StandardCharsets.UTF_8)) }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IllegalStateException("Realtime signaling failed (HTTP $statusCode)")
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Realtime signaling returned an empty SDP answer")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun createOffer(connection: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            connection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("Could not create WebRTC offer")
                    )
                }
                override fun onSetFailure(error: String) = Unit
            }, MediaConstraints())
        }

    private suspend fun setLocalDescription(connection: PeerConnection, description: SessionDescription) {
        suspendCancellableCoroutine<Unit> { continuation ->
            connection.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(value: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("Could not apply local WebRTC description")
                    )
                }
            }, description)
        }
    }

    private suspend fun setRemoteDescription(connection: PeerConnection, description: SessionDescription) {
        suspendCancellableCoroutine<Unit> { continuation ->
            connection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(value: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("Could not apply remote WebRTC description")
                    )
                }
            }, description)
        }
    }

    private fun updateState(next: State) {
        state = next
        onStateChanged(next)
    }

    private fun closeResources() {
        runCatching { events?.unregisterObserver() }
        runCatching { events?.close() }
        runCatching { microphoneTrack?.dispose() }
        runCatching { microphoneSource?.dispose() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioDeviceModule?.release() }
        events = null
        microphoneTrack = null
        microphoneSource = null
        peer = null
        factory = null
        audioDeviceModule = null
    }

    private companion object {
        const val REALTIME_CALLS_URL = "https://api.openai.com/v1/realtime/calls"
        const val HTTP_TIMEOUT_MS = 20_000
        const val ICE_GATHERING_TIMEOUT_MS = 20_000L
        const val PEER_CONNECTION_TIMEOUT_MS = 30_000L
        const val MINIMUM_TOKEN_LIFETIME_MS = 20_000L
        val FACTORY_LOCK = Any()
        @Volatile var initialized = false
    }
}
