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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.pitgpt.galaxy.core.repository.ImageGenerationRequestStore
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobStore
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRuntime
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiSubmissionCancelledException
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiSubmissionUncertainException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun imageGenerationNavigationLocked(isSubmitting: Boolean): Boolean = isSubmitting

internal fun imageGenerationStatusLabel(state: ImageGenerationState): String =
    when (state) {
        ImageGenerationState.QUEUED -> "В очереди"
        ImageGenerationState.RUNNING -> "Выполняется"
        ImageGenerationState.COMPLETED -> "Готово"
        ImageGenerationState.FAILED -> "Ошибка генерации"
        ImageGenerationState.CANCELLED -> "Отменено"
    }

internal data class RecoverableImageGenerationRequest(
    val request: ImageGenerationRequest,
    val job: ComfyUiJobRecord?
) {
    val canResumeSafely: Boolean
        get() = job == null || !job.submissionStarted
}

internal fun latestRecoverableImageGenerationRequest(
    characterId: String,
    requests: List<ImageGenerationRequest>,
    jobs: List<ComfyUiJobRecord>
): RecoverableImageGenerationRequest? {
    val latest = requests.asSequence()
        .filter { it.characterId == characterId }
        .maxWithOrNull(compareBy<ImageGenerationRequest> { it.createdAtEpochMillis }.thenBy { it.id })
        ?: return null
    return RecoverableImageGenerationRequest(
        request = latest,
        job = jobs.firstOrNull { it.requestId == latest.id }
    )
}

/**
 * Loads durable recovery state independently of the network runtime. A saved request
 * must remain visible when ComfyUI is offline or its endpoint probe has not completed.
 */
