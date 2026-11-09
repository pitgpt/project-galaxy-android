package com.pitgpt.galaxy.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.model.ImageGenerationStatus
import com.pitgpt.galaxy.core.usecase.CancelImageGeneration
import com.pitgpt.galaxy.core.usecase.GetImageGenerationStatus
import com.pitgpt.galaxy.core.usecase.SubmitImageGeneration
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRuntime
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiSubmissionUncertainException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun imageGenerationNavigationLocked(isSubmitting: Boolean, hasActiveJob: Boolean): Boolean =
    isSubmitting || hasActiveJob

internal fun imageGenerationStatusLabel(state: ImageGenerationState): String =
    when (state) {
        ImageGenerationState.QUEUED -> "В очереди"
        ImageGenerationState.RUNNING -> "Выполняется"
        ImageGenerationState.COMPLETED -> "Готово"
        ImageGenerationState.FAILED -> "Ошибка генерации"
        ImageGenerationState.CANCELLED -> "Отменено"
    }

internal fun isTerminalImageGenerationState(state: ImageGenerationState?): Boolean =
    state == ImageGenerationState.COMPLETED ||
        state == ImageGenerationState.FAILED ||
        state == ImageGenerationState.CANCELLED

internal suspend fun <T> refreshStatusPreservingCancellation(
    currentStatus: T?,
    refresh: suspend () -> T
): T? =
    try {
        refresh()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentStatus
    }

private const val MAX_GENERATED_IMAGE_DIMENSION = 8_192
private const val MAX_GENERATED_IMAGE_PIXELS = 16L * 1_024 * 1_024
private const val MAX_GENERATED_IMAGE_PREVIEW_DIMENSION = 2_048

internal fun isSafeGeneratedImageDimensions(width: Int, height: Int): Boolean =
    width in 1..MAX_GENERATED_IMAGE_DIMENSION &&
        height in 1..MAX_GENERATED_IMAGE_DIMENSION &&
        width.toLong() * height.toLong() <= MAX_GENERATED_IMAGE_PIXELS

