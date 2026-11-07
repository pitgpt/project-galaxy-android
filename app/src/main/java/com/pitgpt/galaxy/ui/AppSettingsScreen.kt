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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenMemory: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF080B18)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) {
                Text("‹ НАЗАД", color = Color(0xFFB8D9FF))
            }
            Spacer(Modifier.weight(1f))
            Text("НАСТРОЙКИ", color = Color.White, fontSize = 17.sp)
        }
        Text(
            "Параметры приложения и подключённых сервисов.",
            color = Color(0xFF9AA8C5),
            fontSize = 13.sp
        )
        Button(
            onClick = onOpenMemory,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28375D)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("ПАМЯТЬ", color = Color.White)
                Text("Просмотр, экспорт и удаление записей", color = Color(0xFFB8C7E5), fontSize = 11.sp)
            }
        }
        Button(
            onClick = onOpenProviders,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28375D)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("AI-ПРОВАЙДЕРЫ", color = Color.White)
                Text(
                    "API, модели и ключи доступа",
                    color = Color(0xFFB8C7E5),
                    fontSize = 11.sp
                )
            }
        }
    }
}
