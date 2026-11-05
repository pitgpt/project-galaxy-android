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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF080B18)
private val Muted = Color(0xFF9AA8C5)
private val Ice = Color(0xFFB8D9FF)

@Composable
fun GalaxyHome() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF111A35), Night, Color(0xFF100D25))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PROJECT",
                        color = Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = "GALAXY",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x2227D7A0))
                        .border(1.dp, Color(0x5546D9AC), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "●  SYSTEM ONLINE",
                        color = Color(0xFF75E2BD),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier.size(238.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .border(1.dp, Color(0x447C8DDB), CircleShape)
                        .padding(13.dp)
                        .border(1.dp, Color(0x337FCBFF), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(174.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF7D83E8),
                                    Color(0xFF394A91),
                                    Color(0xFF172448),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFE2F4FF),
                                    Ice,
                                    Color(0xFF7589D8),
                                    Color(0xFF28366D)
                                )
                            ),
                            CircleShape
                        )
                        .border(1.dp, Color(0x99D8EEFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "G",
                        color = Color(0xFF172448),
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Light
                    )
                }
            }

            Spacer(modifier = Modifier.height(34.dp))
            Text(
                text = "Я рядом.",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Твоё личное пространство\\nдля мыслей, идей и разговоров.",
                color = Muted,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xAA18213B))
                    .border(1.dp, Color(0x334F6598), RoundedCornerShape(22.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatusLine("Приложение", "Базовая оболочка работает", Color(0xFF65DDB2))
                StatusLine("Голосовой модуль", "Следующий этап разработки", Color(0xFFE9BD70))
                StatusLine("Память и персонализация", "Будут подключены позже", Color(0xFF8EA8F5))
            }

            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "MADE FOR ONE UNIVERSE",
                color = Color(0x667F91B8),
                fontSize = 9.sp,
                letterSpacing = 2.5.sp
            )
        }
    }
}

@Composable
private fun StatusLine(title: String, detail: String, dot: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dot, CircleShape)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(text = detail, color = Muted, fontSize = 11.sp)
        }
    }
}