internal fun generatedImagePreviewSampleSize(width: Int, height: Int): Int {
    require(isSafeGeneratedImageDimensions(width, height)) {
        "Generated image dimensions exceed the preview limit"
    }
    var sampleSize = 1
    while (maxOf(width / sampleSize, height / sampleSize) > MAX_GENERATED_IMAGE_PREVIEW_DIMENSION) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun decodeGeneratedImage(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (!isSafeGeneratedImageDimensions(bounds.outWidth, bounds.outHeight)) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = generatedImagePreviewSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/**
 * First interactive ComfyUI surface. It submits through the saved encrypted settings,
 * polls the provider, and persists completed bytes only through the encrypted media repository.
 */
@Composable
internal fun ImageGenerationPanel(
    app: GalaxyApplication,
    characterId: String,
    modifier: Modifier = Modifier,
    onActiveChanged: (Boolean) -> Unit,
    onSubmittingChanged: (Boolean) -> Unit
) {
    var runtime by remember { mutableStateOf<ComfyUiRuntime?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var statusRetryKey by remember { mutableStateOf(0) }
    var statusNeedsRetry by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<ImageGenerationRequest?>(null) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<ImageGenerationStatus?>(null) }
    var panelError by remember { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }
    var isPersisting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clientId = remember { UUID.randomUUID().toString() }
    val activeChanged = rememberUpdatedState(onActiveChanged)

    LaunchedEffect(app, clientId, retryKey) {
        runtime = null
        setupError = null
        try {
            runtime = app.createComfyUiRuntimeFromSavedSettings(clientId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            setupError = "Не удалось подключиться к ComfyUI. Проверь настройки и доступность сервера."
        }
    }

    LaunchedEffect(runtime, jobId, request, statusRetryKey) {
        statusNeedsRetry = false
        val activeRuntime = runtime ?: return@LaunchedEffect
        val activeJobId = jobId ?: return@LaunchedEffect
        val activeRequest = request ?: return@LaunchedEffect
        var terminal = false
        try {
            while (!terminal) {
                val current = GetImageGenerationStatus(activeRuntime.provider).invoke(activeJobId)
                status = current
                when (current.state) {
                    ImageGenerationState.COMPLETED -> {
                        isPersisting = true
                        try {
                            val stored = activeRuntime.persistCompletedResult(activeRequest, activeJobId)
                            val plaintext = app.generatedImages.read(stored.assetId)
                            val decoded = withContext(Dispatchers.Default) {
                                try {
                                    decodeGeneratedImage(plaintext)
                                } finally {
                                    plaintext.fill(0)
                                }
                            }
                            image = decoded
                            if (decoded == null) {
                                panelError = "Изображение сохранено, но его не удалось открыть."
                            }
                        } finally {
                            isPersisting = false
                        }
                        terminal = true
                    }
                    ImageGenerationState.FAILED -> {
                        panelError = "ComfyUI завершил генерацию с ошибкой."
                        terminal = true
                    }
                    ImageGenerationState.CANCELLED -> terminal = true
                    else -> delay(2_000)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            panelError = if (status == null) {
                "Состояние задания пока не подтверждено. Повтори проверку статуса; повторная отправка заблокирована."
            } else {
                "Не удалось получить результат. Проверь подключение и повтори попытку."
            }
            terminal = isTerminalImageGenerationState(status?.state)
            statusNeedsRetry = !terminal
        } finally {
            if (terminal) activeChanged.value(false)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("ГЕНЕРАЦИЯ ИЗОБРАЖЕНИЯ", fontSize = 16.sp)
        when {
            runtime != null -> Text(
                "ComfyUI подключён. Используется workflow из зашифрованных настроек.",
                fontSize = 11.sp
            )
            setupError != null -> {
                Text(setupError.orEmpty(), fontSize = 12.sp)
                Button(onClick = { retryKey++ }) { Text("ПОВТОРИТЬ ПОДКЛЮЧЕНИЕ") }
            }
            else -> Text("Проверяю сохранённые настройки и подключение…", fontSize = 12.sp)
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { value ->
                if (value.length <= ImageGenerationRequest.MAX_PROMPT_LENGTH) prompt = value
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Описание изображения") },
            minLines = 3,
            maxLines = 5,
            enabled = runtime != null && jobId == null && !isSubmitting
        )
        Button(
            enabled = runtime != null && prompt.isNotBlank() && jobId == null && !isSubmitting,
            onClick = {
                val selectedRuntime = runtime ?: return@Button
                val requestToSubmit = try {
                    ImageGenerationRequest(
                        id = UUID.randomUUID().toString(),
                        conversationId = characterId,
                        characterId = characterId,
                        prompt = prompt.trim(),
                        preferredMimeType = "image/png",
                        width = 1024,
                        height = 1024,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                } catch (_: IllegalArgumentException) {
                    panelError = "Проверь описание изображения."
                    return@Button
                }
                isSubmitting = true
                onSubmittingChanged(true)
                panelError = null
                scope.launch {
                    try {
                        val submittedJobId = SubmitImageGeneration(selectedRuntime.provider).invoke(requestToSubmit)
                        request = requestToSubmit
                        jobId = submittedJobId
                        status = ImageGenerationStatus(ImageGenerationState.QUEUED)
                        onActiveChanged(true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (uncertain: ComfyUiSubmissionUncertainException) {
                        // Keep the original request and stable prompt ID so the user can only
                        // reconcile status; tapping Create again must not enqueue a duplicate.
                        request = requestToSubmit
                        jobId = uncertain.promptId
                        status = null
                        panelError = "Приём задания не подтверждён. Проверяю сохранённый ID; повторная отправка заблокирована."
                        onActiveChanged(true)
                    } catch (_: Exception) {
                        panelError = "Не удалось отправить задание. Проверь ComfyUI и сохранённый workflow."
                    } finally {
                        isSubmitting = false
                        onSubmittingChanged(false)
                    }
                }
            }
        ) {
            Text(if (isSubmitting) "ОТПРАВКА…" else "СОЗДАТЬ ИЗОБРАЖЕНИЕ")
        }
        status?.let { current ->
            Text("Статус: ${imageGenerationStatusLabel(current.state)}", fontSize = 12.sp)
            current.progressPercent?.let { Text("Прогресс: $it%", fontSize = 11.sp) }
        }
        if (statusNeedsRetry) {
            Button(
                onClick = {
                    panelError = null
                    statusNeedsRetry = false
                    statusRetryKey++
                }
            ) { Text("ПОВТОРИТЬ ПРОВЕРКУ") }
        }
        if (status?.state == ImageGenerationState.QUEUED && !isCancelling) {
            Button(
                enabled = !isPersisting,
                onClick = {
                    val selectedRuntime = runtime ?: return@Button
                    val selectedJobId = jobId ?: return@Button
                    isCancelling = true
                    scope.launch {
                        try {
                            CancelImageGeneration(selectedRuntime.provider).invoke(selectedJobId)
                            status = ImageGenerationStatus(ImageGenerationState.CANCELLED)
                            statusNeedsRetry = false
                            onActiveChanged(false)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            panelError = "Отмена не подтверждена. Задание могло уже перейти к выполнению."
                            status = refreshStatusPreservingCancellation(status) {
                                GetImageGenerationStatus(selectedRuntime.provider).invoke(selectedJobId)
                            }
                            if (isTerminalImageGenerationState(status?.state)) {
                                statusNeedsRetry = false
                                onActiveChanged(false)
                            }
                        } finally {
                            isCancelling = false
                        }
                    }
                }
            ) { Text("ОТМЕНИТЬ ЗАДАНИЕ") }
        }
        if (status?.state == ImageGenerationState.RUNNING) {
            Text("Задание выполняется. Безопасная отмена для запущенного ComfyUI job не поддерживается.", fontSize = 11.sp)
        }
        if (isPersisting) Text("Сохраняю результат в зашифрованное хранилище…", fontSize = 11.sp)
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Сгенерированное изображение",
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                contentScale = ContentScale.Fit
            )
        }
        panelError?.let { Text(it, fontSize = 11.sp) }
        if (status?.state?.let { state ->
                state == ImageGenerationState.COMPLETED ||
                    state == ImageGenerationState.FAILED ||
                    state == ImageGenerationState.CANCELLED
            } == true
        ) {
            Button(
                onClick = {
                    request = null
                    jobId = null
                    status = null
                    statusNeedsRetry = false
                    image = null
                    panelError = null
                    prompt = ""
                }
            ) { Text("НОВАЯ ГЕНЕРАЦИЯ") }
        }
        Text(
            "Изображение загружается в память и сохраняется только через зашифрованное хранилище Galaxy.",
            fontSize = 10.sp
        )
    }
}
