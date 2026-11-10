package com.pitgpt.galaxy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed class ConversationTimelineEntry(
    val key: String,
    val createdAtEpochMillis: Long
) {
    data class Message(val value: ConversationMessage) : ConversationTimelineEntry(
        key = "message:${value.id}",
        createdAtEpochMillis = value.createdAtEpochMillis
    )

    data class GeneratedImage(val value: StoredGeneratedImage) : ConversationTimelineEntry(
        key = "image:${value.assetId}",
        createdAtEpochMillis = value.createdAtEpochMillis
    )
}

internal fun conversationTimelineEntries(
    characterId: String,
    messages: List<ConversationMessage>,
    images: List<StoredGeneratedImage>
): List<ConversationTimelineEntry> {
    val entries = buildList {
        messages.asSequence()
            .filter { it.characterId == characterId }
            .forEach { add(ConversationTimelineEntry.Message(it)) }
        images.asSequence()
            .filter { it.characterId == characterId && it.conversationId == characterId }
            .forEach { add(ConversationTimelineEntry.GeneratedImage(it)) }
    }
    return entries.sortedWith(
        compareBy<ConversationTimelineEntry> { it.createdAtEpochMillis }
            .thenBy { if (it is ConversationTimelineEntry.Message) 0 else 1 }
            .thenBy { it.key }
    )
}

@Composable
internal fun GeneratedImageTimelineCard(
    repository: GeneratedImageRepository,
    asset: StoredGeneratedImage,
    modifier: Modifier = Modifier
) {
    var preview by remember(asset.assetId) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(asset.assetId) { mutableStateOf(false) }
    var error by remember(asset.assetId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(preview) {
        onDispose { preview?.let(::clearGeneratedImagePreview) }
    }

    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Color(0x332C3D68))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("СОХРАНЁННЫЙ ОБРАЗ", fontSize = 9.sp, color = androidx.compose.ui.graphics.Color(0xFFB8D9FF))
            Text(
                DateFormat.getDateTimeInstance().format(Date(asset.createdAtEpochMillis)),
                fontSize = 9.sp
            )
        }
        Text(
            "${asset.width} × ${asset.height} · ${asset.mimeType} · ${asset.byteSize / 1024} KiB",
            fontSize = 10.sp
        )
        preview?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Изображение из истории разговора",
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                contentScale = ContentScale.Fit
            )
            TextButton(onClick = { preview = null; error = null }) {
                Text("ЗАКРЫТЬ ПРЕДПРОСМОТР")
            }
        } ?: Button(
            enabled = !isLoading,
            onClick = {
                isLoading = true
                error = null
                scope.launch {
                    try {
                        val plaintext = repository.read(asset.assetId)
                        val decoded = decodeGeneratedImageAndClearBytes(plaintext)
                        if (decoded == null) {
                            error = "Не удалось открыть сохранённое изображение."
                        } else {
                            preview = decoded
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "Не удалось расшифровать сохранённое изображение."
                    } finally {
                        isLoading = false
                    }
                }
            }
        ) {
            Text(if (isLoading) "ОТКРЫВАЮ…" else "ОТКРЫТЬ ИЗОБРАЖЕНИЕ")
        }
        error?.let { Text(it, fontSize = 10.sp, color = androidx.compose.ui.graphics.Color(0xFFFFBD88)) }
    }
}
