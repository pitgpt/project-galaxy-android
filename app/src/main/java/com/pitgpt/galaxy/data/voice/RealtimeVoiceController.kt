package com.pitgpt.galaxy.data.voice

import android.content.Context
import com.pitgpt.galaxy.core.model.CharacterProfile
import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.repository.ConversationRepository
import com.pitgpt.galaxy.core.repository.RealtimeSessionProvider
import com.pitgpt.galaxy.core.usecase.CreateRealtimeSession
import com.pitgpt.galaxy.core.usecase.RealtimeSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

enum class RealtimeVoiceStatus {
    IDLE, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED
}

data class RealtimeVoiceUiState(
    val status: RealtimeVoiceStatus = RealtimeVoiceStatus.IDLE,
    val muted: Boolean = false,
    val message: String? = null
)

/**
 * Coordinates one authenticated Realtime session, its WebRTC transport and backend lease.
 * Audio itself stays inside WebRTC; only completed text transcripts are persisted locally.
 */
class RealtimeVoiceController(
    context: Context,
    private val sessionProviderFactory: () -> RealtimeSessionProvider,
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val onTranscript: (ConversationMessage) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(RealtimeVoiceUiState())
    val state: StateFlow<RealtimeVoiceUiState> = mutableState.asStateFlow()

    @Volatile private var activeCharacterId: String? = null
    @Volatile private var client: RealtimeWebRtcClient? = null
    @Volatile private var lease: RealtimeSessionLease? = null

    suspend fun connect(profile: CharacterProfile) {
        if (mutableState.value.status == RealtimeVoiceStatus.CONNECTING ||
            mutableState.value.status == RealtimeVoiceStatus.CONNECTED) return

        releaseResources()
        activeCharacterId = profile.id
        mutableState.value = RealtimeVoiceUiState(
            status = RealtimeVoiceStatus.CONNECTING,
            message = "Получаю защищённые данные голосовой сессии…"
        )

        var newLease: RealtimeSessionLease? = null
        var newClient: RealtimeWebRtcClient? = null
        try {
            val provider = sessionProviderFactory()
            val voiceId = profile.voiceId?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE_ID
            val credential = CreateRealtimeSession(provider)(
                characterId = profile.id,
                voiceId = voiceId,
                personaInstructions = profile.personaDefinition
            )
            newLease = RealtimeSessionLease(
                credential = credential,
                provider = provider,
                scope = scope,
                onLeaseLost = {
                    scope.launch {
                        releaseResources()
                        mutableState.value = RealtimeVoiceUiState(
                            status = RealtimeVoiceStatus.FAILED,
                            message = "Серверная сессия потеряна. Останови звонок и подключись снова."
                        )
                    }
                }
            )
            newClient = RealtimeWebRtcClient(
                context = appContext,
                onEvent = ::handleEvent,
                onStateChanged = { transportState ->
                    when (transportState) {
                        RealtimeWebRtcClient.State.CONNECTED ->
                            mutableState.value = mutableState.value.copy(
                                status = RealtimeVoiceStatus.CONNECTED,
                                message = "Голосовой канал подключён"
                            )
                        RealtimeWebRtcClient.State.DISCONNECTED ->
                            mutableState.value = mutableState.value.copy(
                                status = RealtimeVoiceStatus.DISCONNECTED,
                                message = "Связь прервана. Можно попробовать подключиться снова."
                            )
                        RealtimeWebRtcClient.State.FAILED ->
                            mutableState.value = mutableState.value.copy(
                                status = RealtimeVoiceStatus.FAILED,
                                message = "Не удалось установить голосовое соединение."
                            )
                        RealtimeWebRtcClient.State.CLOSED ->
                            mutableState.value = mutableState.value.copy(
                                status = RealtimeVoiceStatus.CLOSED,
                                message = "Голосовой канал закрыт"
                            )
                        else -> Unit
                    }
                }
            )
            lease = newLease
            client = newClient
            newClient.connect(credential.clientSecret, credential.expiresAtEpochMillis)
            newLease.start()
            mutableState.value = RealtimeVoiceUiState(
                status = RealtimeVoiceStatus.CONNECTED,
                message = "Можно говорить. Я слушаю."
            )
        } catch (cancelled: CancellationException) {
            newClient?.close()
            runCatching { newLease?.close() }
            if (client === newClient) client = null
            if (lease === newLease) lease = null
            mutableState.value = RealtimeVoiceUiState(status = RealtimeVoiceStatus.IDLE)
            throw cancelled
        } catch (failure: Exception) {
            newClient?.close()
            runCatching { newLease?.close() }
            if (client === newClient) client = null
            if (lease === newLease) lease = null
            mutableState.value = RealtimeVoiceUiState(
                status = RealtimeVoiceStatus.FAILED,
                message = failure.message?.takeIf { it.isNotBlank() }
                    ?: "Не удалось подключить голосовой режим."
            )
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        client?.setMicrophoneEnabled(enabled)
        mutableState.value = mutableState.value.copy(muted = !enabled)
    }

    fun disconnect() {
        scope.launch {
            releaseResources()
            activeCharacterId = null
            mutableState.value = RealtimeVoiceUiState(
                status = RealtimeVoiceStatus.IDLE,
                message = "Голосовой режим остановлен"
            )
        }
    }

    private fun handleEvent(raw: String) {
        val event = try { JSONObject(raw) } catch (_: Exception) { return }
        when (event.optString("type")) {
            "conversation.item.input_audio_transcription.completed" ->
                persistTranscript(MessageRole.USER, event.optString("transcript"))
            "response.output_audio_transcript.done",
            "response.audio_transcript.done" ->
                persistTranscript(MessageRole.CHARACTER, event.optString("transcript"))
            "error" -> mutableState.value = mutableState.value.copy(
                message = "Realtime сообщил об ошибке. Проверь соединение и попробуй снова."
            )
        }
    }

    private fun persistTranscript(role: MessageRole, text: String) {
        val normalized = text.trim()
        val characterId = activeCharacterId ?: return
        if (normalized.isEmpty()) return
        scope.launch {
            val message = ConversationMessage(
                id = UUID.randomUUID().toString(),
                characterId = characterId,
                role = role,
                content = normalized,
                createdAtEpochMillis = System.currentTimeMillis()
            )
            try {
                conversations.append(message)
                onTranscript(message)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    message = "Не удалось сохранить расшифровку в историю."
                )
            }
        }
    }

    private suspend fun releaseResources() {
        val oldClient = client
        val oldLease = lease
        client = null
        lease = null
        oldClient?.close()
        try {
            oldLease?.close()
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                message = "Соединение закрыто, но сервер не подтвердил освобождение сессии."
            )
        }
    }

    private companion object {
        const val DEFAULT_VOICE_ID = "voice.feminine_warm"
    }
}
