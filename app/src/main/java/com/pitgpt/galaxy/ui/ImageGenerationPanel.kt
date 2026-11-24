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
import androidx.compose.runtime.key
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
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.core.repository.ImageGenerationRequestStore
import com.pitgpt.galaxy.core.usecase.PersistGeneratedImageResult
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobStore
import com.pitgpt.galaxy.data.remote.LilaCoreImageGenerationProvider
import com.pitgpt.galaxy.data.remote.LilaImageHistoryJob
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
    jobs: List<ComfyUiJobRecord>,
    conversationId: String = characterId
): RecoverableImageGenerationRequest? {
    val latest = requests.asSequence()
        .filter { it.characterId == characterId && it.conversationId == conversationId }
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
    jobStore: ComfyUiJobStore,
    conversationId: String = characterId
): RecoverableImageGenerationRequest? {
    // Character IDs are shared across signed-in owners; restore only within the stable
    // owner-scoped Lila conversation. The request table remains encrypted by SQLCipher.
    val latest = requestStore.all().asSequence()
        .filter { it.characterId == characterId && it.conversationId == conversationId }
        .maxWithOrNull(compareBy<ImageGenerationRequest> { it.createdAtEpochMillis }.thenBy { it.id })
        ?: return null
    return latestRecoverableImageGenerationRequest(
        characterId = characterId,
        requests = listOf(latest),
        jobs = jobStore.all(),
        conversationId = conversationId
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
    conversationId: String,
    content: @Composable () -> Unit
) {
    // Reset transient Compose state when the authenticated owner/conversation changes,
    // even if the selected character ID is the same.
    key(conversationId) { content() }
}

internal data class CoreImageHistorySyncResult(
    val importedImages: List<com.pitgpt.galaxy.core.model.StoredGeneratedImage>,
    val skippedAssets: Int
)

internal fun coreHistoryRequest(
    job: LilaImageHistoryJob,
    requestId: String,
    characterId: String
): ImageGenerationRequest = ImageGenerationRequest(
    id = requestId,
    conversationId = job.conversationId,
    characterId = characterId,
    prompt = job.prompt,
    negativePrompt = job.negativePrompt,
    preferredMimeType = "image/png",
    width = job.width,
    height = job.height,
    seed = job.seed,
    createdAtEpochMillis = job.createdAtEpochMillis
)

/**
 * Imports only a bounded recent window from the owner-scoped Core history.
 * Binary assets are immediately persisted through the existing encrypted media repository;
 * older assets remain in Core and are not downloaded eagerly on every screen open.
 */
internal suspend fun syncCoreImageHistory(
    provider: LilaCoreImageGenerationProvider,
    conversationId: String,
    characterId: String,
    requestStore: ImageGenerationRequestStore,
    jobStore: ComfyUiJobStore,
    images: GeneratedImageRepository,
    history: List<LilaImageHistoryJob>? = null,
    maxAssetImports: Int = 10,
    maxAssetBytes: Long = 50L * 1024 * 1024
): CoreImageHistorySyncResult {
    require(maxAssetImports in 1..50)
    require(maxAssetBytes in 1L..250L * 1024 * 1024)
    val coreHistory = history ?: provider.history(conversationId)
    val imported = mutableListOf<com.pitgpt.galaxy.core.model.StoredGeneratedImage>()
    var skipped = 0
    var importedBytes = 0L

    for (job in coreHistory.sortedByDescending { it.createdAtEpochMillis }) {
        try {
            val existingJob = jobStore.get(job.jobId)
            val requestId = existingJob?.requestId ?: job.jobId
            val existingRequest = requestStore.get(requestId)
            val request = existingRequest ?: coreHistoryRequest(job, requestId, characterId)

            check(request.conversationId == conversationId && request.characterId == characterId) {
                "Core image history conflicts with the local conversation context"
            }
            check(
                request.prompt == job.prompt &&
                    request.negativePrompt.orEmpty() == job.negativePrompt.orEmpty() &&
                    request.width == job.width &&
                    request.height == job.height &&
                    request.seed == job.seed
            ) {
                "Core image history conflicts with the stored generation request"
            }
            if (existingRequest == null) requestStore.put(request)

            if (existingJob == null) {
                jobStore.put(
                    ComfyUiJobRecord(
                        promptId = job.jobId,
                        requestId = requestId,
                        createdAtEpochMillis = job.createdAtEpochMillis,
                        submissionStarted = true
                    )
                )
            } else {
                check(existingJob.requestId == requestId) {
                    "Core image job is already correlated to a different local request"
                }
            }

            if (job.state != "completed" || images.getForRequest(requestId) != null) continue
            val assetBytes = job.assetByteSize ?: continue
            if (imported.size >= maxAssetImports || assetBytes > maxAssetBytes - importedBytes) {
                skipped++
                continue
            }

            imported += PersistGeneratedImageResult(provider, images).invoke(request, job.jobId)
            importedBytes += assetBytes
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            skipped++
        }
    }
    return CoreImageHistorySyncResult(importedImages = imported, skippedAssets = skipped)
}

@Composable
internal fun ImageGenerationPanel(
    app: GalaxyApplication,
    characterId: String,
    modifier: Modifier = Modifier,
    imageHistoryReady: Boolean,
    onSubmittingChanged: (Boolean) -> Unit,
    conversationId: String = characterId,
    onImagePersisted: (com.pitgpt.galaxy.core.model.StoredGeneratedImage) -> Unit = {},
    onJobStateChanged: () -> Unit = {}
) {
    val provider = remember(app) { app.lilaCoreImageGenerationProvider }
    val currentOnImagePersisted by rememberUpdatedState(onImagePersisted)
    val currentOnJobStateChanged by rememberUpdatedState(onJobStateChanged)
    var providerConfigured by remember { mutableStateOf(false) }
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(app, provider, characterId, conversationId, retryKey, imageHistoryReady) {
        isRestoring = true
        setupError = null
        try {
            val summary = provider.providerSummary()
            providerConfigured = summary.configured
            if (!summary.configured) {
                setupError = "ComfyUI не настроен в Lila Core. Открой настройки ComfyUI и сохрани endpoint с workflow."
            }

        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            providerConfigured = false
            setupError = "Не удалось прочитать конфигурацию Lila Core. Проверь авторизацию и подключение."
        } finally {
            if (imageHistoryReady && request == null) {
                try {
                    val restored = loadLatestRecoverableImageGenerationRequest(
                        characterId = characterId,
                        conversationId = conversationId,
                        requestStore = app.imageGenerationRequests,
                        jobStore = app.comfyUiJobStore
                    )
                    if (restored != null) {
                        request = restored.request
                        prompt = restored.request.prompt
                        if (restored.canResumeSafely) {
                            isPreparedRecovery = true
                            panelError = "Найден сохранённый запрос с устойчивым ID. Его можно безопасно продолжить без создания дубликата."
                        } else {
                            jobId = requireNotNull(restored.job).promptId
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    panelError = "Не удалось восстановить локальную связь image job. Данные не удалены."
                }
            }
            isRestoring = false
        }
    }

    LaunchedEffect(provider, jobId, request, statusRetryKey, isPreparedRecovery) {
        if (isPreparedRecovery) return@LaunchedEffect
        statusNeedsRetry = false
        val activeJobId = jobId ?: return@LaunchedEffect
        val activeRequest = request ?: return@LaunchedEffect
        var terminal = false
        try {
            while (!terminal) {
                val current = GetImageGenerationStatus(provider).invoke(activeJobId)
                status = current
                when (current.state) {
                    ImageGenerationState.COMPLETED -> {
                        isPersisting = true
                        try {
                            val stored = PersistGeneratedImageResult(provider, app.generatedImages)
                                .invoke(activeRequest, activeJobId)
                            currentOnImagePersisted(stored)
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
                        panelError = "ComfyUI завершил генерацию с ошибкой. Проверь сохранённый workflow."
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
                "Состояние задания пока не подтверждено. Повтори проверку; новая отправка с другим ID не выполняется."
            } else {
                "Не удалось получить результат. Проверь подключение и повтори проверку статуса."
            }
            terminal = isTerminalImageGenerationState(status?.state)
            statusNeedsRetry = !terminal
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("ГЕНЕРАЦИЯ ИЗОБРАЖЕНИЯ", fontSize = 16.sp)
        when {
            providerConfigured -> Text(
                "Запрос проходит через Lila Core. Workflow и ComfyUI credentials хранятся на сервере.",
                fontSize = 11.sp
            )
            setupError != null -> {
                Text(setupError.orEmpty(), fontSize = 12.sp)
                Button(onClick = { retryKey++ }, enabled = !isSubmitting && !isRestoring) {
                    Text("ПОВТОРИТЬ ПРОВЕРКУ CORE")
                }
            }
            else -> Text("Проверяю конфигурацию Lila Core…", fontSize = 12.sp)
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
            enabled = providerConfigured && request == null && !isSubmitting && !isRestoring
        )
        Button(
            enabled = providerConfigured && prompt.isNotBlank() && request == null && !isSubmitting && !isRestoring,
            onClick = {
                val requestToSubmit = try {
                    ImageGenerationRequest(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
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
                        app.imageGenerationRequests.put(requestToSubmit)
                        val submittedJobId = SubmitImageGeneration(provider).invoke(requestToSubmit)
                        jobId = submittedJobId
                        status = ImageGenerationStatus(ImageGenerationState.QUEUED)
                        isPreparedRecovery = false
                    } catch (cancelled: CancellationException) {
                        isPreparedRecovery = true
                        panelError = "Отправка прервана. Сохранённый запрос можно безопасно повторить с тем же ID."
                        throw cancelled
                    } catch (_: Exception) {
                        isPreparedRecovery = true
                        panelError = "Не удалось подтвердить отправку. Повтор использует тот же idempotency key и те же параметры."
                    } finally {
                        isSubmitting = false
                        onSubmittingChanged(false)
                        currentOnJobStateChanged()
                    }
                }
            }
        ) {
            Text(if (isSubmitting) "ОТПРАВКА…" else "СОЗДАТЬ ИЗОБРАЖЕНИЕ")
        }
        if (isPreparedRecovery && request != null) {
            Button(
                enabled = providerConfigured && !isSubmitting && !isRestoring,
                onClick = {
                    val savedRequest = request ?: return@Button
                    isSubmitting = true
                    onSubmittingChanged(true)
                    panelError = null
                    scope.launch {
                        try {
                            app.imageGenerationRequests.put(savedRequest)
                            val submittedJobId = SubmitImageGeneration(provider).invoke(savedRequest)
                            jobId = submittedJobId
                            status = ImageGenerationStatus(ImageGenerationState.QUEUED)
                            isPreparedRecovery = false
                        } catch (cancelled: CancellationException) {
                            isPreparedRecovery = true
                            panelError = "Повтор прерван. Тот же сохранённый запрос можно продолжить позже."
                            throw cancelled
                        } catch (_: Exception) {
                            isPreparedRecovery = true
                            panelError = "Повтор не подтверждён. Параметры и idempotency key сохранены без изменений."
                        } finally {
                            isSubmitting = false
                            onSubmittingChanged(false)
                            currentOnJobStateChanged()
                        }
                    }
                }
            ) { Text("ПОВТОРИТЬ СОХРАНЁННЫЙ ЗАПРОС") }
        }
        if (isRestoring) Text("Восстанавливаю историю Lila Core…", fontSize = 11.sp)
        status?.let { current ->
            Text("Статус: " + imageGenerationStatusLabel(current.state), fontSize = 12.sp)
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
                    val selectedJobId = jobId ?: return@Button
                    isCancelling = true
                    scope.launch {
                        try {
                            CancelImageGeneration(provider).invoke(selectedJobId)
                            status = ImageGenerationStatus(ImageGenerationState.CANCELLED)
                            statusNeedsRetry = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            panelError = "Отмена не подтверждена. Задание могло уже перейти к выполнению."
                            status = refreshStatusPreservingCancellation(status) {
                                GetImageGenerationStatus(provider).invoke(selectedJobId)
                            }
                            if (isTerminalImageGenerationState(status?.state)) statusNeedsRetry = false
                        } finally {
                            isCancelling = false
                            currentOnJobStateChanged()
                        }
                    }
                }
            ) { Text("ОТМЕНИТЬ ЗАДАНИЕ") }
        }
        if (status?.state == ImageGenerationState.RUNNING) {
            Text("Задание выполняется. Безопасная отмена уже запущенного ComfyUI job не поддерживается.", fontSize = 11.sp)
        }
        if (isPersisting) Text("Сохраняю результат в зашифрованное хранилище Galaxy…", fontSize = 11.sp)
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Сгенерированное изображение",
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                contentScale = ContentScale.Fit
            )
        }
        panelError?.let { Text(it, fontSize = 11.sp) }
        if (isTerminalImageGenerationState(status?.state)) {
            Button(
                onClick = {
                    request = null
                    jobId = null
                    isPreparedRecovery = false
                    status = null
                    statusNeedsRetry = false
                    image = null
                    panelError = null
                    prompt = ""
                }
            ) { Text("НОВАЯ ГЕНЕРАЦИЯ") }
        }
        Text(
            "Результаты из Core кэшируются только через зашифрованное хранилище Galaxy. История заданий остаётся в Lila Core.",
            fontSize = 10.sp
        )
    }
}
