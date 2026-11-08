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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.data.providers.ComfyUiSavedSettings
import com.pitgpt.galaxy.data.providers.EncryptedProviderStore
import com.pitgpt.galaxy.data.providers.ProviderSettings
import com.pitgpt.galaxy.data.providers.ProviderSettingsIo
import com.pitgpt.galaxy.data.providers.resolveComfyUiBearerToken
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiEndpointSelector
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRoutePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ComfyNight = Color(0xFF080B18)
private val ComfyText = Color.White
private val ComfyMuted = Color(0xFF9AA8C5)
private val ComfyAccent = Color(0xFFB8D9FF)

@Composable
fun ComfyUiSettingsScreen(store: EncryptedProviderStore, onBack: () -> Unit) {
    val settingsIo = remember(store) { ProviderSettingsIo(store) }
    val context = LocalContext.current
    var persistedComfyUi by remember { mutableStateOf<ComfyUiSavedSettings?>(null) }
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
    var lanUrl by remember { mutableStateOf("") }
    var lanToken by remember { mutableStateOf("") }
    var vpnUrl by remember { mutableStateOf("") }
    var vpnToken by remember { mutableStateOf("") }
    var clearLanToken by remember { mutableStateOf(false) }
    var clearVpnToken by remember { mutableStateOf(false) }
    var preference by remember { mutableStateOf(ComfyUiRoutePreference.AUTO) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var importingWorkflow by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val workflowPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importingWorkflow = true
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Workflow file cannot be opened")
                        readBoundedWorkflowJson(input)
                    }
                    workflowJson = imported
                    if (workflowId.isBlank()) workflowId = "comfyui.workflow.v1"
                    status = "Workflow JSON загружен. Укажи ID узлов и входов, затем сохрани шаблон."
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

    fun applyLoadedSettings(loaded: ProviderSettings) {
        val comfy = loaded.comfyUi
        persistedComfyUi = comfy
        lanUrl = comfy?.lanBaseUrl.orEmpty()
        vpnUrl = comfy?.vpnBaseUrl.orEmpty()
        preference = comfy?.preference ?: ComfyUiRoutePreference.AUTO
        val workflow = loaded.comfyUiWorkflow
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
        lanToken = ""
        vpnToken = ""
        clearLanToken = false
        clearVpnToken = false
        loadFailed = false
    }

    LaunchedEffect(settingsIo) {
        try {
            applyLoadedSettings(settingsIo.load())
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

    fun candidate(): ComfyUiSavedSettings {
        val lan = lanUrl.trim().takeIf { it.isNotEmpty() }
        val vpn = vpnUrl.trim().takeIf { it.isNotEmpty() }
        val saved = ComfyUiSavedSettings(
            lanBaseUrl = lan,
            lanBearerToken = resolveComfyUiBearerToken(
                endpoint = lan,
                enteredToken = lanToken,
                clearSavedToken = clearLanToken,
                previousEndpoint = persistedComfyUi?.lanBaseUrl,
                previousToken = persistedComfyUi?.lanBearerToken
            ),
            vpnBaseUrl = vpn,
            vpnBearerToken = resolveComfyUiBearerToken(
                endpoint = vpn,
                enteredToken = vpnToken,
                clearSavedToken = clearVpnToken,
                previousEndpoint = persistedComfyUi?.vpnBaseUrl,
                previousToken = persistedComfyUi?.vpnBearerToken
            ),
            preference = preference
        )
        saved.toEndpointOptions()
        return saved
    }

    Column(
        Modifier.fillMaxSize().background(ComfyNight).verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ НАЗАД", color = ComfyAccent) }
            Spacer(Modifier.weight(1f))
            Text("COMFYUI", color = ComfyText, fontSize = 17.sp)
        }
        Text(
            "Подключение к ComfyUI на домашнем компьютере. Адреса и токены сохраняются в зашифрованном хранилище Android Keystore.",
            color = ComfyMuted, fontSize = 12.sp, lineHeight = 18.sp
        )
        when {
            loading -> {
                Text("Читаю зашифрованные настройки…", color = ComfyMuted, fontSize = 13.sp)
            }
            loadFailed -> {
                Text("Не удалось прочитать зашифрованные настройки. Данные не изменены и не перезаписаны.",
                    color = Color(0xFFFFB0A8), fontSize = 13.sp)
                Button(
                    enabled = !saving && !checking,
                    onClick = {
                        loading = true
                        scope.launch {
                            try {
                                applyLoadedSettings(settingsIo.load())
                                status = "Настройки прочитаны."
                            } catch (_: Exception) {
                                loadFailed = true
                                status = "Хранилище пока недоступно. Исходные данные сохранены."
                            } finally {
                                loading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ПОВТОРИТЬ ЧТЕНИЕ") }
            }
            else -> {
                Text("LAN — адрес компьютера в домашней сети", color = ComfyAccent, fontSize = 13.sp)
                OutlinedTextField(
                    value = lanUrl, onValueChange = { lanUrl = it; status = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("LAN URL, например http://192.168.1.20:8188") }
                )
                OutlinedTextField(
                    value = lanToken, onValueChange = { lanToken = it; status = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (persistedComfyUi?.lanBearerToken != null) "LAN-токен (пусто — сохранить только для прежнего адреса)" else "LAN-токен (необязательно)") },
                    visualTransformation = PasswordVisualTransformation()
                )
                if (persistedComfyUi?.lanBearerToken != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearLanToken, onCheckedChange = { clearLanToken = it })
                        Text("Удалить сохранённый LAN-токен", color = ComfyMuted, fontSize = 12.sp)
                    }
                }

                Text("VPN / Tailscale — адрес компьютера через VPN", color = ComfyAccent, fontSize = 13.sp)
                OutlinedTextField(
                    value = vpnUrl, onValueChange = { vpnUrl = it; status = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("VPN URL, например http://100.64.0.10:8188") }
                )
                OutlinedTextField(
                    value = vpnToken, onValueChange = { vpnToken = it; status = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (persistedComfyUi?.vpnBearerToken != null) "VPN-токен (пусто — сохранить только для прежнего адреса)" else "VPN-токен (необязательно)") },
                    visualTransformation = PasswordVisualTransformation()
                )
                if (persistedComfyUi?.vpnBearerToken != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearVpnToken, onCheckedChange = { clearVpnToken = it })
                        Text("Удалить сохранённый VPN-токен", color = ComfyMuted, fontSize = 12.sp)
                    }
                }


                Text("ШАБЛОН WORKFLOW", color = ComfyAccent, fontSize = 13.sp)
                Text(
                    "Импортируй API-format JSON из ComfyUI. Galaxy не угадывает узлы: укажи ID узла и имя входа для каждого поля.",
                    color = ComfyMuted, fontSize = 11.sp, lineHeight = 16.sp
                )
                Button(
                    enabled = !saving && !checking && !importingWorkflow,
                    onClick = { workflowPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (importingWorkflow) "ЧИТАЮ WORKFLOW…" else if (workflowJson.isBlank()) "ИМПОРТИРОВАТЬ WORKFLOW JSON" else "ЗАМЕНИТЬ WORKFLOW JSON") }
                Text(
                    if (workflowJson.isBlank()) "Workflow пока не импортирован."
                    else "JSON загружен и готов к настройке. Содержимое хранится в зашифрованном хранилище.",
                    color = ComfyMuted, fontSize = 11.sp
                )
                OutlinedTextField(value = workflowId, onValueChange = { workflowId = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("ID шаблона") })
                OutlinedTextField(value = positiveNodeId, onValueChange = { positiveNodeId = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Positive prompt · ID узла") })
                OutlinedTextField(value = positiveInputName, onValueChange = { positiveInputName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Positive prompt · имя входа") })
                OutlinedTextField(value = negativeNodeId, onValueChange = { negativeNodeId = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Negative prompt · ID узла (необязательно)") })
                OutlinedTextField(value = negativeInputName, onValueChange = { negativeInputName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Negative prompt · имя входа") })
                OutlinedTextField(value = widthNodeId, onValueChange = { widthNodeId = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Width · ID узла (необязательно)") })
                OutlinedTextField(value = widthInputName, onValueChange = { widthInputName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Width · имя входа") })
                OutlinedTextField(value = heightNodeId, onValueChange = { heightNodeId = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Height · ID узла (необязательно)") })
                OutlinedTextField(value = heightInputName, onValueChange = { heightInputName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Height · имя входа") })
                OutlinedTextField(value = seedNodeId, onValueChange = { seedNodeId = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Seed · ID узла (необязательно)") })
                OutlinedTextField(value = seedInputName, onValueChange = { seedInputName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Seed · имя входа") })
                Button(
                    enabled = !saving && !checking && !loading && !loadFailed && !importingWorkflow,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                val template = try {
                                    buildComfyUiWorkflowTemplate(
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
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }

                                if (template == null) {
                                    status = "Проверь JSON, ID шаблона и соответствие ID узлов/имён входов."
                                } else {
                                    val current = settingsIo.load()
                                    settingsIo.save(current.copy(comfyUiWorkflow = template))
                                    status = "Workflow сохранён в зашифрованном хранилище."
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "Не удалось сохранить workflow. Проверь Android Keystore."
                            } finally {
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("СОХРАНИТЬ WORKFLOW") }

                Text("Предпочитаемый маршрут", color = ComfyAccent, fontSize = 13.sp)
                ComfyUiRoutePreference.values().forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = preference == option, onClick = { preference = option })
                        Text(
                            when (option) {
                                ComfyUiRoutePreference.AUTO -> "Автоматически: сначала LAN, затем VPN"
                                ComfyUiRoutePreference.LAN_ONLY -> "Только LAN"
                                ComfyUiRoutePreference.VPN_ONLY -> "Только VPN"
                            },
                            color = ComfyText, fontSize = 12.sp
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    enabled = !checking && !saving,
                    onClick = {
                        checking = true
                        status = "Проверяю подключение…"
                        scope.launch {
                            status = try {
                                val resolution = ComfyUiEndpointSelector().resolve(candidate().toEndpointOptions())
                                resolution.attempts.joinToString(separator = "\n") {
                                    "${it.route}: ${it.message}"
                                }.ifBlank { "Не удалось проверить подключение." }
                            } catch (_: Exception) {
                                "Проверь адреса, режим маршрута и токены. Секреты не были выведены."
                            }
                            checking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (checking) "ПРОВЕРКА…" else "ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ") }

                Button(
                    enabled = !saving && !checking,
                    onClick = {
                        val next = runCatching { candidate() }.getOrNull()
                        if (next == null) {
                            status = "Проверь адреса и выбранный режим маршрута."
                        } else {
                            saving = true
                            scope.launch {
                                try {
                                    val current = settingsIo.load()
                                    settingsIo.save(current.copy(comfyUi = next))
                                    persistedComfyUi = next
                                    status = "Настройки ComfyUI сохранены в зашифрованном хранилище."
                                    lanToken = ""
                                    vpnToken = ""
                                    clearLanToken = false
                                    clearVpnToken = false
                                } catch (_: Exception) {
                                    status = "Не удалось сохранить настройки. Проверь адреса и доступность Android Keystore."
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (saving) "СОХРАНЕНИЕ…" else "СОХРАНИТЬ НАСТРОЙКИ") }
                status?.let { Text(it, color = ComfyAccent, fontSize = 12.sp, lineHeight = 18.sp) }
                Text(
                    "Публичные адреса требуют HTTPS. Android может блокировать обычный HTTP даже для частных адресов; глобальное разрешение cleartext traffic не включено. Перенаправления запросов отключены.",
                    color = ComfyMuted, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }
    }
}


