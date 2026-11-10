package com.pitgpt.galaxy.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import com.pitgpt.galaxy.core.usecase.SendMessage
import com.pitgpt.galaxy.core.usecase.GenerateCharacterReply
import com.pitgpt.galaxy.data.voice.RealtimeVoiceController
import com.pitgpt.galaxy.data.voice.RealtimeVoiceStatus
import com.pitgpt.galaxy.data.providers.EncryptedProviderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private val Night = Color(0xFF080B18)
private val Muted = Color(0xFF9AA8C5)
private val Ice = Color(0xFFB8D9FF)
private const val CHARACTER_ID = "galaxy.default"

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
    var showSettings by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }
    var showComfyUiSettings by remember { mutableStateOf(false) }
    var showCharacters by remember { mutableStateOf(false) }
    var showCreateCharacter by remember { mutableStateOf(false) }
    var showEditCharacter by remember { mutableStateOf(false) }
    var editorProfile by remember { mutableStateOf<CharacterProfile?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<CharacterProfile?>(null) }
    val messages = remember { mutableStateListOf<ConversationMessage>() }
    val scope = rememberCoroutineScope()
    val voiceController = remember(app, scope) {
        RealtimeVoiceController(
            context = context,
            sessionProviderFactory = { app.authenticatedRealtimeSessionProvider() },
            conversations = app.conversations,
            scope = scope,
            onTranscript = { message -> messages.add(message) }
        )
    }
    val voiceState by voiceController.state.collectAsState()
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val selectedProfile = profile
        if (granted && selectedProfile != null) {
            scope.launch { voiceController.connect(selectedProfile) }
        } else if (!granted) {
            voiceController.setPermissionDenied()
        }
    }
    DisposableEffect(voiceController) {
        onDispose { voiceController.disconnect() }
    }

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
            val loadedMessages = app.conversations.getForCharacter(activeProfile.id)
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
                            messages.addAll(app.conversations.getForCharacter(fallback.id))
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
                        val selectedMessages = app.conversations.getForCharacter(selectedProfile.id)
                        activeCharacterId = selectedProfile.id
                        context.getSharedPreferences("galaxy_preferences", 0).edit()
                            .putString("active_character_id", selectedProfile.id).apply()
                        profile = selectedProfile
                        characterName = selectedProfile.displayName
                        messages.clear()
                        messages.addAll(selectedMessages)
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
    } else if (showMemory) {
        MemoryManagementScreen(
            repository = app.memories,
            activeCharacterId = activeCharacterId,
            onBack = { showMemory = false; showSettings = true }
        )
    } else if (showProviders) {
        AiProvidersScreen(store = app.providerStore, onBack = { showProviders = false; showSettings = true })
    } else if (showComfyUiSettings) {
        ComfyUiSettingsScreen(
            store = app.providerStore,
            onBack = { showComfyUiSettings = false; showSettings = true }
        )
    } else if (showSettings) {
        AppSettingsScreen(
            onBack = { showSettings = false },
            onOpenProviders = { showSettings = false; showProviders = true },
            onOpenComfyUi = { showSettings = false; showComfyUiSettings = true },
            onOpenMemory = { showSettings = false; showMemory = true }
        )
    } else if (showChat) {
        ConversationScreen(
            app = app,
            characterId = activeCharacterId,
            characterName = characterName,
            messages = messages,
            isLoading = isLoading,
            loadError = loadError,
            onRetryLoad = { reloadKey += 1 },
            voiceState = voiceState,
            onStartVoice = {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    profile?.let { selected -> scope.launch { voiceController.connect(selected) } }
                } else {
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopVoice = { voiceController.disconnect() },
            onToggleMute = { voiceController.setMicrophoneEnabled(voiceState.muted) },
            onBack = { voiceController.disconnect(); showChat = false },
            onSend = { text ->
                val userMessage = SendMessage(app.conversations).invoke(activeCharacterId, text)
                messages.add(userMessage)
                try {
                    val selectedProfile = requireNotNull(profile) { "Active character profile is missing" }
                    val reply = GenerateCharacterReply(
                        conversations = app.conversations,
                        providerStore = app.providerStore
                    ).invoke(
                        characterId = activeCharacterId,
                        systemPrompt = selectedProfile.personaDefinition
                    )
                    messages.add(reply)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw CharacterReplyGenerationException(error)
                }
            }
        )
    } else {
        HomeScreen(
            characterName = if (initialized) characterName else null,
            avatarId = profile?.avatarId,
            avatarImageUri = profile?.avatarImageUri,
            isLoading = isLoading,
            loadError = loadError,
            onRetry = { reloadKey += 1 },
            onOpenChat = { showChat = true },
            onOpenProfile = { showProfile = true },
            onOpenCharacters = { showCharacters = true },
            onOpenSettings = { showSettings = true }
        )
    }
}

