package com.pitgpt.galaxy.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.data.providers.ComfyUiSavedSettings
import com.pitgpt.galaxy.data.providers.EncryptedProviderStore
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiEndpointSelector
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRoutePreference
import kotlinx.coroutines.launch

private val ComfyNight = Color(0xFF080B18)
private val ComfyText = Color.White
private val ComfyMuted = Color(0xFF9AA8C5)
private val ComfyAccent = Color(0xFFB8D9FF)

@Composable
fun ComfyUiSettingsScreen(store: EncryptedProviderStore, onBack: () -> Unit) {
    val initial = remember(store) { runCatching { store.load() } }
    var persistedComfyUi by remember { mutableStateOf(initial.getOrNull()?.comfyUi) }
    var loadFailed by remember { mutableStateOf(initial.isFailure) }
    var lanUrl by remember { mutableStateOf(persistedComfyUi?.lanBaseUrl.orEmpty()) }
    var lanToken by remember { mutableStateOf("") }
    var vpnUrl by remember { mutableStateOf(persistedComfyUi?.vpnBaseUrl.orEmpty()) }
    var vpnToken by remember { mutableStateOf("") }
    var clearLanToken by remember { mutableStateOf(false) }
    var clearVpnToken by remember { mutableStateOf(false) }
    var preference by remember { mutableStateOf(persistedComfyUi?.preference ?: ComfyUiRoutePreference.AUTO) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun candidate(): ComfyUiSavedSettings {
        val lan = lanUrl.trim().takeIf { it.isNotEmpty() }
        val vpn = vpnUrl.trim().takeIf { it.isNotEmpty() }
        val saved = ComfyUiSavedSettings(
            lanBaseUrl = lan,
            lanBearerToken = if (lan == null || clearLanToken) null else lanToken.trim().takeIf { it.isNotEmpty() } ?: persistedComfyUi?.lanBearerToken,
            vpnBaseUrl = vpn,
            vpnBearerToken = if (vpn == null || clearVpnToken) null else vpnToken.trim().takeIf { it.isNotEmpty() } ?: persistedComfyUi?.vpnBearerToken,
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
        if (loadFailed) {
            Text("Не удалось прочитать зашифрованные настройки. Данные не изменены и не перезаписаны.",
                color = Color(0xFFFFB0A8), fontSize = 13.sp)
            Button(onClick = {
                try {
                    val loaded = store.load().comfyUi
                    lanUrl = loaded?.lanBaseUrl.orEmpty()
                    vpnUrl = loaded?.vpnBaseUrl.orEmpty()
                    preference = loaded?.preference ?: ComfyUiRoutePreference.AUTO
                    persistedComfyUi = loaded
                    loadFailed = false
                    status = "Настройки прочитаны."
                } catch (_: Exception) {
                    status = "Хранилище пока недоступно. Исходные данные сохранены."
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ПОВТОРИТЬ ЧТЕНИЕ") }
        } else {
            Text("LAN — адрес компьютера в домашней сети", color = ComfyAccent, fontSize = 13.sp)
            OutlinedTextField(
                value = lanUrl, onValueChange = { lanUrl = it; status = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("LAN URL, например http://192.168.1.20:8188") }
            )
            OutlinedTextField(
                value = lanToken, onValueChange = { lanToken = it; status = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (persistedComfyUi?.lanBearerToken != null) "LAN-токен (пусто — оставить сохранённый)" else "LAN-токен (необязательно)") },
                visualTransformation = PasswordVisualTransformation()
            )
            if (persistedComfyUi?.lanBearerToken != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = clearLanToken, onClick = { clearLanToken = !clearLanToken })
                    Text("Удалить сохранённый LAN-токен", color = ComfyMuted, fontSize = 12.sp)
                }
            }

            Text("VPN / Tailscale — адрес компьютера через VPN", color = ComfyAccent, fontSize = 13.sp)
            OutlinedTextField(
                value = vpnUrl, onValueChange = { vpnUrl = it; status = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("VPN URL, например http://100.x.x.x:8188") }
            )
            OutlinedTextField(
                value = vpnToken, onValueChange = { vpnToken = it; status = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (persistedComfyUi?.vpnBearerToken != null) "VPN-токен (пусто — оставить сохранённый)" else "VPN-токен (необязательно)") },
                visualTransformation = PasswordVisualTransformation()
            )
            if (persistedComfyUi?.vpnBearerToken != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = clearVpnToken, onClick = { clearVpnToken = !clearVpnToken })
                    Text("Удалить сохранённый VPN-токен", color = ComfyMuted, fontSize = 12.sp)
                }
            }

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
                    saving = true
                    try {
                        val next = candidate()
                        val current = store.load()
                        store.save(current.copy(comfyUi = next))
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
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saving) "СОХРАНЕНИЕ…" else "СОХРАНИТЬ НАСТРОЙКИ") }
            status?.let { Text(it, color = ComfyAccent, fontSize = 12.sp, lineHeight = 18.sp) }
            Text(
                "HTTP разрешён только для локальных и частных адресов. Для публичного адреса требуется HTTPS. Перенаправления запросов отключены.",
                color = ComfyMuted, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
    }
}
