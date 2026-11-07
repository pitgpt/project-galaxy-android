package com.pitgpt.galaxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.data.providers.AiProviderConfig
import com.pitgpt.galaxy.data.providers.EncryptedProviderStore
import com.pitgpt.galaxy.data.providers.ProviderSettings
import com.pitgpt.galaxy.data.providers.ProviderSettingsLoadException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

private val ProviderNight = Color(0xFF080B18)
private val ProviderText = Color.White
private val ProviderMuted = Color(0xFF9AA8C5)
private val ProviderAccent = Color(0xFFB8D9FF)

@Composable
fun AiProvidersScreen(store: EncryptedProviderStore, onBack: () -> Unit) {
    val initialLoad = remember(store) { runCatching { store.load() } }
    var settings by remember { mutableStateOf(initialLoad.getOrElse { ProviderSettings() }) }
    var loadError by remember {
        mutableStateOf(initialLoad.exceptionOrNull()?.let {
            "Не удалось расшифровать сохранённые настройки. Исходные данные сохранены и не удалены."
        })
    }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.example.com/v1") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun startEdit(provider: AiProviderConfig?) {
        isEditing = true
        editingId = provider?.id
        name = provider?.name.orEmpty()
        baseUrl = provider?.baseUrl ?: "https://api.example.com/v1"
        apiKey = provider?.apiKey.orEmpty()
        model = provider?.model.orEmpty()
        status = null
    }
    fun persist(next: ProviderSettings): Boolean {
        return try {
            store.save(next)
            settings = next
            status = "Настройки сохранены на этом устройстве."
            true
        } catch (_: Exception) {
            status = "Не удалось сохранить настройки. Проверь доступность Android Keystore и повтори попытку."
            false
        }
    }

    Column(Modifier.fillMaxSize().background(ProviderNight).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("‹ НАЗАД", color = ProviderAccent) }
            Text("AI-ПРОВАЙДЕРЫ", color = ProviderText, fontSize = 17.sp)
        }
        Text("Ключи хранятся локально в зашифрованном виде с помощью Android Keystore. Не отправляй их в чат и не делись скриншотами с открытым ключом.",
            color = ProviderMuted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(14.dp))
        if (loadError != null) {
            Text("Сохранённые настройки провайдера сейчас недоступны.", color = Color(0xFFFFB0A8),
                fontSize = 15.sp)
            Text("Возможно, Android Keystore временно недоступен или ключ был сброшен. Приложение не удалило зашифрованные данные и не будет их перезаписывать.",
                color = ProviderMuted, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                try {
                    settings = store.load()
                    loadError = null
                    status = "Сохранённые настройки восстановлены."
                } catch (_: ProviderSettingsLoadException) {
                    loadError = "Не удалось прочитать сохранённые настройки. Данные по-прежнему сохранены."
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("ПОВТОРИТЬ ЧТЕНИЕ")
            }
            TextButton(onClick = { showResetConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                Text("СБРОСИТЬ ХРАНИЛИЩЕ", color = Color(0xFFFF9B9B))
            }
        } else if (!isEditing) {
            Button(onClick = { startEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                Text("＋ ДОБАВИТЬ ПРОВАЙДЕРА")
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(settings.providers, key = { it.id }) { provider ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18213B))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(provider.name, color = ProviderText, fontSize = 16.sp)
                            Text("${provider.model} · ${provider.baseUrl}", color = ProviderMuted, fontSize = 11.sp)
                            Text(if (settings.activeProviderId == provider.id) "Активный провайдер" else "Не выбран",
                                color = if (settings.activeProviderId == provider.id) Color(0xFF75E2BD) else ProviderMuted,
                                fontSize = 11.sp)
                            Row {
                                TextButton(onClick = { persist(settings.copy(activeProviderId = provider.id)) }) {
                                    Text("ВЫБРАТЬ")
                                }
                                TextButton(onClick = { startEdit(provider) }) { Text("ИЗМЕНИТЬ") }
                                TextButton(onClick = {
                                    val remaining = settings.providers.filterNot { it.id == provider.id }
                                    persist(settings.copy(providers = remaining,
                                        activeProviderId = if (settings.activeProviderId == provider.id) remaining.firstOrNull()?.id else settings.activeProviderId))
                                }) { Text("УДАЛИТЬ", color = Color(0xFFFF9B9B)) }
                            }
                        }
                    }
                }
            }
            if (settings.providers.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Пока нет подключённых провайдеров. Добавь совместимый API, например агрегатор с OpenAI-compatible интерфейсом.",
                    color = ProviderMuted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        } else {
            Text(if (editingId == null) "Новый провайдер" else "Параметры провайдера",
                color = ProviderText, fontSize = 16.sp)
            ProviderField(name, { name = it }, "Название (например, ProxyAPI)")
            ProviderField(baseUrl, { baseUrl = it }, "Base URL", singleLine = true)
            ProviderField(apiKey, { apiKey = it }, "API-ключ", secret = true, singleLine = true)
            ProviderField(model, { model = it }, "Модель (например, gpt-4.1-mini)", singleLine = true)
            Text("Для первой версии поддерживаются API с OpenAI-совместимым /chat/completions и /models. Подключение к голосовому режиму будет отдельным этапом.",
                color = ProviderMuted, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !checking && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                onClick = {
                    checking = true
                    status = "Проверяю доступность API…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { checkProvider(baseUrl.trim(), apiKey.trim()) }
                        checking = false
                        status = result
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text(if (checking) "ПРОВЕРКА…" else "ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ") }
            status?.let { Text(it, color = ProviderAccent, fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = { editingId = null; isEditing = false; status = null }) { Text("ОТМЕНА") }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    val cleanName = name.trim()
                    val cleanUrl = baseUrl.trim().trimEnd('/')
                    val cleanModel = model.trim()
                    if (cleanName.isBlank() || cleanUrl.isBlank() || apiKey.isBlank() || cleanModel.isBlank()) {
                        status = "Заполни все поля."
                    } else if (!isSafeProviderBaseUrl(cleanUrl)) {
                        status = "Укажи корректный HTTPS Base URL без логина, пароля, query-параметров или fragment."
                    } else {
                        val item = AiProviderConfig(editingId ?: UUID.randomUUID().toString(),
                            cleanName, cleanUrl, apiKey.trim(), cleanModel)
                        val updated = settings.providers.filterNot { it.id == item.id } + item
                        if (persist(settings.copy(providers = updated,
                                activeProviderId = settings.activeProviderId ?: item.id))) {
                            editingId = null
                        }
                    }
                }) { Text("СОХРАНИТЬ") }
            }
        }
        status?.takeIf { !isEditing && loadError == null }?.let {
            Text(it, color = ProviderAccent, fontSize = 11.sp)
        }
    }
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Удалить сохранённые настройки?") },
            text = {
                Text("Это действие безвозвратно удалит локальную зашифрованную копию провайдеров и API-ключей. Продолжай только если восстановить доступ не удалось и ты готов настроить провайдеры заново.")
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        store.clear()
                        settings = ProviderSettings()
                        loadError = null
                        isEditing = false
                        status = "Хранилище очищено. Можно добавить провайдера заново."
                    } catch (_: Exception) {
                        loadError = "Не удалось очистить хранилище. Исходные данные не подтверждены как удалённые."
                    } finally {
                        showResetConfirmation = false
                    }
                }) { Text("УДАЛИТЬ", color = Color(0xFFFF7777)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) { Text("ОТМЕНА") }
            }
        )
    }
}

