package com.pitgpt.galaxy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun clearGeneratedImagePreview(bitmap: ImageBitmap) {
    val androidBitmap = runCatching { bitmap.asAndroidBitmap() }.getOrNull() ?: return
    if (!androidBitmap.isRecycled) {
        runCatching { androidBitmap.eraseColor(android.graphics.Color.TRANSPARENT) }
        runCatching { androidBitmap.recycle() }
    }
}

internal fun generatedImageHistoryForConversation(
    conversationId: String,
    images: List<StoredGeneratedImage>
): List<StoredGeneratedImage> =
    images.asSequence()
        .filter { it.conversationId == conversationId }
        .sortedWith(
            compareByDescending<StoredGeneratedImage> { it.createdAtEpochMillis }
                .thenBy { it.assetId }
        )
        .toList()

@Composable
internal fun GeneratedImageHistory(
    repository: GeneratedImageRepository,
    conversationId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    var images by remember { mutableStateOf<List<StoredGeneratedImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewAssetId by remember { mutableStateOf<String?>(null) }
    var previewLoadingAssetId by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(preview) {
        onDispose { preview?.let(::clearGeneratedImagePreview) }
    }

    LaunchedEffect(repository, conversationId, refreshKey) {
        isLoading = true
        loadError = null
        try {
            images = generatedImageHistoryForConversation(
                conversationId,
                repository.listForConversation(conversationId)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = "Не удалось загрузить историю изображений. Повтори попытку."
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("‹  НАЗАД") }
            Text("ИСТОРИЯ ИЗОБРАЖЕНИЙ", fontSize = 14.sp)
            TextButton(onClick = { refreshKey++ }, enabled = !isLoading) { Text("ОБНОВИТЬ") }
        }

        preview?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Предпросмотр сохранённого изображения",
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                contentScale = ContentScale.Fit
            )
            TextButton(onClick = {
                preview = null
                previewAssetId = null
                previewError = null
            }) {
                Text("ЗАКРЫТЬ ПРЕДПРОСМОТР")
            }
        }
        previewError?.let { Text(it, fontSize = 11.sp) }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading) {
                item { Text("Загружаю зашифрованную историю…", fontSize = 12.sp) }
            }
            if (loadError != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(loadError.orEmpty(), fontSize = 12.sp)
                        Button(onClick = { refreshKey++ }, enabled = !isLoading) {
                            Text("ПОВТОРИТЬ")
                        }
                    }
                }
            }
            if (!isLoading && loadError == null && images.isEmpty()) {
                item { Text("Сохранённых изображений пока нет.", fontSize = 12.sp) }
            }
            items(images, key = { it.assetId }) { asset ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(androidx.compose.ui.graphics.Color(0x221B2B4A))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(asset.createdAtEpochMillis)),
                        fontSize = 11.sp
                    )
                    Text(
                        "${asset.width} × ${asset.height} · ${asset.mimeType} · ${asset.byteSize / 1024} KiB",
                        fontSize = 10.sp
                    )
                    Button(
                        enabled = previewLoadingAssetId == null,
                        onClick = {
                            preview = null
                            previewAssetId = asset.assetId
                            previewError = null
                            previewLoadingAssetId = asset.assetId
                            scope.launch {
                                try {
                                    // read() decrypts only into memory; the decoder clears the
                                    // plaintext byte array in its outer finally block.
                                    val plaintext = repository.read(asset.assetId)
                                    val decoded = decodeGeneratedImageAndClearBytes(plaintext)
                                    if (previewAssetId == asset.assetId) {
                                        preview = decoded
                                        if (decoded == null) {
                                            previewError = "Не удалось открыть сохранённое изображение."
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (previewAssetId == asset.assetId) {
                                        previewError = "Не удалось расшифровать изображение. Исходный файл не изменён."
                                    }
                                } finally {
                                    if (previewLoadingAssetId == asset.assetId) {
                                        previewLoadingAssetId = null
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (previewLoadingAssetId == asset.assetId) "ОТКРЫВАЮ…" else "ОТКРЫТЬ")
                    }
                }
            }
        }
    }
}
