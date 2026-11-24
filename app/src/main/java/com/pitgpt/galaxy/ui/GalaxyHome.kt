package com.pitgpt.galaxy.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.BuildConfig
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.model.CharacterProfile
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.usecase.SendMessage
import com.pitgpt.galaxy.data.remote.GalaxyApiException
import com.pitgpt.galaxy.data.remote.LilaHistoryMessage
import com.pitgpt.galaxy.data.remote.LilaImageHistoryJob
import com.pitgpt.galaxy.data.remote.LilaTurnStatus
import com.pitgpt.galaxy.data.remote.stableLilaConversationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

private val Night = Color(0xFF080B18)
private val Muted = Color(0xFF9AA8C5)
private val Ice = Color(0xFFB8D9FF)
private const val CHARACTER_ID = "galaxy.default"
internal const val PENDING_LILA_MESSAGE_PREFIX = "lila-pending-"

internal data class ConversationTimelineEntry(
    val key: String,
    val createdAtEpochMillis: Long,
    val message: ConversationMessage? = null,
    val image: StoredGeneratedImage? = null,
    val imageJob: LilaImageHistoryJob? = null
)

internal fun buildConversationTimeline(
    messages: List<ConversationMessage>,
    images: List<StoredGeneratedImage>,
    imageJobs: List<LilaImageHistoryJob>,
    requestIdsByJobId: Map<String, String>
): List<ConversationTimelineEntry> {
    val storedRequestIds = images.mapTo(mutableSetOf<String>()) { it.requestId }
    return buildList {
        messages.forEach { message ->
            add(
                ConversationTimelineEntry(
                    key = "message:" + message.id,
                    createdAtEpochMillis = message.createdAtEpochMillis,
                    message = message
                )
            )
        }
        images.forEach { image ->
            add(
                ConversationTimelineEntry(
                    key = "image:" + image.assetId,
                    createdAtEpochMillis = image.createdAtEpochMillis,
                    image = image
                )
            )
        }
        imageJobs.forEach { job ->
            val requestId = requestIdsByJobId[job.jobId] ?: job.jobId
            if (job.state != "completed" || requestId !in storedRequestIds) {
                add(
                    ConversationTimelineEntry(
                        key = "image-job:" + job.jobId,
                        createdAtEpochMillis = job.createdAtEpochMillis,
                        imageJob = job
                    )
                )
            }
        }
    }.sortedWith(compareBy<ConversationTimelineEntry> { it.createdAtEpochMillis }.thenBy { it.key })
}

internal fun shouldPollCoreImageJobState(state: String): Boolean =
    state in setOf(
        "submitting",
        "queued",
        "running",
        "provider_completed",
        "result_pending"
    )

internal fun coreImageJobStatusLabel(state: String): String = when (state) {
    "prepared" -> "ПОДГОТОВКА"
    "submitting" -> "ОТПРАВКА В COMFYUI"
    "submission_unknown" -> "ОЖИДАЕТ ПОДТВЕРЖДЕНИЯ"
    "queued" -> "В ОЧЕРЕДИ"
    "running" -> "ВЫПОЛНЯЕТСЯ"
    "provider_completed", "result_pending" -> "СОХРАНЕНИЕ РЕЗУЛЬТАТА"
    "completed" -> "ГОТОВО · РЕЗУЛЬТАТ В CORE"
    "failed" -> "ОШИБКА ГЕНЕРАЦИИ"
    "cancelled" -> "ОТМЕНЕНО"
    else -> "СТАТУС НЕИЗВЕСТЕН"
}

