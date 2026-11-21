package com.pitgpt.galaxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.data.remote.GalaxyApiException
import com.pitgpt.galaxy.data.remote.HttpLilaProviderCatalog
import com.pitgpt.galaxy.data.remote.LilaConversationModelSelection
import com.pitgpt.galaxy.data.remote.LilaProviderProbeResult
import com.pitgpt.galaxy.data.remote.LilaProviderSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val ProviderScreenText = Color(0xFFB8D9FF)
private val ProviderScreenMuted = Color(0xFF9AA8C5)

@Composable
fun LilaProviderSettingsScreen(
    catalog: HttpLilaProviderCatalog,
    conversationId: String?,
    onBack: () -> Unit,
    onModelSelected: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<LilaProviderSummary>>(emptyList()) }
    var selection by remember {
        mutableStateOf(LilaConversationModelSelection(conversationId.orEmpty(), false, null, null))
    }
    var probes by remember { mutableStateOf<Map<String, LilaProviderProbeResult>>(emptyMap()) }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var selectedModelId by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var editingProviderId by remember { mutableStateOf<String?>(null) }
    var providerId by remember { mutableStateOf("") }
    var providerType by remember { mutableStateOf("cloud") }
    var baseUrl by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var timeoutSeconds by remember { mutableStateOf("30") }
    var enabled by remember { mutableStateOf(true) }
    var clearApiKey by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LilaProviderSummary?>(null) }

    suspend fun reload() {
        isLoading = true
        error = null
        try {
            providers = catalog.listProviders()
            if (conversationId != null) {
                selection = catalog.getConversationModel(conversationId)
                selectedProviderId = selection.providerId
                selectedModelId = selection.modelId.orEmpty()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = (exception as? GalaxyApiException)?.message
                ?: "Не удалось загрузить каталог провайдеров."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(conversationId) { reload() }

    fun resetEditor() {
        editingProviderId = null
        providerId = ""
        providerType = "cloud"
        baseUrl = ""
        modelId = ""
        apiKey = ""
        timeoutSeconds = "30"
        enabled = true
        clearApiKey = false
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("НАЗАД", color = ProviderScreenText) }
            Spacer(Modifier.weight(1f))
            Text("LILA CORE", color = ProviderScreenMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text("ПРОВАЙДЕРЫ И МОДЕЛИ", color = ProviderScreenText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "API keys отправляются только в защищённый backend. Galaxy не сохраняет их в Room или SharedPreferences.",
            color = ProviderScreenMuted,
            fontSize = 12.sp
        )
        if (!catalog.isConfigured) {
            Text(
                "Backend Galaxy не настроен. Для управления провайдерами нужна HTTPS-сборка.",
                color = ProviderScreenMuted,
                fontSize = 12.sp
            )
        }
        if (conversationId == null) {
            Text(
                "Войди в аккаунт Galaxy, чтобы выбрать модель для общего диалога.",
                color = ProviderScreenMuted,
                fontSize = 12.sp
            )
        } else {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("МОДЕЛЬ ЭТОГО ДИАЛОГА", fontWeight = FontWeight.Bold)
                    if (selection.selected) {
                        Text("${selection.providerId} · ${selection.modelId}", color = ProviderScreenText, fontWeight = FontWeight.SemiBold)
                        if (providers.none { it.providerId == selection.providerId }) {
                            Text(
                                "Выбранный провайдер удалён. Выбери другой — автоматической подмены нет.",
                                color = ProviderScreenMuted,
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        Text("Модель ещё не выбрана.", color = ProviderScreenMuted)
                    }
                }
            }
        }

        error?.let { Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp) }
        notice?.let { Text(it, color = Color(0xFF75E2BD), fontSize = 12.sp) }
        if (isLoading) {
            Text("Загружаю каталог…", color = ProviderScreenMuted)
        } else {
            Text("СОХРАНЁННЫЕ ПРОВАЙДЕРЫ", color = ProviderScreenMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (providers.isEmpty()) {
                Text("Пока нет подключённых провайдеров.", color = ProviderScreenMuted, fontSize = 12.sp)
            }
            providers.forEach { provider ->
                val probe = probes[provider.providerId]
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(provider.providerId, fontWeight = FontWeight.Bold)
                        Text("${provider.providerType.uppercase()} · ${provider.modelId}", color = ProviderScreenText, fontSize = 12.sp)
                        Text(
                            (if (provider.enabled) "Включён" else "Отключён") +
                                (if (provider.hasApiKey) " · API key сохранён" else " · без API key"),
                            color = ProviderScreenMuted,
                            fontSize = 11.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                enabled = !isSaving && provider.enabled,
                                onClick = {
                                    scope.launch {
                                        isSaving = true
                                        error = null
                                        notice = null
                                        try {
                                            val result = catalog.probeProvider(provider.providerId)
                                            probes = probes + (provider.providerId to result)
                                            selectedProviderId = provider.providerId
                                            selectedModelId = result.configuredModelId
                                            notice = if (result.modelDiscoverySupported) {
                                                "Соединение доступно. /models вернул ${result.modelIds.size} моделей; генерация ещё не проверялась."
                                            } else {
                                                "Endpoint доступен, но /models не поддерживается. Укажи model ID вручную."
                                            }
                                        } catch (exception: CancellationException) {
                                            throw exception
                                        } catch (exception: Exception) {
                                            error = (exception as? GalaxyApiException)?.message
                                                ?: "Не удалось проверить провайдера."
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            ) { Text("ПРОВЕРИТЬ") }
                            TextButton(
                                enabled = !isSaving,
                                onClick = {
                                    editingProviderId = provider.providerId
                                    providerId = provider.providerId
                                    providerType = provider.providerType
                                    baseUrl = ""
                                    modelId = provider.modelId
                                    apiKey = ""
                                    timeoutSeconds = provider.timeoutSeconds.toInt().toString()
                                    enabled = provider.enabled
                                    clearApiKey = false
                                    notice = "Backend не возвращает сохранённый URL. Введи его заново для редактирования."
                                    error = null
                                }
                            ) { Text("ИЗМЕНИТЬ") }
                            TextButton(enabled = !isSaving, onClick = { pendingDelete = provider }) {
                                Text("УДАЛИТЬ")
                            }
                        }
                        if (probe != null) {
                            Text(
                                if (probe.connectionReachable) "Соединение: доступно" else "Соединение: недоступно",
                                color = ProviderScreenMuted,
                                fontSize = 11.sp
                            )
                            if (probe.modelIds.isNotEmpty()) {
                                Text("Найденные модели:", color = ProviderScreenMuted, fontSize = 11.sp)
                                probe.modelIds.forEach { discovered ->
                                    TextButton(onClick = {
                                        selectedProviderId = provider.providerId
                                        selectedModelId = discovered
                                    }) {
                                        Text(
                                            discovered,
                                            color = if (selectedModelId == discovered && selectedProviderId == provider.providerId)
                                                ProviderScreenText else ProviderScreenMuted
                                        )
                                    }
                                }
                            }
                        }
                        if (conversationId != null) {
                            OutlinedTextField(
                                value = if (selectedProviderId == provider.providerId) selectedModelId else provider.modelId,
                                onValueChange = {
                                    selectedProviderId = provider.providerId
                                    selectedModelId = it
                                },
                                label = { Text("Модель для этого диалога") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Button(
                                enabled = !isSaving && provider.enabled,
                                onClick = {
                                    val chosenModel = if (selectedProviderId == provider.providerId) selectedModelId else provider.modelId
                                    scope.launch {
                                        isSaving = true
                                        error = null
                                        notice = null
                                        try {
                                            val result = catalog.setConversationModel(
                                                conversationId, provider.providerId, chosenModel
                                            )
                                            selection = result
                                            onModelSelected("${provider.providerId} · $chosenModel")
                                            notice = "Модель сохранена для этого диалога."
                                        } catch (exception: CancellationException) {
                                            throw exception
                                        } catch (exception: Exception) {
                                            error = (exception as? GalaxyApiException)?.message
                                                ?: "Не удалось выбрать модель."
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            ) { Text("ВЫБРАТЬ ДЛЯ ДИАЛОГА") }
                        }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (editingProviderId == null) "ДОБАВИТЬ ПРОВАЙДЕР" else "ИЗМЕНИТЬ ПРОВАЙДЕР", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = providerId,
                    onValueChange = { providerId = it },
                    label = { Text("Provider ID") },
                    enabled = editingProviderId == null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { providerType = "cloud" }) {
                        Text(if (providerType == "cloud") "● CLOUD" else "CLOUD")
                    }
                    TextButton(onClick = { providerType = "local" }) {
                        Text(if (providerType == "local") "● LOCAL" else "LOCAL")
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (адрес, доступный backend)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (editingProviderId == null) "API key (необязательно)" else "Новый API key (пусто — оставить прежний)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = timeoutSeconds,
                    onValueChange = { timeoutSeconds = it },
                    label = { Text("Timeout, секунд (1–120)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("Провайдер включён")
                }
                if (editingProviderId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearApiKey, onCheckedChange = { clearApiKey = it })
                        Text("Удалить сохранённый API key")
                    }
                    Text(
                        "Локальные серверы должны быть доступны с машины backend. Для Docker localhost относится к контейнеру.",
                        color = ProviderScreenMuted,
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isSaving && !isLoading && providerId.isNotBlank() &&
                            baseUrl.isNotBlank() && modelId.isNotBlank() &&
                            timeoutSeconds.toDoubleOrNull() != null,
                        onClick = {
                            scope.launch {
                                isSaving = true
                                error = null
                                notice = null
                                try {
                                    val timeout = timeoutSeconds.toDouble()
                                    if (editingProviderId == null) {
                                        catalog.createProvider(
                                            providerId, providerType, baseUrl, modelId,
                                            apiKey.takeIf { it.isNotBlank() }, timeout, enabled
                                        )
                                    } else {
                                        catalog.updateProvider(
                                            editingProviderId!!, providerType, baseUrl, modelId,
                                            apiKey.takeIf { it.isNotBlank() }, clearApiKey,
                                            timeout, enabled
                                        )
                                    }
                                    apiKey = ""
                                    clearApiKey = false
                                    notice = "Провайдер сохранён. Ключ не хранится на устройстве."
                                    resetEditor()
                                    reload()
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    error = (exception as? GalaxyApiException)?.message
                                        ?: "Не удалось сохранить провайдера."
                                } finally {
                                    isSaving = false
                                }
                            }
                        }
                    ) { Text("СОХРАНИТЬ") }
                    if (editingProviderId != null) {
                        TextButton(onClick = { resetEditor() }) { Text("ОТМЕНА") }
                    }
                }
            }
        }
        Text(
            "Проверка /models не подтверждает генерацию. Совместимость с runtime подтверждается только реальным тестовым ходом.",
            color = ProviderScreenMuted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(18.dp))
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить провайдера?") },
            text = {
                Text("Конфигурация ${target.providerId} будет удалена. Выбор в существующих диалогах останется неактивным до повторного выбора.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        isSaving = true
                        error = null
                        try {
                            catalog.deleteProvider(target.providerId)
                            probes = probes - target.providerId
                            notice = "Провайдер удалён. Автоматической подмены модели не будет."
                            pendingDelete = null
                            reload()
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            error = (exception as? GalaxyApiException)?.message
                                ?: "Не удалось удалить провайдера."
                        } finally {
                            isSaving = false
                        }
                    }
                }) { Text("УДАЛИТЬ") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("ОТМЕНА") } }
        )
    }
}
