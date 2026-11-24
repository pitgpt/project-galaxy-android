package com.pitgpt.galaxy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.data.remote.LilaComfyUiProviderConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ComfyNight = Color(0xFF080B18)
private val ComfyText = Color.White
private val ComfyMuted = Color(0xFF9AA8C5)
private val ComfyAccent = Color(0xFFB8D9FF)

@Composable
fun ComfyUiSettingsScreen(app: GalaxyApplication, onBack: () -> Unit) {
    val provider = remember(app) { app.lilaCoreImageGenerationProvider }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var persistedBaseUrl by remember { mutableStateOf<String?>(null) }
    var bearerToken by remember { mutableStateOf("") }
    var hasBearerToken by remember { mutableStateOf(false) }
    var clearBearerToken by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }
    var workflowId by remember { mutableStateOf("") }
    var workflowJson by remember { mutableStateOf("") }
    var positiveNodeId by remember { mutableStateOf("") }
    var positiveInputName by remember { mutableStateOf("text") }
    var negativeNodeId by remember { mutableStateOf("") }
    var negativeInputName by remember { mutableStateOf("text") }
    var widthNodeId by remember { mutableStateOf("") }
    var widthInputName by remember { mutableStateOf("width") }
    var heightNodeId by remember { mutableStateOf("") }
    var heightInputName by remember { mutableStateOf("height") }
    var seedNodeId by remember { mutableStateOf("") }
    var seedInputName by remember { mutableStateOf("seed") }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var importingWorkflow by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun applyConfiguration(configuration: LilaComfyUiProviderConfiguration) {
        configured = configuration.configured
        persistedBaseUrl = configuration.baseUrl
        baseUrl = configuration.baseUrl.orEmpty()
        hasBearerToken = configuration.hasBearerToken
        bearerToken = ""
        clearBearerToken = false
        val workflow = configuration.workflow
        workflowId = workflow?.id.orEmpty()
        workflowJson = workflow?.workflowJson.orEmpty()
        positiveNodeId = workflow?.positivePrompt?.nodeId.orEmpty()
        positiveInputName = workflow?.positivePrompt?.inputName ?: "text"
        negativeNodeId = workflow?.negativePrompt?.nodeId.orEmpty()
        negativeInputName = workflow?.negativePrompt?.inputName ?: "text"
        widthNodeId = workflow?.width?.nodeId.orEmpty()
        widthInputName = workflow?.width?.inputName ?: "width"
        heightNodeId = workflow?.height?.nodeId.orEmpty()
        heightInputName = workflow?.height?.inputName ?: "height"
        seedNodeId = workflow?.seed?.nodeId.orEmpty()
        seedInputName = workflow?.seed?.inputName ?: "seed"
        loadFailed = false
    }

    suspend fun loadConfiguration() {
        try {
            val configuration = provider.providerConfiguration()
            applyConfiguration(configuration)
            status = if (configuration.configured) {
                "Настройки загружены из защищённого хранилища Lila Core."
            } else {
                "ComfyUI ещё не настроен в Lila Core."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
            status = "Не удалось прочитать конфигурацию Lila Core. Сохранённые данные не изменены."
        }
    }

    val workflowPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importingWorkflow = true
                try {
                    workflowJson = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Workflow file cannot be opened")
                        readBoundedWorkflowJson(input)
                    }
                    configured = false
                    if (workflowId.isBlank()) workflowId = "comfyui.workflow.v1"
                    status = "Workflow JSON загружен. Укажи ID узлов и входов, затем сохрани конфигурацию."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = "Не удалось прочитать workflow. Файл должен быть JSON размером не более 2 МиБ."
                } finally {
                    importingWorkflow = false
                }
            }
        }
    }

    LaunchedEffect(provider) {
        try {
            loadConfiguration()
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(ComfyNight).verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ НАЗАД", color = ComfyAccent) }
            Spacer(Modifier.weight(1f))
            Text("COMFYUI · LILA CORE", color = ComfyText, fontSize = 16.sp)
        }
        Text(
            "Настройки и секреты хранятся в защищённом хранилище Lila Core. Android обращается к Core по HTTPS; сам Core подключается к ComfyUI на домашнем компьютере.",
            color = ComfyMuted, fontSize = 12.sp, lineHeight = 18.sp
        )
        Text(
            "Для backend в Docker обычно используется http://host.docker.internal:8188. Разрешён только локальный endpoint на порту 8188; публичные адреса и произвольный LAN egress отклоняются.",
            color = ComfyMuted, fontSize = 11.sp, lineHeight = 16.sp
        )

        when {
            loading -> Text("Читаю конфигурацию Lila Core…", color = ComfyMuted, fontSize = 13.sp)
            loadFailed -> {
                Text(
                    "Конфигурация не прочитана. Изменения заблокированы, чтобы не перезаписать существующий workflow.",
                    color = Color(0xFFFFB0A8), fontSize = 13.sp
                )
                Button(
                    enabled = !saving && !checking,
                    onClick = {
                        loading = true
                        scope.launch {
                            try { loadConfiguration() } finally { loading = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ПОВТОРИТЬ ЧТЕНИЕ") }
            }
            else -> {
                Text("ENDPOINT COMFYUI", color = ComfyAccent, fontSize = 13.sp)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; configured = false; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Backend URL, например http://host.docker.internal:8188") }
                )
                OutlinedTextField(
                    value = bearerToken,
                    onValueChange = { bearerToken = it; clearBearerToken = false; configured = false; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(if (hasBearerToken) "Новый токен (пусто — сохранить прежний для того же адреса)" else "Bearer token (необязательно)")
                    },
                    visualTransformation = PasswordVisualTransformation()
                )
                if (hasBearerToken) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = clearBearerToken,
                            onCheckedChange = { clearBearerToken = it; if (it) bearerToken = ""; configured = false }
                        )
                        Text("Удалить сохранённый токен", color = ComfyMuted, fontSize = 12.sp)
                    }
                }
                if (hasBearerToken && persistedBaseUrl != null && persistedBaseUrl != baseUrl.trim() && bearerToken.isBlank()) {
                    Text(
                        "При смене endpoint Core удалит прежний токен. Введи новый токен, если он нужен новому адресу.",
                        color = Color(0xFFE9BD70), fontSize = 11.sp, lineHeight = 16.sp
                    )
                }

                Text("ШАБЛОН WORKFLOW", color = ComfyAccent, fontSize = 13.sp)
                Text(
                    "Импортируй API-format JSON из ComfyUI. Galaxy не угадывает узлы: явно укажи ID узла и имя входа для каждого поля.",
                    color = ComfyMuted, fontSize = 11.sp, lineHeight = 16.sp
                )
                Button(
                    enabled = !saving && !checking && !importingWorkflow,
                    onClick = { workflowPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (importingWorkflow) "ЧИТАЮ WORKFLOW…" else if (workflowJson.isBlank()) "ИМПОРТИРОВАТЬ WORKFLOW JSON" else "ЗАМЕНИТЬ WORKFLOW JSON")
                }
                Text(
                    if (workflowJson.isBlank()) "Workflow пока не импортирован."
                    else "Workflow загружен и готов к проверке. Полный JSON передаётся только в Lila Core.",
                    color = ComfyMuted, fontSize = 11.sp
                )
                OutlinedTextField(value = workflowId, onValueChange = { workflowId = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("ID шаблона") })
                OutlinedTextField(value = positiveNodeId, onValueChange = { positiveNodeId = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Positive prompt · ID узла") })
                OutlinedTextField(value = positiveInputName, onValueChange = { positiveInputName = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Positive prompt · имя входа") })
                OutlinedTextField(value = negativeNodeId, onValueChange = { negativeNodeId = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Negative prompt · ID узла (необязательно)") })
                OutlinedTextField(value = negativeInputName, onValueChange = { negativeInputName = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Negative prompt · имя входа") })
                OutlinedTextField(value = widthNodeId, onValueChange = { widthNodeId = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Width · ID узла (необязательно)") })
                OutlinedTextField(value = widthInputName, onValueChange = { widthInputName = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Width · имя входа") })
                OutlinedTextField(value = heightNodeId, onValueChange = { heightNodeId = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Height · ID узла (необязательно)") })
                OutlinedTextField(value = heightInputName, onValueChange = { heightInputName = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Height · имя входа") })
                OutlinedTextField(value = seedNodeId, onValueChange = { seedNodeId = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Seed · ID узла (необязательно)") })
                OutlinedTextField(value = seedInputName, onValueChange = { seedInputName = it; configured = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Seed · имя входа") })

                Button(
                    enabled = !saving && !checking && !importingWorkflow && baseUrl.isNotBlank() && workflowJson.isNotBlank(),
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                val template = buildComfyUiWorkflowTemplate(
                                    id = workflowId,
                                    workflowJson = workflowJson,
                                    positiveNodeId = positiveNodeId,
                                    positiveInputName = positiveInputName,
                                    negativeNodeId = negativeNodeId,
                                    negativeInputName = negativeInputName,
                                    widthNodeId = widthNodeId,
                                    widthInputName = widthInputName,
                                    heightNodeId = heightNodeId,
                                    heightInputName = heightInputName,
                                    seedNodeId = seedNodeId,
                                    seedInputName = seedInputName
                                )
                                val oldUrl = persistedBaseUrl
                                val oldHadToken = hasBearerToken
                                val enteredToken = bearerToken.trim().takeIf { it.isNotEmpty() }
                                val result = provider.configureProvider(
                                    baseUrl = baseUrl,
                                    workflow = template,
                                    bearerToken = enteredToken,
                                    clearBearerToken = clearBearerToken
                                )
                                configured = result.configured
                                hasBearerToken = result.hasBearerToken
                                persistedBaseUrl = baseUrl.trim()
                                bearerToken = ""
                                clearBearerToken = false
                                status = if (oldHadToken && oldUrl != null && oldUrl != baseUrl.trim() && enteredToken == null) {
                                    "Настройки сохранены. Старый токен удалён при смене endpoint."
                                } else {
                                    "Конфигурация сохранена в защищённом хранилище Lila Core."
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "Не удалось сохранить конфигурацию. Проверь workflow, endpoint и доступность Lila Core."
                            } finally {
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (saving) "СОХРАНЕНИЕ…" else "СОХРАНИТЬ В LILA CORE") }

                Text(
                    "Проверка выполняется по последней сохранённой конфигурации. Сначала сохрани изменения.",
                    color = ComfyMuted, fontSize = 11.sp
                )
                Button(
                    enabled = !saving && !checking && !loadFailed && configured,
                    onClick = {
                        checking = true
                        status = "Проверяю соединение через Lila Core…"
                        scope.launch {
                            try {
                                val result = provider.testProvider()
                                status = if (result.connectionReachable) {
                                    "ComfyUI доступен через Core; workflow: " + (result.workflowId ?: "неизвестен") + "."
                                } else {
                                    "Core не подтвердил соединение с ComfyUI."
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "Проверка ComfyUI не прошла. Проверь адрес, workflow и доступность backend."
                            } finally {
                                checking = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (checking) "ПРОВЕРКА…" else "ПРОВЕРИТЬ СОХРАНЁННУЮ КОНФИГУРАЦИЮ") }

                status?.let { Text(it, color = ComfyAccent, fontSize = 12.sp, lineHeight = 18.sp) }
            }
        }
    }
}