@Composable
fun GalaxyHome() {
    val context = LocalContext.current
    val app = context.applicationContext as GalaxyApplication
    var characterName by remember { mutableStateOf("Галактика") }
    var activeCharacterId by remember { mutableStateOf(CHARACTER_ID) }
    var characters by remember { mutableStateOf<List<CharacterProfile>>(emptyList()) }
    var initialized by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var showChat by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }
    var showComfyUiSettings by remember { mutableStateOf(false) }
    var showCharacters by remember { mutableStateOf(false) }
    var showCreateCharacter by remember { mutableStateOf(false) }
    var showEditCharacter by remember { mutableStateOf(false) }
    var editorProfile by remember { mutableStateOf<CharacterProfile?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<CharacterProfile?>(null) }
    var conversationOwnerKey by remember { mutableStateOf<String?>(null) }
    var activeModelLabel by remember { mutableStateOf<String?>(null) }
    var historySyncWarning by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ConversationMessage>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadKey) {
        isLoading = true
        loadError = null
        try {
            app.initialize()
            val loadedCharacters = app.characters.getAll()
            val preferredId = context.getSharedPreferences("galaxy_preferences", 0)
                .getString("active_character_id", activeCharacterId) ?: CHARACTER_ID
            val activeProfile = loadedCharacters.firstOrNull { it.id == preferredId }
                ?: loadedCharacters.firstOrNull { it.id == CHARACTER_ID }
                ?: throw IllegalStateException("Built-in character is missing")
            val ownerId = app.auth?.currentUserId()
            conversationOwnerKey = ownerId?.let(::ownerPartitionKey)
            val loadedMessages = app.conversations.getForCharacter(
                conversationStorageKey(conversationOwnerKey, activeProfile.id)
            )
            characters = loadedCharacters
            activeCharacterId = activeProfile.id
            context.getSharedPreferences("galaxy_preferences", 0).edit()
                .putString("active_character_id", activeProfile.id).apply()
            profile = activeProfile
            characterName = activeProfile.displayName
            messages.clear()
            messages.addAll(loadedMessages)
            initialized = true
        } catch (error: Exception) {
            loadError = "Не удалось загрузить данные Galaxy. Проверь хранилище и попробуй ещё раз."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(showChat, activeCharacterId, conversationOwnerKey, loadError) {
        if (!showChat || loadError != null || !app.lilaTurnProvider.isConfigured) {
            historySyncWarning = null
            return@LaunchedEffect
        }
        val ownerKey = conversationOwnerKey
        if (ownerKey == null) {
            activeModelLabel = null
            historySyncWarning = "Войди в аккаунт Galaxy, чтобы синхронизировать историю Лилы."
            return@LaunchedEffect
        }

        val conversationId = getOrCreateLilaConversationId(context, ownerKey, activeCharacterId)
        val activeModelPreferenceKey = "lila_active_model_$conversationId"
        val preferences = context.getSharedPreferences("galaxy_preferences", 0)
        activeModelLabel = preferences.getString(activeModelPreferenceKey, null)
        val storageCharacterId = conversationStorageKey(conversationOwnerKey, activeCharacterId)
        historySyncWarning = null
        isLoading = true
        try {
            val pendingLocalMessages = app.conversations.getForCharacter(storageCharacterId)
                .filter { it.id.startsWith(PENDING_LILA_MESSAGE_PREFIX) }
                .takeLast(5)
            pendingLocalMessages.forEach { pendingMessage ->
                val idempotencyKey = pendingMessage.id.removePrefix(PENDING_LILA_MESSAGE_PREFIX)
                val reconciliation = try {
                    app.lilaTurnProvider.getTurnStatus(conversationId, idempotencyKey)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (reconciliation?.status == LilaTurnStatus.COMPLETED) {
                    val recovered = reconciliation.result ?: return@forEach
                    val userMessage = pendingMessage.copy(id = UUID.randomUUID().toString())
                    val assistantMessage = ConversationMessage(
                        id = UUID.randomUUID().toString(),
                        characterId = storageCharacterId,
                        role = MessageRole.CHARACTER,
                        content = recovered.text,
                        createdAtEpochMillis = pendingMessage.createdAtEpochMillis + 1
                    )
                    try {
                        app.conversations.commitPendingTurn(
                            pendingMessage.id,
                            userMessage,
                            assistantMessage
                        )
                        clearPendingLilaTurnKey(
                            context,
                            pendingLilaTurnPreferenceKey(
                                ownerKey,
                                conversationId,
                                pendingMessage.content
                            )
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        historySyncWarning =
                            "Ответ Лилы восстановлен на сервере, но локальная копия пока не обновлена."
                    }
                }
            }

            val remoteHistory = app.lilaTurnProvider.loadAllHistory(conversationId)
            val latestRemoteModel = remoteHistory.lastOrNull { it.role == "assistant" }?.displayModelLabel
            // The current conversation selection can change on another client before
            // the next assistant turn. Restore it independently from the last-used model.
            val selectedModelLabel = try {
                val selection = app.lilaProviderCatalog.getConversationModel(conversationId)
                if (selection.selected) "${selection.providerId} · ${selection.modelId}" else null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val restoredModelLabel = selectedModelLabel ?: latestRemoteModel
            if (restoredModelLabel != null) {
                activeModelLabel = restoredModelLabel
                preferences.edit().putString(activeModelPreferenceKey, restoredModelLabel).apply()
            }
            if (remoteHistory.isNotEmpty()) {
                val localHistory = app.conversations.getForCharacter(storageCharacterId)
                val mergedHistory = mergeRemoteHistory(
                    characterId = storageCharacterId,
                    conversationId = conversationId,
                    local = localHistory,
                    remote = remoteHistory
                )
                app.conversations.replaceForCharacter(storageCharacterId, mergedHistory)
                messages.clear()
                messages.addAll(mergedHistory)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            historySyncWarning =
                "Не удалось синхронизировать историю с Lila Core. Локальная переписка сохранена."
        } finally {
            isLoading = false
        }
    }

    if (showEditCharacter) {
        CharacterEditorScreen(
            initialProfile = editorProfile,
            title = "РЕДАКТИРОВАТЬ ПЕРСОНАЖА",
            saveLabel = "СОХРАНИТЬ ИЗМЕНЕНИЯ",
            errorMessage = editorError,
            onBack = { showEditCharacter = false; editorProfile = null },
            onSave = { updated ->
                scope.launch {
                    try {
                        app.characters.save(updated)
                        characters = app.characters.getAll()
                        if (activeCharacterId == updated.id) {
                            profile = updated
                            characterName = updated.displayName
                        }
                        editorError = null
                        showEditCharacter = false
                        editorProfile = null
                    } catch (error: Exception) {
                        editorError = "Не удалось сохранить изменения персонажа."
                    }
                }
            }
        )
    } else if (showCreateCharacter) {
        CharacterEditorScreen(
            initialProfile = null,
            title = "НОВЫЙ ПЕРСОНАЖ",
            saveLabel = "СОЗДАТЬ ПЕРСОНАЖА",
            errorMessage = editorError,
            onBack = { showCreateCharacter = false },
            onSave = { draft ->
                scope.launch {
                    try {
                        val created = draft.copy(id = UUID.randomUUID().toString(), isBuiltIn = false)
                        app.characters.save(created)
                        characters = app.characters.getAll()
                        activeCharacterId = created.id
                        context.getSharedPreferences("galaxy_preferences", 0).edit()
                            .putString("active_character_id", created.id).apply()
                        profile = created
                        characterName = created.displayName
                        messages.clear()
                        activeModelLabel = null
                        loadError = null
                        showCreateCharacter = false
                    } catch (error: Exception) {
                        editorError = "Не удалось создать персонажа. Проверь хранилище и повтори попытку."
                    }
                }
            }
        )
    } else if (showCharacters) {
        CharacterListScreen(
            characters = characters,
            activeCharacterId = activeCharacterId,
            onBack = { showCharacters = false },
            onCreate = { editorError = null; showCreateCharacter = true },
            onEdit = { selected -> editorProfile = selected; editorError = null; showEditCharacter = true },
            onDelete = { selected ->
                scope.launch {
                    try {
                        app.characters.delete(selected.id)
                        characters = app.characters.getAll()
                        if (activeCharacterId == selected.id) {
                            val fallback = app.characters.getById(CHARACTER_ID)
                                ?: throw IllegalStateException("Built-in character is missing")
                            activeCharacterId = fallback.id
                            context.getSharedPreferences("galaxy_preferences", 0).edit()
                                .putString("active_character_id", fallback.id).apply()
                            profile = fallback
                            characterName = fallback.displayName
                            messages.clear()
                            activeModelLabel = null
                            messages.addAll(
                                app.conversations.getForCharacter(
                                    conversationStorageKey(conversationOwnerKey, fallback.id)
                                )
                            )
                        }
                    } catch (error: Exception) {
                        loadError = "Не удалось удалить персонажа. Данные не были изменены."
                    }
                }
            },
            onSelect = { selected ->
                scope.launch {
                    isLoading = true
                    loadError = null
                    try {
                        val selectedProfile = app.characters.getById(selected.id)
                            ?: throw IllegalStateException("Character not found")
                        val selectedMessages = app.conversations.getForCharacter(
                            conversationStorageKey(conversationOwnerKey, selectedProfile.id)
                        )
                        activeCharacterId = selectedProfile.id
                        context.getSharedPreferences("galaxy_preferences", 0).edit()
                            .putString("active_character_id", selectedProfile.id).apply()
                        profile = selectedProfile
                        characterName = selectedProfile.displayName
                        messages.clear()
                        messages.addAll(selectedMessages)
                        activeModelLabel = null
                        showCharacters = false
                    } catch (error: Exception) {
                        loadError = "Не удалось переключить персонажа. Попробуй ещё раз."
                    } finally {
                        isLoading = false
                    }
                }
            }
        )
    } else if (showProfile) {
        CharacterProfileScreen(
            profile = profile,
            onBack = { showProfile = false },
            onSave = { updated ->
                scope.launch {
                    try {
                        app.characters.save(updated)
                        characters = app.characters.getAll()
                        profile = updated
                        characterName = updated.displayName
                        showProfile = false
                    } catch (error: Exception) {
                        loadError = "Не удалось сохранить профиль. Изменения не применены."
                    }
                }
            }
        )
    } else if (showProviders) {
        val providerConversationId = conversationOwnerKey?.let { ownerKey ->
            getOrCreateLilaConversationId(context, ownerKey, activeCharacterId)
        }
        LilaProviderSettingsScreen(
            catalog = app.lilaProviderCatalog,
            conversationId = providerConversationId,
            onBack = { showProviders = false },
            onModelSelected = { label ->
                activeModelLabel = label
                providerConversationId?.let { conversationId ->
                    context.getSharedPreferences("galaxy_preferences", 0).edit()
                        .putString("lila_active_model_$conversationId", label)
                        .apply()
                }
            }
        )
    } else if (showComfyUiSettings) {
        ComfyUiSettingsScreen(app = app, onBack = { showComfyUiSettings = false })
    } else if (showChat) {
        val conversationId = conversationOwnerKey?.let { ownerKey ->
            getOrCreateLilaConversationId(context, ownerKey, activeCharacterId)
        } ?: conversationStorageKey(conversationOwnerKey, activeCharacterId)
        ConversationScreen(
            app = app,
            conversationId = conversationId,
            characterId = activeCharacterId,
            characterName = characterName,
            messages = messages,
            activeModelLabel = activeModelLabel,
            aiConfigured = app.lilaTurnProvider.isConfigured,
            remoteImageHistoryEnabled = conversationOwnerKey != null && app.lilaTurnProvider.isConfigured,
            isLoading = isLoading,
            loadError = loadError,
            historySyncWarning = historySyncWarning,
            onRetryLoad = { reloadKey += 1 },
            onBack = { showChat = false },
            onSend = { text ->
                val storageCharacterId = conversationStorageKey(conversationOwnerKey, activeCharacterId)
                if (app.lilaTurnProvider.isConfigured) {
                    val ownerKey = conversationOwnerKey
                        ?: throw GalaxyApiException(401, "Войди в аккаунт Galaxy, чтобы отправить сообщение Лиле.")
                    val conversationId = getOrCreateLilaConversationId(
                        context,
                        ownerKey,
                        activeCharacterId
                    )
                    val pendingTurnKey = getOrCreatePendingLilaTurnKey(context, ownerKey, conversationId, text)
                    val pendingMessageId = pendingLilaMessageId(pendingTurnKey.value)
                    val existingPending = messages.firstOrNull { it.id == pendingMessageId }
                    val createdAt = existingPending?.createdAtEpochMillis ?: System.currentTimeMillis()
                    val pendingUserMessage = ConversationMessage(
                        id = pendingMessageId,
                        characterId = storageCharacterId,
                        role = MessageRole.USER,
                        content = text.trim(),
                        createdAtEpochMillis = createdAt
                    )
                    // Persist the user's text before any network I/O. If the process dies
                    // while the provider request is in flight, the local conversation still
                    // contains the exact message and the same idempotency key can be reused.
                    app.conversations.append(pendingUserMessage)
                    val pendingIndex = messages.indexOfFirst { it.id == pendingMessageId }
                    if (pendingIndex >= 0) messages[pendingIndex] = pendingUserMessage
                    else messages.add(pendingUserMessage)

                    val response = app.lilaTurnProvider.sendTurn(conversationId, text, pendingTurnKey.value)
                    val userMessage = ConversationMessage(
                        id = UUID.randomUUID().toString(),
                        characterId = storageCharacterId,
                        role = MessageRole.USER,
                        content = text.trim(),
                        createdAtEpochMillis = createdAt
                    )
                    val assistantMessage = ConversationMessage(
                        id = UUID.randomUUID().toString(),
                        characterId = storageCharacterId,
                        role = MessageRole.CHARACTER,
                        content = response.text,
                        createdAtEpochMillis = createdAt + 1
                    )
                    try {
                        app.conversations.commitPendingTurn(
                            pendingMessageId,
                            userMessage,
                            assistantMessage
                        )
                    } catch (error: Exception) {
                        throw GalaxyApiException(
                            null,
                            "Ответ Лилы получен, но локальная копия не завершена. Повтори отправку того же сообщения: Core вернёт сохранённый ответ без повторного вызова модели."
                        )
                    }
                    clearPendingLilaTurnKey(context, pendingTurnKey.preferenceKey)
                    messages.removeAll { it.id == pendingMessageId }
                    messages.add(userMessage)
                    messages.add(assistantMessage)
                    val fallbackNotice = lilaFallbackNoticeText(
                        fallbackUsed = response.fallbackUsed,
                        providerId = response.providerId,
                        modelId = response.modelId,
                        fallbackFromProviderId = response.fallbackFromProviderId,
                        fallbackFromModelId = response.fallbackFromModelId,
                        fallbackReason = response.fallbackReason
                    )
                    if (fallbackNotice != null) {
                        val noticeMessage = ConversationMessage(
                            id = UUID.randomUUID().toString(),
                            characterId = storageCharacterId,
                            role = MessageRole.SYSTEM,
                            content = fallbackNotice,
                            createdAtEpochMillis = createdAt + 2
                        )
                        messages.add(noticeMessage)
                        try {
                            app.conversations.append(noticeMessage)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            historySyncWarning =
                                "Ответ Лилы сохранён, но уведомление о резервной модели будет восстановлено при синхронизации."
                        }
                    }
                    activeModelLabel = response.displayModel.takeIf {
                        response.providerId != null || response.modelId != null
                    }
                    activeModelLabel?.let { label ->
                        context.getSharedPreferences("galaxy_preferences", 0).edit()
                            .putString("lila_active_model_$conversationId", label)
                            .apply()
                    }
                } else {
                    val message = SendMessage(app.conversations).invoke(storageCharacterId, text)
                    messages.add(message)
                    activeModelLabel = null
                }
            }
        )
    } else {
        HomeScreen(
            characterName = if (initialized) characterName else null,
            avatarId = profile?.avatarId,
            avatarImageUri = profile?.avatarImageUri,
            isLoading = isLoading,
            aiConfigured = app.lilaTurnProvider.isConfigured,
            loadError = loadError,
            onRetry = { reloadKey += 1 },
            onOpenChat = { showChat = true },
            onOpenProfile = { showProfile = true },
            onOpenCharacters = { showCharacters = true },
            onOpenProviders = { showProviders = true },
            onOpenComfyUi = { showComfyUiSettings = true }
        )
    }
}

@Composable
private fun HomeScreen(
    characterName: String?,
    avatarId: String?,
    avatarImageUri: String?,
    isLoading: Boolean,
    aiConfigured: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenComfyUi: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A35), Night, Color(0xFF100D25)))
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PROJECT", color = Muted, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    Text("GALAXY", color = Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0x2227D7A0))
                            .border(1.dp, Color(0x5546D9AC), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            if (aiConfigured) "●  BACKEND CONFIGURED" else "●  LOCAL PREVIEW",
                            color = Color(0xFF75E2BD),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                        color = Color(0xFF91A5CC), fontSize = 9.sp)
                    Text(BuildConfig.GIT_SHA, color = Color(0xFF657AAB), fontSize = 8.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(238.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(230.dp).border(1.dp, Color(0x447C8DDB), CircleShape)
                    .padding(13.dp).border(1.dp, Color(0x337FCBFF), CircleShape))
                Box(Modifier.size(174.dp).background(
                    Brush.radialGradient(listOf(Color(0xFF7D83E8), Color(0xFF394A91),
                        Color(0xFF172448), Color.Transparent)), CircleShape))
                AvatarPortrait(avatarId = avatarId, modifier = Modifier.size(112.dp), imageUri = avatarImageUri)
            }
            Spacer(Modifier.height(34.dp))
            Text("Я рядом.", color = Color.White, fontSize = 34.sp,
                fontWeight = FontWeight.Light)
            Spacer(Modifier.height(10.dp))
            Text(
                characterName?.let { "$it • Твоё личное пространство" }
                    ?: "Твоё личное пространство для мыслей, идей и разговоров.",
                color = Muted, fontSize = 15.sp, lineHeight = 23.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Color(0xAA18213B))
                    .border(1.dp, Color(0x334F6598), RoundedCornerShape(22.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatusLine("Приложение", "Базовая оболочка работает", Color(0xFF65DDB2))
                StatusLine("Персонаж", if (characterName != null) "Профиль сохранён локально"
                    else if (isLoading) "Загрузка профиля…" else "Профиль недоступен",
                    if (loadError == null) Color(0xFF65DDB2) else Color(0xFFFF9B9B))
                StatusLine("Диалоги", if (loadError == null) "Локальная переписка доступна"
                    else "Не удалось загрузить историю",
                    if (loadError == null) Color(0xFF65DDB2) else Color(0xFFFF9B9B))
                if (loadError != null) {
                    Text(loadError, color = Color(0xFFFF9B9B), fontSize = 11.sp)
                    TextButton(onClick = onRetry) { Text("ПОВТОРИТЬ", color = Ice) }
                }
                StatusLine(
                    "Lila Core",
                    if (aiConfigured) "Backend настроен" else "Локальный режим; backend не настроен",
                    if (aiConfigured) Color(0xFF65DDB2) else Color(0xFFE9BD70)
                )
                StatusLine("Голос", "После стабильного чата и изображений", Color(0xFFE9BD70))
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onOpenProfile,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28375D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("ПРОФИЛЬ ПЕРСОНАЖА", fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenCharacters,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28375D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("ПЕРСОНАЖИ И ПРОФИЛИ", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenProviders,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28375D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("ПРОВАЙДЕРЫ И МОДЕЛИ", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenComfyUi,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28375D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("COMFYUI · ГЕНЕРАЦИЯ ИЗОБРАЖЕНИЙ", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6578D8)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("ОТКРЫТЬ ДИАЛОГ", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("MADE FOR ONE UNIVERSE", color = Color(0x667F91B8),
                fontSize = 9.sp, letterSpacing = 2.5.sp)
        }
    }
}

@Composable
private fun ConversationScreen(
    app: GalaxyApplication,
    conversationId: String,
    characterId: String,
    characterName: String,
    messages: List<ConversationMessage>,
    activeModelLabel: String?,
    aiConfigured: Boolean,
    remoteImageHistoryEnabled: Boolean,
    isLoading: Boolean,
    loadError: String?,
    historySyncWarning: String?,
    onRetryLoad: () -> Unit,
    onBack: () -> Unit,
    onSend: suspend (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var showImageGeneration by remember { mutableStateOf(false) }
    var imageGenerationSubmitting by remember { mutableStateOf(false) }
    var generatedImages by remember(conversationId) {
        mutableStateOf<List<StoredGeneratedImage>>(emptyList())
    }
    var imageHistoryRefreshKey by remember(conversationId) { mutableStateOf(0) }
    var imageHistoryLoadError by remember(conversationId) { mutableStateOf(false) }
    var imageHistorySyncWarning by remember(conversationId) { mutableStateOf<String?>(null) }
    var imageHistoryReady by remember(conversationId) { mutableStateOf(false) }
    var imageJobs by remember(conversationId) { mutableStateOf<List<LilaImageHistoryJob>>(emptyList()) }
    var imageJobRequestIds by remember(conversationId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val sendScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(app, conversationId, imageHistoryRefreshKey, remoteImageHistoryEnabled, characterId) {
        var shouldPoll = true
        while (shouldPoll) {
            try {
                if (remoteImageHistoryEnabled) {
                    val coreHistory = app.lilaCoreImageGenerationProvider.history(conversationId)
                    val sync = syncCoreImageHistory(
                        provider = app.lilaCoreImageGenerationProvider,
                        conversationId = conversationId,
                        characterId = characterId,
                        requestStore = app.imageGenerationRequests,
                        jobStore = app.comfyUiJobStore,
                        images = app.generatedImages,
                        history = coreHistory
                    )
                    imageJobs = coreHistory
                    imageJobRequestIds = try {
                        app.comfyUiJobStore.all().associate { it.promptId to it.requestId }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    generatedImages = app.generatedImages.listForConversation(conversationId)
                    imageHistoryReady = true
                    imageHistorySyncWarning = coreImageHistorySyncWarning(sync)
                    imageHistoryLoadError = false
                    shouldPoll = coreHistory.any { shouldPollCoreImageJobState(it.state) }
                } else {
                    generatedImages = app.generatedImages.listForConversation(conversationId)
                    imageJobs = emptyList()
                    imageJobRequestIds = emptyMap()
                    imageHistorySyncWarning = null
                    imageHistoryLoadError = false
                    imageHistoryReady = true
                    shouldPoll = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                imageHistoryLoadError = true
                imageHistoryReady = true
                shouldPoll = false
            }
            if (shouldPoll) delay(4_000)
        }
    }

    val timeline = buildConversationTimeline(
        messages = messages,
        images = generatedImages,
        imageJobs = imageJobs,
        requestIdsByJobId = imageJobRequestIds
    )

    fun submitDraft() {
        val value = draft.trim()
        if (value.isEmpty() || isSending || isLoading) return
        sendScope.launch {
            isSending = true
            sendError = null
            try {
                onSend(value)
                draft = ""
            } catch (error: Exception) {
                sendError = (error as? GalaxyApiException)?.message
                    ?: "Не удалось завершить отправку. Текст остался в поле, попробуй ещё раз."
            } finally {
                isSending = false
            }
        }
    }
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.lastIndex)
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A35), Night, Color(0xFF100D25)))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, enabled = !imageGenerationNavigationLocked(imageGenerationSubmitting)) { Text("‹  НАЗАД", color = Ice) }
                TextButton(
                    onClick = { showImageGeneration = !showImageGeneration },
                    enabled = !imageGenerationNavigationLocked(imageGenerationSubmitting)
                ) { Text(if (showImageGeneration) "СКРЫТЬ ОБРАЗ" else "ОБРАЗ", color = Ice, fontSize = 9.sp) }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(characterName, color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        activeModelLabel?.let { "LILA · $it" }
                            ?: if (aiConfigured) "LILA CORE · BACKEND НАСТРОЕН"
                            else "ЛОКАЛЬНЫЙ ДИАЛОГ · AI НЕ ПОДКЛЮЧЁН",
                        color = if (activeModelLabel != null) Color(0xFF75E2BD) else Color(0xFFE9BD70),
                        fontSize = 8.sp,
                        letterSpacing = 0.7.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            historySyncWarning?.let {
                Text(it, color = Color(0xFFE9BD70), fontSize = 11.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (showImageGeneration) {
                CharacterScopedGenerationPanel(conversationId) {
                    ImageGenerationPanel(
                        app = app,
                        characterId = characterId,
                        conversationId = conversationId,
                        modifier = Modifier.weight(0.42f),
                        imageHistoryReady = imageHistoryReady,
                        onSubmittingChanged = { imageGenerationSubmitting = it },
                        onImagePersisted = { imageHistoryRefreshKey++ },
                        onJobStateChanged = { imageHistoryRefreshKey++ }
                    )
                }
            }
            if (imageHistoryLoadError || imageHistorySyncWarning != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (imageHistoryLoadError) {
                            "Не удалось синхронизировать историю генерации с Lila Core. Локальные изображения сохранены."
                        } else {
                            imageHistorySyncWarning.orEmpty()
                        },
                        color = Color(0xFFE9BD70),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    TextButton(onClick = { imageHistoryRefreshKey++ }) {
                        Text("ПОВТОРИТЬ СИНХРОНИЗАЦИЮ", color = Color(0xFFE9BD70), fontSize = 9.sp)
                    }
                }
            }
            val chatWeight = if (showImageGeneration) 0.58f else 1f
            if (loadError != null) {
                Column(
                    Modifier.fillMaxWidth().weight(chatWeight).padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("История пока недоступна", color = Color.White, fontSize = 22.sp,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(loadError, color = Color(0xFFFF9B9B), fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                    TextButton(onClick = onRetryLoad) { Text("ПОВТОРИТЬ ЗАГРУЗКУ", color = Ice) }
                }
            } else if (isLoading && timeline.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(chatWeight), contentAlignment = Alignment.Center) {
                    Text("Загружаю историю…", color = Muted, fontSize = 14.sp)
                }
            } else if (timeline.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(chatWeight).padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Начало нашей истории", color = Color.White, fontSize = 24.sp,
                        fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (aiConfigured) {
                            "Отправь сообщение — Galaxy передаст его в Lila Core. Ответ модели сохранится вместе с локальной историей."
                        } else {
                            "Здесь можно записывать сообщения. Они сохраняются на телефоне и останутся после перезапуска приложения. Ответы AI появятся после настройки backend."
                        },
                        color = Muted, fontSize = 14.sp, lineHeight = 21.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(chatWeight).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    reverseLayout = false
                ) {
                    items(timeline, key = { it.key }) { entry ->
                        when {
                            entry.message != null -> {
                                val message = entry.message
                                val isUser = message.role == MessageRole.USER
                                val isSystem = message.role == MessageRole.SYSTEM
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth(0.86f)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(
                                                when {
                                                    isUser -> Color(0xFF354779)
                                                    isSystem -> Color(0xFF3A3426)
                                                    else -> Color(0xFF202A46)
                                                }
                                            )
                                            .padding(horizontal = 14.dp, vertical = 11.dp)
                                    ) {
                                        val pendingDelivery = isUser &&
                                            message.id.startsWith(PENDING_LILA_MESSAGE_PREFIX)
                                        Text(
                                            when {
                                                pendingDelivery -> "ТЫ · ОЖИДАЕТ ПОДТВЕРЖДЕНИЯ"
                                                isUser -> "ТЫ"
                                                isSystem -> "МАРШРУТИЗАЦИЯ МОДЕЛИ"
                                                else -> characterName.uppercase()
                                            },
                                            color = when {
                                                pendingDelivery || isSystem -> Color(0xFFE9BD70)
                                                else -> Ice
                                            },
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        if (pendingDelivery) {
                                            Spacer(Modifier.height(3.dp))
                                            Text(
                                                "Сообщение сохранено на устройстве. Результат отправки ещё не подтверждён.",
                                                color = Color(0xFFE9BD70),
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp
                                            )
                                            TextButton(
                                                onClick = {
                                                    draft = message.content
                                                    sendError = null
                                                }
                                            ) {
                                                Text(
                                                    "ПОВТОРИТЬ ЭТО СООБЩЕНИЕ",
                                                    color = Color(0xFFE9BD70),
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(
                                            message.content,
                                            color = if (isSystem) Color(0xFFFFD98A) else Color.White,
                                            fontSize = if (isSystem) 11.sp else 15.sp,
                                            lineHeight = if (isSystem) 16.sp else 21.sp
                                        )
                                    }
                                }
                            }
                            entry.image != null -> GeneratedImageTimelineBubble(
                                app = app,
                                image = entry.image,
                                characterName = characterName
                            )
                            entry.imageJob != null -> ImageJobTimelineBubble(job = entry.imageJob)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1A2440)).padding(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Написать сообщение…", color = Muted) },
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        disabledTextColor = Color.White,
                        cursorColor = Ice,
                        focusedBorderColor = Color(0xFF8060C8),
                        unfocusedBorderColor = Color(0xFF536A9E),
                        focusedPlaceholderColor = Muted,
                        unfocusedPlaceholderColor = Muted
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitDraft() }),
                    enabled = !isSending
                )
                Spacer(Modifier.size(6.dp))
                Button(
                    onClick = { submitDraft() },
                    enabled = draft.isNotBlank() && !isSending,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6578D8))
                ) { Text(if (isSending) "…" else "➤") }
            }
            sendError?.let { error ->
                Spacer(Modifier.height(6.dp))
                Text(error, color = Color(0xFFFF9B9B), fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun ImageJobTimelineBubble(job: LilaImageHistoryJob) {
    val statusColor = when (job.state) {
        "completed" -> Color(0xFF75E2BD)
        "failed" -> Color(0xFFFF9B9B)
        "cancelled" -> Muted
        "running", "provider_completed", "result_pending" -> Ice
        else -> Color(0xFFE9BD70)
    }
    val detail = when (job.state) {
        "submission_unknown" ->
            "Core не подтвердил приём задания. Повторная отправка заблокирована, чтобы не создать дубликат."
        "completed" ->
            "Результат сохранён в Lila Core и ожидает импорта в зашифрованное хранилище Galaxy."
        "failed" ->
            "Lila Core сохранил ошибку задания. Проверь workflow ComfyUI перед новой генерацией."
        "cancelled" ->
            "Задание отменено до начала выполнения."
        else -> null
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF202A46))
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(
                "LILA · ГЕНЕРАЦИЯ ИЗОБРАЖЕНИЯ",
                color = Ice,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                coreImageJobStatusLabel(job.state),
                color = statusColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(5.dp))
            Text(job.prompt, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4)
            Spacer(Modifier.height(4.dp))
            Text(
                "${job.width} × ${job.height}" + (job.seed?.let { " · seed $it" } ?: ""),
                color = Muted,
                fontSize = 9.sp
            )
            detail?.let {
                Spacer(Modifier.height(5.dp))
                Text(it, color = statusColor, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun GeneratedImageTimelineBubble(
    app: GalaxyApplication,
    image: StoredGeneratedImage,
    characterName: String
) {
    var bitmap by remember(image.assetId) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(image.assetId) { mutableStateOf(true) }
    var isUnavailable by remember(image.assetId) { mutableStateOf(false) }

    LaunchedEffect(app, image.assetId) {
        try {
            val plaintext = app.generatedImages.read(image.assetId)
            bitmap = decodeGeneratedImageAndClearBytes(plaintext)
            isUnavailable = bitmap == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            isUnavailable = true
        } finally {
            isLoading = false
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF202A46))
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(
                "${characterName.uppercase()} · ИЗОБРАЖЕНИЕ",
                color = Ice,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(7.dp))
            when {
                bitmap != null -> Image(
                    bitmap = requireNotNull(bitmap),
                    contentDescription = "Сгенерированное изображение",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentScale = ContentScale.Fit
                )
                isLoading -> Text("Загружаю сохранённое изображение…", color = Muted, fontSize = 12.sp)
                isUnavailable -> Text(
                    "Метаданные изображения сохранены, но файл сейчас недоступен.",
                    color = Color(0xFFE9BD70),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "${image.width} × ${image.height} · ${image.mimeType}",
                color = Muted,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun CharacterProfileScreen(
    profile: CharacterProfile?,
    onBack: () -> Unit,
    onSave: (CharacterProfile) -> Unit
) {
    var displayName by remember(profile?.id) { mutableStateOf(profile?.displayName.orEmpty()) }
    var description by remember(profile?.id) { mutableStateOf(profile?.description.orEmpty()) }
    var personaDefinition by remember(profile?.id) { mutableStateOf(profile?.personaDefinition.orEmpty()) }
    var avatarImageUri by remember(profile?.id) { mutableStateOf(profile?.avatarImageUri) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onSuccess {
                avatarImageUri = uri.toString()
                error = null
            }.onFailure {
                error = "Не удалось сохранить доступ к изображению. Выбери другой файл."
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A35), Night, Color(0xFF100D25)))
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹  НАЗАД", color = Ice) }
                Spacer(Modifier.weight(1f))
                Text("ПРОФИЛЬ", color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            if (profile == null) {
                Text("Профиль пока недоступен. Вернись назад и повтори загрузку.",
                    color = Color(0xFFFF9B9B), fontSize = 14.sp)
            } else {
                Text("Эти настройки принадлежат персонажу и сохраняются локально.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Имя персонажа") },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Ice, unfocusedLabelColor = Muted,
                        focusedBorderColor = Ice, unfocusedBorderColor = Color(0xFF536A9E)
                    )
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Краткое описание") },
                    minLines = 2, maxLines = 3,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Ice, unfocusedLabelColor = Muted,
                        focusedBorderColor = Ice, unfocusedBorderColor = Color(0xFF536A9E)
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarPortrait(
                        avatarId = profile.avatarId,
                        modifier = Modifier.size(76.dp),
                        imageUri = avatarImageUri
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("АВАТАР ГАЛАКТИКИ", color = Ice, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(
                            if (avatarImageUri != null) "Пользовательское изображение"
                            else avatarVisualSpec(profile.avatarId ?: "avatar.cosmic").title,
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text("Изображение будет использоваться в главном экране и списке персонажей.",
                            color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                        Text("ВЫБРАТЬ ИЗОБРАЖЕНИЕ", color = Ice, fontSize = 11.sp)
                    }
                    if (avatarImageUri != null) {
                        TextButton(onClick = { avatarImageUri = null }) {
                            Text("УБРАТЬ", color = Color(0xFFFF9B9B), fontSize = 11.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = personaDefinition,
                    onValueChange = { personaDefinition = it; error = null },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text("Личность и инструкции персонажа") },
                    minLines = 5,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Ice, unfocusedLabelColor = Muted,
                        focusedBorderColor = Ice, unfocusedBorderColor = Color(0xFF536A9E)
                    )
                )
                error?.let { Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp) }
                Button(
                    onClick = {
                        val name = displayName.trim()
                        val shortDescription = description.trim()
                        val persona = personaDefinition.trim()
                        if (name.isEmpty()) {
                            error = "Укажи имя персонажа."
                        } else if (persona.isEmpty()) {
                            error = "Опиши личность и инструкции персонажа."
                        } else {
                            onSave(profile.copy(
                                displayName = name,
                                description = shortDescription,
                                personaDefinition = persona,
                                avatarImageUri = avatarImageUri
                            ))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6578D8)),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("СОХРАНИТЬ ПРОФИЛЬ", fontWeight = FontWeight.Bold) }
            }
        }
    }
}


@Composable
private fun CharacterListScreen(
    characters: List<CharacterProfile>,
    activeCharacterId: String,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (CharacterProfile) -> Unit,
    onDelete: (CharacterProfile) -> Unit,
    onSelect: (CharacterProfile) -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A35), Night, Color(0xFF100D25)))
        )
    ) {
        var pendingDelete by remember { mutableStateOf<CharacterProfile?>(null) }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹  НАЗАД", color = Ice) }
                Spacer(Modifier.weight(1f))
                Text("ПЕРСОНАЖИ", color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            Text("У каждого персонажа своя личность и отдельная история диалога.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(characters, key = { it.id }) { character ->
                    val selected = character.id == activeCharacterId
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(if (selected) Color(0xFF29385F) else Color(0xAA18213B))
                            .border(1.dp, if (selected) Color(0xFF8DAEFF) else Color(0x334F6598),
                                RoundedCornerShape(18.dp))
                            .padding(15.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarPortrait(
                                avatarId = character.avatarId,
                                modifier = Modifier.size(52.dp),
                                imageUri = character.avatarImageUri
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(character.displayName, color = Color.White, fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold)
                                if (character.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(character.description, color = Muted, fontSize = 12.sp,
                                        lineHeight = 17.sp, maxLines = 2)
                                }
                            }
                            if (selected) Text("АКТИВЕН", color = Color(0xFF9EDCCB),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            else if (character.isBuiltIn) Text("ВСТРОЕННЫЙ", color = Ice,
                                fontSize = 9.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!selected) {
                                TextButton(onClick = { onSelect(character) }) {
                                    Text("ВЫБРАТЬ", color = Ice)
                                }
                            }
                            if (!character.isBuiltIn) {
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { onEdit(character) }) { Text("ИЗМЕНИТЬ", color = Ice) }
                                TextButton(onClick = { pendingDelete = character }) {
                                    Text("УДАЛИТЬ", color = Color(0xFFFF9B9B))
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6578D8)),
                shape = RoundedCornerShape(16.dp)
            ) { Text("＋  СОЗДАТЬ ПЕРСОНАЖА", fontWeight = FontWeight.Bold) }
        }
        pendingDelete?.let { character ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Удалить персонажа?") },
                text = { Text("Профиль «" + character.displayName + "» будет удалён. История диалога останется в локальной базе.") },
                confirmButton = {
                    TextButton(onClick = { onDelete(character); pendingDelete = null }) {
                        Text("УДАЛИТЬ", color = Color(0xFFFF7777))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("ОТМЕНА") }
                }
            )
        }
    }
}

@Composable
private fun CharacterEditorScreen(
    initialProfile: CharacterProfile?,
    title: String,
    saveLabel: String,
    errorMessage: String?,
    onBack: () -> Unit,
    onSave: (CharacterProfile) -> Unit
) {
    var displayName by remember(initialProfile?.id) { mutableStateOf(initialProfile?.displayName.orEmpty()) }
    var description by remember(initialProfile?.id) { mutableStateOf(initialProfile?.description.orEmpty()) }
    var personaDefinition by remember(initialProfile?.id) { mutableStateOf(initialProfile?.personaDefinition.orEmpty()) }
    var avatarId by remember(initialProfile?.id) { mutableStateOf(initialProfile?.avatarId ?: "avatar.cosmic") }
    var voiceId by remember(initialProfile?.id) { mutableStateOf(initialProfile?.voiceId ?: "coral") }
    var avatarImageUri by remember(initialProfile?.id) { mutableStateOf(initialProfile?.avatarImageUri) }
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onSuccess { avatarImageUri = uri.toString() }
                .onFailure { error = "Не удалось сохранить доступ к изображению. Выбери другой файл." }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A35), Night, Color(0xFF100D25)))
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹  НАЗАД", color = Ice) }
                Spacer(Modifier.weight(1f))
                Text(title, color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            Text("Имя и описание будут видны в списке. Инструкции задают основу личности персонажа.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Имя персонажа") },
                singleLine = true,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedLabelColor = Ice, unfocusedLabelColor = Muted,
                    focusedBorderColor = Ice, unfocusedBorderColor = Color(0xFF536A9E)
                )
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Краткое описание") },
                minLines = 2, maxLines = 2,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedLabelColor = Ice, unfocusedLabelColor = Muted,
                    focusedBorderColor = Ice, unfocusedBorderColor = Color(0xFF536A9E)
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarPortrait(avatarId = avatarId, modifier = Modifier.size(76.dp), imageUri = avatarImageUri)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ПРЕДПРОСМОТР АВАТАРА", color = Ice, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(avatarVisualSpec(avatarId).title, color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(avatarVisualSpec(avatarId).description, color = Muted, fontSize = 11.sp,
                        lineHeight = 15.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                    Text("ВЫБРАТЬ ИЗОБРАЖЕНИЕ", color = Ice, fontSize = 11.sp)
                }
                if (avatarImageUri != null) {
                    TextButton(onClick = { avatarImageUri = null }) {
                        Text("УБРАТЬ", color = Color(0xFFFF9B9B), fontSize = 11.sp)
                    }
                }
            }
            Text("ВНЕШНОСТЬ АВАТАРА", color = Ice, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            PresetChoiceRow(
                selectedId = avatarId,
                options = listOf(
                    "avatar.cosmic" to "Космос",
                    "avatar.cyber" to "Кибер",
                    "avatar.minimal" to "Минимал"
                ),
                onSelect = { avatarId = it }
            )
            Text("ГОЛОС ПЕРСОНАЖА", color = Ice, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            PresetChoiceRow(
                selectedId = voiceId,
                options = listOf(
                    "coral" to "Тёплый",
                    "shimmer" to "Спокойный",
                    "alloy" to "Нейтральный"
                ),
                onSelect = { voiceId = it }
            )
            Text("Аватар уже отображается в интерфейсе. Голосовой пресет задаёт голос ответа в голосовом режиме.",
                color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            OutlinedTextField(
                value = personaDefinition,
                onValueChange = { personaDefinition = it; error = null },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("Личность и инструкции персонажа") },
                minLines = 3,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedLabelColor = Ice, unfocusedLabelColor = Muted,
                    focusedBorderColor = Ice, unfocusedBorderColor = Color(0xFF536A9E)
                )
            )
            errorMessage?.let { Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp) }
            error?.let { Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp) }
            Button(
                onClick = {
                    val name = displayName.trim()
                    val persona = personaDefinition.trim()
                    if (name.isEmpty()) error = "Укажи имя персонажа."
                    else if (persona.isEmpty()) error = "Опиши личность и инструкции персонажа."
                    else onSave(
                        (initialProfile ?: CharacterProfile(
                            id = "",
                            displayName = name,
                            description = description.trim(),
                            personaDefinition = persona
                        )).copy(
                            displayName = name,
                            description = description.trim(),
                            personaDefinition = persona,
                            avatarId = avatarId,
                            voiceId = voiceId,
                            isBuiltIn = initialProfile?.isBuiltIn ?: false,
                            avatarImageUri = avatarImageUri
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6578D8)),
                shape = RoundedCornerShape(16.dp)
            ) { Text(saveLabel, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PresetChoiceRow(
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (id, label) ->
            val selected = id == selectedId
            TextButton(
                onClick = { onSelect(id) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (selected) Color.White else Muted
                )
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}


@Composable
private fun StatusLine(title: String, detail: String, dot: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Muted, fontSize = 11.sp)
        }
    }
}


internal fun lilaFallbackNoticeText(
    fallbackUsed: Boolean,
    providerId: String?,
    modelId: String?,
    fallbackFromProviderId: String?,
    fallbackFromModelId: String?,
    fallbackReason: String?
): String? {
    if (!fallbackUsed) return null
    val actualProvider = providerId ?: return null
    val actualModel = modelId ?: return null
    val primaryProvider = fallbackFromProviderId ?: return null
    val primaryModel = fallbackFromModelId ?: return null
    val reason = when (fallbackReason) {
        "primary_connection_unavailable" -> "основной провайдер не принял соединение до отправки"
        "primary_provider_unavailable" -> "основной провайдер был недоступен до отправки"
        else -> return null
    }
    return "Резервная модель использована. Основная: $primaryProvider · $primaryModel — $reason. Ответ: $actualProvider · $actualModel."
}

internal fun mergeRemoteHistory(
    characterId: String,
    conversationId: String,
    local: List<ConversationMessage>,
    remote: List<LilaHistoryMessage>
): List<ConversationMessage> {
    val unmatchedLocal = local.toMutableList()
    val remoteMessages = mutableListOf<ConversationMessage>()
    remote.forEach { entry ->
        val role = when (entry.role) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.CHARACTER
            else -> throw IllegalArgumentException("Unsupported Lila history role")
        }
        val matchIndex = unmatchedLocal.indexOfFirst {
            !it.id.startsWith(PENDING_LILA_MESSAGE_PREFIX) &&
                it.role == role && it.content == entry.content
        }
        val previous = if (matchIndex >= 0) unmatchedLocal.removeAt(matchIndex) else null
        remoteMessages += ConversationMessage(
            id = previous?.id
                ?.takeUnless { it.startsWith(PENDING_LILA_MESSAGE_PREFIX) }
                ?: "lila-$conversationId-${entry.sequence.toString().padStart(20, '0')}",
            characterId = characterId,
            role = role,
            content = entry.content,
            createdAtEpochMillis = previous?.createdAtEpochMillis ?: entry.createdAtEpochMillis
        )

        val noticeText = lilaFallbackNoticeText(
            fallbackUsed = entry.fallbackUsed,
            providerId = entry.providerId,
            modelId = entry.modelId,
            fallbackFromProviderId = entry.fallbackFromProviderId,
            fallbackFromModelId = entry.fallbackFromModelId,
            fallbackReason = entry.fallbackReason
        )
        if (noticeText != null) {
            val noticeIndex = unmatchedLocal.indexOfFirst {
                it.role == MessageRole.SYSTEM && it.content == noticeText
            }
            val previousNotice = if (noticeIndex >= 0) unmatchedLocal.removeAt(noticeIndex) else null
            remoteMessages += ConversationMessage(
                id = previousNotice?.id
                    ?: "lila-$conversationId-fallback-${entry.sequence.toString().padStart(20, '0')}",
                characterId = characterId,
                role = MessageRole.SYSTEM,
                content = noticeText,
                createdAtEpochMillis = previousNotice?.createdAtEpochMillis ?: entry.createdAtEpochMillis
            )
        }
    }
    return (remoteMessages + unmatchedLocal)
        .sortedBy { it.createdAtEpochMillis }
}


private fun ownerPartitionKey(ownerId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ownerId.toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun conversationStorageKey(ownerKey: String?, characterId: String): String =
    ownerKey?.let { "owner:$it:$characterId" } ?: characterId

private fun getOrCreateLilaConversationId(
    context: Context,
    ownerKey: String,
    characterId: String
): String {
    val preferences = context.getSharedPreferences("galaxy_preferences", Context.MODE_PRIVATE)
    val key = "lila_conversation_id_${ownerKey}_${characterId}"
    val stableId = stableLilaConversationId(ownerKey, characterId)
    if (preferences.getString(key, null) != stableId) {
        preferences.edit().putString(key, stableId).apply()
    }
    return stableId
}



internal fun pendingLilaMessageId(idempotencyKey: String): String =
    "$PENDING_LILA_MESSAGE_PREFIX$idempotencyKey"

private data class PendingLilaTurnKey(
    val value: String,
    val preferenceKey: String
)

private fun pendingLilaTurnPreferenceKey(
    ownerKey: String,
    conversationId: String,
    message: String
): String {
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest("$ownerKey|$conversationId|$message".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "lila_pending_turn_$fingerprint"
}

private fun getOrCreatePendingLilaTurnKey(
    context: Context,
    ownerKey: String,
    conversationId: String,
    message: String
): PendingLilaTurnKey {
    val preferenceKey = pendingLilaTurnPreferenceKey(ownerKey, conversationId, message)
    val preferences = context.getSharedPreferences("galaxy_preferences", Context.MODE_PRIVATE)
    val existing = preferences.getString(preferenceKey, null)
        ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
    val value = existing ?: UUID.randomUUID().toString().also { generated ->
        preferences.edit().putString(preferenceKey, generated).apply()
    }
    return PendingLilaTurnKey(value, preferenceKey)
}

private fun clearPendingLilaTurnKey(context: Context, preferenceKey: String) {
    context.getSharedPreferences("galaxy_preferences", Context.MODE_PRIVATE)
        .edit()
        .remove(preferenceKey)
        .apply()
}
