package com.pitgpt.galaxy.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import kotlinx.coroutines.launch

private val Night = Color(0xFF080B18)
private val Muted = Color(0xFF9AA8C5)
private val Ice = Color(0xFFB8D9FF)
private const val CHARACTER_ID = "galaxy.default"

@Composable
fun GalaxyHome() {
    val context = LocalContext.current
    val app = context.applicationContext as GalaxyApplication
    var characterName by remember { mutableStateOf("Галактика") }
    var initialized by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var showChat by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<CharacterProfile?>(null) }
    val messages = remember { mutableStateListOf<ConversationMessage>() }

    LaunchedEffect(reloadKey) {
        isLoading = true
        loadError = null
        try {
            app.initialize()
            val loadedName = app.characters.getById(CHARACTER_ID)?.displayName ?: "Галактика"
            val loadedMessages = app.conversations.getForCharacter(CHARACTER_ID)
            profile = app.characters.getById(CHARACTER_ID)
            characterName = loadedName
            messages.clear()
            messages.addAll(loadedMessages)
            initialized = true
        } catch (error: Exception) {
            loadError = "Не удалось загрузить данные Galaxy. Проверь хранилище и попробуй ещё раз."
        } finally {
            isLoading = false
        }
    }

    if (showProfile) {
        CharacterProfileScreen(
            profile = profile,
            onBack = { showProfile = false },
            onSave = { updated ->
                app.characters.save(updated)
                profile = updated
                characterName = updated.displayName
                showProfile = false
            }
        )
    } else if (showChat) {
        ConversationScreen(
            characterName = characterName,
            messages = messages,
            isLoading = isLoading,
            loadError = loadError,
            onRetryLoad = { reloadKey += 1 },
            onBack = { showChat = false },
            onSend = { text ->
                val message = SendMessage(app.conversations).invoke(CHARACTER_ID, text)
                messages.add(message)
            }
        )
    } else {
        HomeScreen(
            characterName = if (initialized) characterName else null,
            isLoading = isLoading,
            loadError = loadError,
            onRetry = { reloadKey += 1 },
            onOpenChat = { showChat = true },
            onOpenProfile = { showProfile = true }
        )
    }
}

@Composable
private fun HomeScreen(
    characterName: String?,
    isLoading: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenProfile: () -> Unit
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
                        Text("●  LOCAL PREVIEW", color = Color(0xFF75E2BD),
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                Box(
                    Modifier.size(112.dp).background(
                        Brush.radialGradient(listOf(Color(0xFFE2F4FF), Ice,
                            Color(0xFF7589D8), Color(0xFF28366D))), CircleShape
                    ).border(1.dp, Color(0x99D8EEFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("G", color = Color(0xFF172448), fontSize = 54.sp,
                        fontWeight = FontWeight.Light)
                }
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
                StatusLine("AI и голос", "Подключение на следующих этапах", Color(0xFFE9BD70))
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
    characterName: String,
    messages: List<ConversationMessage>,
    isLoading: Boolean,
    loadError: String?,
    onRetryLoad: () -> Unit,
    onBack: () -> Unit,
    onSend: suspend (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val sendScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun submitDraft() {
        val value = draft.trim()
        if (value.isEmpty() || isSending) return
        sendScope.launch {
            isSending = true
            sendError = null
            try {
                onSend(value)
                draft = ""
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
        )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹  НАЗАД", color = Ice) }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(characterName, color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("ЛОКАЛЬНЫЙ ДИАЛОГ · AI НЕ ПОДКЛЮЧЁН",
                        color = Color(0xFFE9BD70), fontSize = 8.sp, letterSpacing = 0.7.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (loadError != null) {
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
private fun CharacterProfileScreen(
    profile: CharacterProfile?,
    onBack: () -> Unit,
    onSave: (CharacterProfile) -> Unit
) {
    var displayName by remember(profile?.id) { mutableStateOf(profile?.displayName.orEmpty()) }
    var description by remember(profile?.id) { mutableStateOf(profile?.description.orEmpty()) }
    var personaDefinition by remember(profile?.id) { mutableStateOf(profile?.personaDefinition.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

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
                                personaDefinition = persona
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