internal suspend fun loadLatestRecoverableImageGenerationRequest(
    characterId: String,
    requestStore: ImageGenerationRequestStore,
    jobStore: ComfyUiJobStore
): RecoverableImageGenerationRequest? {
    val latest = requestStore.latestForCharacter(characterId) ?: return null
    return latestRecoverableImageGenerationRequest(
        characterId = characterId,
        requests = listOf(latest),
        jobs = jobStore.all()
    )
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

internal suspend fun decodeGeneratedImageAndClearBytes(
    bytes: ByteArray,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): ImageBitmap? = try {
    withContext(dispatcher) { decodeGeneratedImage(bytes) }
} finally {
    // Keep plaintext cleanup outside withContext: cancellation can prevent its block from starting.
    bytes.fill(0)
}

/**
 * First interactive ComfyUI surface. It submits through the saved encrypted settings,
 * polls the provider, and persists completed bytes only through the encrypted media repository.
 */
@Composable
internal fun CharacterScopedGenerationPanel(
    characterId: String,
    content: @Composable () -> Unit
) {
    key(characterId) { content() }
}

@Composable
internal fun ImageGenerationPanel(
    app: GalaxyApplication,
    characterId: String,
    modifier: Modifier = Modifier,
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
    var isRestoring by remember { mutableStateOf(false) }
    var isPreparedRecovery by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }
    var isPersisting by remember { mutableStateOf(false) }
    var isClearingRequest by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clientId = remember { UUID.randomUUID().toString() }

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

    LaunchedEffect(app, characterId) {
        if (request != null || isRestoring) return@LaunchedEffect
        isRestoring = true
        try {
            val restored = loadLatestRecoverableImageGenerationRequest(
                characterId = characterId,
                requestStore = app.imageGenerationRequests,
                jobStore = app.comfyUiJobStore
            )
            if (restored != null) {
                request = restored.request
                prompt = restored.request.prompt
                if (restored.canResumeSafely) {
                    isPreparedRecovery = true
                    panelError = "Найден сохранённый запрос, который ещё не отправлялся. Его можно безопасно продолжить."
                } else {
                    jobId = requireNotNull(restored.job).promptId
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            panelError = "Не удалось восстановить сохранённое задание. Повтори проверку после подключения к ComfyUI."
        } finally {
            isRestoring = false
        }
    }

    LaunchedEffect(runtime, jobId, request, statusRetryKey, isPreparedRecovery) {
        if (isPreparedRecovery) return@LaunchedEffect
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
                            val decoded = decodeGeneratedImageAndClearBytes(plaintext)
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
        }
    }

    if (showHistory) {
        GeneratedImageHistory(
            repository = app.generatedImages,
            conversationId = characterId,
            modifier = modifier,
            onBack = { showHistory = false }
        )
        return
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("ГЕНЕРАЦИЯ ИЗОБРАЖЕНИЯ", fontSize = 16.sp)
            TextButton(onClick = { showHistory = true }) { Text("ИСТОРИЯ") }
        }
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
            enabled = runtime != null && (request == null || isPreparedRecovery) && !isSubmitting && !isRestoring
        )
        Button(
            enabled = runtime != null && prompt.isNotBlank() && request == null && !isSubmitting && !isRestoring,
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
                request = requestToSubmit
                isSubmitting = true
                onSubmittingChanged(true)
                panelError = null
                scope.launch {
                    try {
                        // Save the full request in SQLCipher before the job journal can
                        // advance to SUBMISSION_STARTED or any network request occurs.
                        app.imageGenerationRequests.put(requestToSubmit)
                        val submittedJobId = SubmitImageGeneration(selectedRuntime.provider).invoke(requestToSubmit)
                        jobId = submittedJobId
                        status = ImageGenerationStatus(ImageGenerationState.QUEUED)
                        isPreparedRecovery = false
                    } catch (cancelled: ComfyUiSubmissionCancelledException) {
                        jobId = cancelled.promptId
                        status = null
                        statusNeedsRetry = true
                        isPreparedRecovery = false
                        panelError = "Отправка прервана после передачи управления сети. Проверяю сохранённый ID; повторная отправка заблокирована."
                        throw cancelled
                    } catch (cancelled: CancellationException) {
                        // This generic cancellation occurs before the durable submission claim; resuming the
                        // same prepared request is safe because no POST /prompt has been started.
                        isPreparedRecovery = true
                        panelError = "Отправка отменена до подтверждения передачи серверу. Сохранённый запрос можно продолжить."
                        throw cancelled
                    } catch (uncertain: ComfyUiSubmissionUncertainException) {
                        jobId = uncertain.promptId
                        status = null
                        statusNeedsRetry = true
                        isPreparedRecovery = false
                        panelError = "Приём задания не подтверждён. Проверяю сохранённый ID; повторная отправка заблокирована."
                    } catch (_: Exception) {
                        // No started job means the same saved request can be retried safely.
                        isPreparedRecovery = true
                        panelError = "Не удалось отправить задание. Сохранённый запрос оставлен для безопасного повтора."
                    } finally {
                        isSubmitting = false
                        onSubmittingChanged(false)
                    }
                }
            }
        ) {
            Text(if (isSubmitting) "ОТПРАВКА…" else "СОЗДАТЬ ИЗОБРАЖЕНИЕ")
        }
        if (isPreparedRecovery && request != null) {
            Button(
                enabled = runtime != null && prompt.isNotBlank() && !isSubmitting && !isRestoring,
                onClick = {
                    val activeRuntime = runtime ?: return@Button
                    val savedRequest = request ?: return@Button
                    val requestToResume = try {
                        savedRequest.copy(prompt = prompt.trim())
                    } catch (_: IllegalArgumentException) {
                        panelError = "Проверь описание изображения."
                        return@Button
                    }
                    isSubmitting = true
                    onSubmittingChanged(true)
                    panelError = null
                    scope.launch {
                        try {
                            app.imageGenerationRequests.put(requestToResume)
                            request = requestToResume
                            val submittedJobId = SubmitImageGeneration(activeRuntime.provider).invoke(requestToResume)
                            jobId = submittedJobId
                            status = ImageGenerationStatus(ImageGenerationState.QUEUED)
                            isPreparedRecovery = false
                        } catch (cancelled: ComfyUiSubmissionCancelledException) {
                            jobId = cancelled.promptId
                            status = null
                            statusNeedsRetry = true
                            isPreparedRecovery = false
                            panelError = "Отправка прервана после передачи управления сети. Проверяю сохранённый ID; повторная отправка заблокирована."
                            throw cancelled
                        } catch (cancelled: CancellationException) {
                            // This generic cancellation occurs before the durable submission claim; resuming the
                            // same prepared request is safe because no POST /prompt has been started.
                            isPreparedRecovery = true
                            panelError = "Отправка отменена до подтверждения передачи серверу. Сохранённый запрос можно продолжить."
                            throw cancelled
                        } catch (uncertain: ComfyUiSubmissionUncertainException) {
                            jobId = uncertain.promptId
                            status = null
                            statusNeedsRetry = true
                            isPreparedRecovery = false
                            panelError = "Приём задания не подтверждён. Проверяю сохранённый ID; повторная отправка заблокирована."
                        } catch (_: Exception) {
                            isPreparedRecovery = true
                            panelError = "Не удалось продолжить отправку. Запрос сохранён; повтори попытку после проверки настроек."
                        } finally {
                            isSubmitting = false
                            onSubmittingChanged(false)
                        }
                    }
                }
            ) { Text("ПРОДОЛЖИТЬ СОХРАНЁННУЮ ОТПРАВКУ") }
        }
        if (isRestoring) Text("Восстанавливаю сохранённое задание…", fontSize = 11.sp)
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
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            panelError = "Отмена не подтверждена. Задание могло уже перейти к выполнению."
                            status = refreshStatusPreservingCancellation(status) {
                                GetImageGenerationStatus(selectedRuntime.provider).invoke(selectedJobId)
                            }
                            if (isTerminalImageGenerationState(status?.state)) {
                                statusNeedsRetry = false
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
                enabled = !isClearingRequest && !isSubmitting && !isPersisting,
                onClick = {
                    val requestIdToClear = request?.id
                    isClearingRequest = true
                    scope.launch {
                        try {
                            if (requestIdToClear != null) {
                                // The encrypted image remains in GeneratedImageRepository history.
                                // Remove only the dismissed request context so it is not restored
                                // as the active panel after the next process recreation.
                                app.imageGenerationRequests.delete(requestIdToClear)
                            }
                            request = null
                            jobId = null
                            isPreparedRecovery = false
                            status = null
                            statusNeedsRetry = false
                            image = null
                            panelError = null
                            prompt = ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            panelError = "Не удалось убрать запрос из активной панели. Сохранённое состояние не изменено."
                        } finally {
                            isClearingRequest = false
                        }
                    }
                }
            ) { Text(if (isClearingRequest) "ОЧИЩАЮ…" else "НОВАЯ ГЕНЕРАЦИЯ") }
        }
        Text(
            "Изображение загружается в память и сохраняется только через зашифрованное хранилище Galaxy.",
            fontSize = 10.sp
        )
    }
}
