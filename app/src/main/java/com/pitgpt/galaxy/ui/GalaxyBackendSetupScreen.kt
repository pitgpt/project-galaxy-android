package com.pitgpt.galaxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.data.auth.GalaxyBackendConfiguration

private val SetupText = Color(0xFFB8D9FF)
private val SetupMuted = Color(0xFF9AA8C5)

@Composable
fun GalaxyBackendSetupScreen(
    recoveryRequired: Boolean,
    onSave: (GalaxyBackendConfiguration) -> Unit,
    onReset: () -> Unit
) {
    var galaxyApiUrl by remember { mutableStateOf("") }
    var supabaseUrl by remember { mutableStateOf("") }
    var publishableKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "ПОДКЛЮЧЕНИЕ GALAXY",
            color = SetupMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Настроим связь с Лилой",
            color = SetupText,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Укажи HTTPS-адрес своего Galaxy backend и публичную конфигурацию Supabase. " +
                "После сохранения приложение сможет авторизоваться и открыть каталог моделей.",
            color = SetupMuted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(18.dp))

        if (recoveryRequired) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Сохранённая конфигурация не может быть расшифрована текущим Android Keystore. " +
                            "Её ciphertext сохранён. Если ключ был сброшен системой, введи параметры заново.",
                        color = SetupMuted,
                        fontSize = 12.sp
                    )
                    TextButton(onClick = onReset) {
                        Text("СБРОСИТЬ НЕДОСТУПНУЮ КОНФИГУРАЦИЮ")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = galaxyApiUrl,
            onValueChange = { galaxyApiUrl = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Galaxy API base URL") },
            placeholder = { Text("https://api.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = supabaseUrl,
            onValueChange = { supabaseUrl = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Supabase project URL") },
            placeholder = { Text("https://project.supabase.co") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = publishableKey,
            onValueChange = { publishableKey = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Supabase publishable / anon key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Используй только publishable/anon key. Service-role key и API keys языковых моделей сюда вводить нельзя.",
            color = SetupMuted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(14.dp))
        error?.let {
            Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val configuration = try {
                    GalaxyBackendConfiguration(
                        galaxyApiBaseUrl = galaxyApiUrl,
                        supabaseUrl = supabaseUrl,
                        supabasePublishableKey = publishableKey
                    ).validated()
                } catch (failure: IllegalArgumentException) {
                    error = failure.message ?: "Проверь адреса и публичный ключ."
                    return@Button
                }
                try {
                    onSave(configuration)
                    error = null
                    publishableKey = ""
                } catch (_: Exception) {
                    error = "Не удалось безопасно сохранить конфигурацию. Проверь состояние Android Keystore и повтори."
                }
            }
        ) {
            Text("СОХРАНИТЬ И ПРОДОЛЖИТЬ")
        }
    }
}
