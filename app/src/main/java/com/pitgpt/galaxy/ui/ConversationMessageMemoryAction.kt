package com.pitgpt.galaxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.data.remote.GalaxyApiException
import com.pitgpt.galaxy.data.remote.HttpLilaMemoryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.MessageDigest

internal fun conversationMessageMemoryId(conversationId: String, messageId: String): String {
    require(conversationId.isNotBlank() && messageId.isNotBlank())
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$conversationId|$messageId".toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return "chat-$hex"
}

internal fun conversationMessageMemoryContent(
    message: ConversationMessage,
    characterName: String
): String? {
    val speaker = when (message.role) {
        MessageRole.USER -> "Ты"
        MessageRole.CHARACTER -> characterName.trim().take(64).ifBlank { "Лила" }
        MessageRole.SYSTEM -> return null
    }
    if (message.content.isBlank()) return null
    val content = "$speaker: ${message.content}"
    return content.takeIf { it.length <= MAX_MEMORY_CONTENT_CHARS }
}

@Composable
internal fun ConversationMessageMemoryAction(
    client: HttpLilaMemoryClient,
    conversationId: String,
    message: ConversationMessage,
    characterName: String
) {
    val content = remember(message.id, message.content, message.role, characterName) {
        conversationMessageMemoryContent(message, characterName)
    }
    val scope = rememberCoroutineScope()
    var isSaving by rememberSaveable(message.id) { mutableStateOf(false) }
    var isSaved by rememberSaveable(message.id) { mutableStateOf(false) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(
            enabled = content != null && !isSaving && !isSaved,
            onClick = {
                val memoryContent = content ?: return@TextButton
                scope.launch {
                    isSaving = true
                    error = null
                    try {
                        client.save(
                            conversationMessageMemoryId(conversationId, message.id),
                            memoryContent
                        )
                        isSaved = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        error = (exception as? GalaxyApiException)?.message
                            ?: "Не удалось сохранить сообщение в долговременную память."
                    } finally {
                        isSaving = false
                    }
                }
            }
        ) {
            Text(
                when {
                    isSaving -> "СОХРАНЯЮ В ПАМЯТЬ…"
                    isSaved -> "СОХРАНЕНО В ПАМЯТИ"
                    else -> "СОХРАНИТЬ В ПАМЯТЬ"
                },
                color = if (isSaved) Color(0xFF75E2BD) else Color(0xFFB8D9FF),
                fontSize = 9.sp
            )
        }
        if (content == null) {
            Text(
                "Сообщение превышает лимит памяти. Сохрани сокращённый вариант в настройках.",
                color = Color(0xFFE9BD70),
                fontSize = 9.sp
            )
        }
        error?.let {
            Spacer(Modifier.height(1.dp))
            Text(it, color = Color(0xFFFF9B9B), fontSize = 10.sp)
        }
    }
}

private const val MAX_MEMORY_CONTENT_CHARS = 4_000