@Composable
private fun HomeScreen(
    characterName: String?,
    avatarId: String?,
    avatarImageUri: String?,
    isLoading: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A35), Night, Color(0xFF100D25)))
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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
                        Text("●  LOCAL PREVIEW", color = Color(0xFF75E2BD),
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                        color = Color(0xFF91A5CC), fontSize = 9.sp)
                    Text(BuildConfig.GIT_SHA, color = Color(0xFF657AAB), fontSize = 8.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(208.dp), contentAlignment = Alignment.Center) {
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
            Spacer(Modifier.height(24.dp))
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
                StatusLine("AI и голос", "Голосовой режим — в диалоге", Color(0xFFE9BD70))
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
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28375D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("НАСТРОЙКИ", fontWeight = FontWeight.SemiBold)
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
    characterId: String,
    characterName: String,
    messages: List<ConversationMessage>,
    isLoading: Boolean,
    loadError: String?,
    onRetryLoad: () -> Unit,
    voiceState: com.pitgpt.galaxy.data.voice.RealtimeVoiceUiState,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onToggleMute: () -> Unit,
    onBack: () -> Unit,
    onSend: suspend (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var showImageGeneration by remember { mutableStateOf(false) }
    var imageGenerationSubmitting by remember { mutableStateOf(false) }
    val sendScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboardDismiss = rememberOutsideTapKeyboardDismissState()

    fun submitDraft() {
        val value = draft.trim()
        if (value.isEmpty() || isSending) return
        sendScope.launch {
            isSending = true
            sendError = null
            try {
                onSend(value)
                draft = ""
            } catch (error: CancellationException) {
                throw error
            } catch (error: CharacterReplyGenerationException) {
                draft = ""
                sendError = "Сообщение сохранено, но ответ AI не получен. Проверь провайдера и повтори позже."
            } catch (error: Exception) {
                sendError = "Не удалось сохранить сообщение. Текст остался в поле, попробуй ещё раз."
            } finally {
                isSending = false
            }
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A35), Night, Color(0xFF100D25)))
        ).then(keyboardDismiss.rootModifier())
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onBack,
                    enabled = !imageGenerationNavigationLocked(imageGenerationSubmitting)
                ) {
                    Text("‹  НАЗАД", color = Ice)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { showImageGeneration = !showImageGeneration },
                    enabled = !imageGenerationNavigationLocked(imageGenerationSubmitting, imageGenerationActive)
                ) {
                    Text(if (showImageGeneration) "К ЧАТУ" else "ОБРАЗ", color = Ice, fontSize = 9.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(characterName, color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(when (voiceState.status) {
                        RealtimeVoiceStatus.CONNECTED -> "ГОЛОСОВОЙ КАНАЛ ПОДКЛЮЧЁН"
                        RealtimeVoiceStatus.CONNECTING -> "ПОДКЛЮЧАЮ ГОЛОС…"
                        RealtimeVoiceStatus.DISCONNECTED -> "СВЯЗЬ ПРЕРВАНА"
                        RealtimeVoiceStatus.FAILED -> "ОШИБКА ГОЛОСОВОГО КАНАЛА"
                        else -> "ЛОКАЛЬНАЯ ИСТОРИЯ · AI НЕ ПОДКЛЮЧЁН"
                    }, color = if (voiceState.status == RealtimeVoiceStatus.CONNECTED) Color(0xFF75E2BD) else Color(0xFFE9BD70),
                        fontSize = 8.sp, letterSpacing = 0.7.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (showImageGeneration) {
                ImageGenerationPanel(
                    app = app,
                    characterId = characterId,
                    modifier = Modifier.weight(1f),
                    onSubmittingChanged = { imageGenerationSubmitting = it }
                )
            } else if (loadError != null) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp),
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
            } else if (isLoading && messages.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Загружаю историю…", color = Muted, fontSize = 14.sp)
                }
            } else if (messages.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Начало нашей истории", color = Color.White, fontSize = 24.sp,
                        fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Здесь можно записывать сообщения. Они сохраняются на телефоне и останутся после перезапуска приложения. Ответы AI появятся после подключения модели.",
                        color = Muted, fontSize = 14.sp, lineHeight = 21.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    reverseLayout = false
                ) {
                    items(messages, key = { it.id }) { message ->
                        val isUser = message.role == MessageRole.USER
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Column(
                                Modifier.fillMaxWidth(0.86f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isUser) Color(0xFF354779) else Color(0xFF202A46))
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Text(if (isUser) "ТЫ" else characterName.uppercase(),
                                    color = Ice, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp)
                                Spacer(Modifier.height(5.dp))
                                Text(message.content, color = Color.White, fontSize = 15.sp,
                                    lineHeight = 21.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(voiceState.message ?: "Голосовой режим выключен", color = Muted, fontSize = 10.sp)
                    if (voiceState.status == RealtimeVoiceStatus.CONNECTED) {
                        Text("Аудио передаётся напрямую по WebRTC", color = Color(0xFF75E2BD), fontSize = 9.sp)
                    }
                }
                when (voiceState.status) {
                    RealtimeVoiceStatus.CONNECTED -> {
                        TextButton(onClick = onToggleMute) {
                            Text(if (voiceState.muted) "ВКЛ. МИКРОФОН" else "МИКРОФОН ВЫКЛ.", color = Ice, fontSize = 9.sp)
                        }
                        TextButton(onClick = onStopVoice) { Text("СТОП", color = Color(0xFFFFA0A0), fontSize = 9.sp) }
                    }
                    RealtimeVoiceStatus.CONNECTING -> Text("ПОДОЖДИ…", color = Ice, fontSize = 10.sp)
                    else -> Button(onClick = onStartVoice, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6578D8))) {
                        Text("ГОВОРИТЬ", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1A2440)).padding(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).then(keyboardDismiss.textFieldModifier("message")),
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
    val keyboardDismiss = rememberOutsideTapKeyboardDismissState()
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
        ).then(keyboardDismiss.rootModifier())
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
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
                    modifier = Modifier.fillMaxWidth().then(keyboardDismiss.textFieldModifier("displayName")),
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
                    modifier = Modifier.fillMaxWidth().then(keyboardDismiss.textFieldModifier("description")),
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
                    modifier = Modifier.fillMaxWidth().then(keyboardDismiss.textFieldModifier("persona")),
                    label = { Text("Личность и инструкции персонажа") },
                    minLines = 5,
                    maxLines = 8,
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
    val keyboardDismiss = rememberOutsideTapKeyboardDismissState()
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
        ).then(keyboardDismiss.rootModifier())
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
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
                modifier = Modifier.fillMaxWidth().then(keyboardDismiss.textFieldModifier("displayName")),
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
                modifier = Modifier.fillMaxWidth().then(keyboardDismiss.textFieldModifier("description")),
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
                modifier = Modifier.fillMaxWidth().then(keyboardDismiss.textFieldModifier("persona")),
                label = { Text("Личность и инструкции персонажа") },
                minLines = 3,
                maxLines = 8,
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

private class CharacterReplyGenerationException(cause: Throwable) : Exception(cause)