@Composable
private fun ProviderField(value: String, onValueChange: (String) -> Unit, label: String,
                          secret: Boolean = false, singleLine: Boolean = false) {
    OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text(label) }, singleLine = singleLine,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ProviderText, unfocusedTextColor = ProviderText,
            focusedLabelColor = ProviderAccent, unfocusedLabelColor = ProviderMuted,
            focusedBorderColor = ProviderAccent, unfocusedBorderColor = Color(0xFF536A9E)
        )
    )
}

private fun isSafeProviderBaseUrl(value: String): Boolean = runCatching {
    val uri = URI(value.trim().trimEnd('/'))
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

private fun checkProvider(baseUrl: String, apiKey: String): String {
    var connection: HttpURLConnection? = null
    return try {
        if (!isSafeProviderBaseUrl(baseUrl)) return "Некорректный адрес. Разрешён только безопасный HTTPS Base URL."
        val endpoint = baseUrl.trimEnd('/') + "/models"
        connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        when (code) {
            in 200..299 -> "API доступен. HTTP $code. Это ещё не проверка выбранной модели."
            401, 403 -> "Сервер ответил HTTP $code: проверь ключ и права доступа."
            404 -> "Адрес /models не найден (HTTP 404). Уточни Base URL или проверь API вручную."
            else -> "Сервер ответил HTTP $code."
        }
    } catch (error: Exception) {
        "Не удалось подключиться: ${error.javaClass.simpleName}. Проверь адрес и интернет."
    } finally {
        connection?.disconnect()
    }
}
