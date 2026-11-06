package com.pitgpt.galaxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.data.auth.SupabaseAuthService
import kotlinx.coroutines.launch

private val AuthNight = Color(0xFF080B18)
private val AuthMuted = Color(0xFF9AA8C5)
private val AuthIce = Color(0xFFB8D9FF)

@Composable
fun GalaxyAuthGate(auth: SupabaseAuthService?, content: @Composable () -> Unit) {
    if (auth == null) {
        content()
        return
    }

    var initialized by remember { mutableStateOf(false) }
    var authenticated by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(auth) {
        checking = true
        runCatching {
            auth.initialize()
            authenticated = !auth.accessToken().isNullOrBlank()
        }
        initialized = true
        checking = false
    }

    when {
        checking || !initialized -> AuthLoadingScreen()
        authenticated -> {
            Box(Modifier.fillMaxSize()) {
                content()
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { auth.signOut() }
                            authenticated = false
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                ) { Text("ВЫЙТИ", color = AuthIce, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
        else -> GalaxyAuthScreen(
            auth = auth,
            onAuthenticated = { authenticated = true }
        )
    }
}

@Composable
private fun AuthLoadingScreen() {
    Box(
        Modifier.fillMaxSize().background(AuthNight),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = AuthIce)
    }
}

@Composable
private fun GalaxyAuthScreen(
    auth: SupabaseAuthService,
    onAuthenticated: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF111A35), AuthNight, Color(0xFF100D25)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("PROJECT", color = AuthMuted, fontSize = 11.sp, letterSpacing = 4.sp)
            Text("GALAXY", color = Color.White, fontSize = 30.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                if (registering) "СОЗДАНИЕ АККАУНТА" else "ВОЙТИ В АККАУНТ",
                color = AuthIce, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; message = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedLabelColor = AuthIce, unfocusedLabelColor = AuthMuted,
                    focusedBorderColor = AuthIce, unfocusedBorderColor = Color(0xFF536A9E)
                )
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; message = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedLabelColor = AuthIce, unfocusedLabelColor = AuthMuted,
                    focusedBorderColor = AuthIce, unfocusedBorderColor = Color(0xFF536A9E)
                )
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    val cleanEmail = email.trim()
                    if (!cleanEmail.contains("@") || password.isBlank()) {
                        message = "Проверь email и введи пароль."
                        isError = true
                    } else {
                        scope.launch {
                            busy = true
                            message = null
                            try {
                                if (registering) {
                                    auth.signUp(cleanEmail, password)
                                    val token = auth.accessToken()
                                    if (token.isNullOrBlank()) {
                                        message = "Аккаунт создан. Подтверди email по ссылке из письма, затем вернись и войди."
                                        isError = false
                                    } else {
                                        onAuthenticated()
                                    }
                                } else {
                                    auth.signIn(cleanEmail, password)
                                    if (auth.accessToken().isNullOrBlank()) {
                                        message = "Вход не завершён. Проверь подтверждение email и попробуй снова."
                                        isError = true
                                    } else {
                                        onAuthenticated()
                                    }
                                }
                            } catch (error: Exception) {
                                message = if (registering) {
                                    "Не удалось создать аккаунт. Проверь данные и настройки Supabase."
                                } else {
                                    "Не удалось войти. Проверь email, пароль и подтверждение почты."
                                }
                                isError = true
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6578D8)),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (busy) CircularProgressIndicator(color = Color.White)
                else Text(if (registering) "СОЗДАТЬ АККАУНТ" else "ВОЙТИ", fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = { registering = !registering; message = null },
                enabled = !busy
            ) {
                Text(
                    if (registering) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться",
                    color = AuthIce
                )
            }
            message?.let {
                Text(
                    it,
                    color = if (isError) Color(0xFFFFA0A0) else Color(0xFF9DE0C5),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Сессия хранится в зашифрованном хранилище устройства.",
                color = AuthMuted, fontSize = 10.sp
            )
        }
    }
}
