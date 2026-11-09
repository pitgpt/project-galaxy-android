package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.GeneratedImagePayload
import com.pitgpt.galaxy.core.model.ImageGenerationCapabilities
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.model.ImageGenerationStatus
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider
import com.pitgpt.galaxy.core.repository.GeneratedImageResultSource
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

data class ComfyUiJobRecord(
    val promptId: String,
    val requestId: String,
    val createdAtEpochMillis: Long,
    val cancelled: Boolean = false,
    val submissionStarted: Boolean = false
)

/** Store only correlation metadata; production implementation must use SQLCipher. */
interface ComfyUiJobStore {
    suspend fun put(record: ComfyUiJobRecord)
    suspend fun get(promptId: String): ComfyUiJobRecord?
    suspend fun markCancelled(promptId: String)
    suspend fun all(): List<ComfyUiJobRecord> = emptyList()
    suspend fun markSubmissionStarted(promptId: String): Boolean
    suspend fun delete(promptId: String) = Unit
}

/** Development/test only. Process death loses request correlation. */
class InMemoryComfyUiJobStore : ComfyUiJobStore {
    private val records = ConcurrentHashMap<String, ComfyUiJobRecord>()
    override suspend fun put(record: ComfyUiJobRecord) {
        check(records.putIfAbsent(record.promptId, record) == null) { "ComfyUI job is already registered" }
    }
    override suspend fun get(promptId: String) = records[promptId]
    override suspend fun all(): List<ComfyUiJobRecord> = records.values.toList()
    override suspend fun markSubmissionStarted(promptId: String): Boolean {
        var claimed = false
        records.computeIfPresent(promptId) { _, record ->
            if (!record.submissionStarted) {
                claimed = true
                record.copy(submissionStarted = true)
            } else {
                record
            }
        }
        return claimed
    }
    override suspend fun delete(promptId: String) { records.remove(promptId) }
    override suspend fun markCancelled(promptId: String) {
        records.computeIfPresent(promptId) { _, value -> value.copy(cancelled = true) }
    }
}

/**
 * Adapter for one explicitly mapped ComfyUI workflow. PNG is the initial output format.
 * Image bytes remain in memory and must be passed to encrypted media storage by the caller.
 */
class ComfyUiImageGenerationProvider(
    private val config: ComfyUiConnectionConfig,
    private val template: ComfyUiWorkflowTemplate,
    private val clientId: String,
    private val client: ComfyUiApiClient,
    private val jobs: ComfyUiJobStore
) : ImageGenerationProvider, GeneratedImageResultSource {
    init {
        require(clientId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "ComfyUI client ID is invalid" }
    }

    override suspend fun capabilities() = ImageGenerationCapabilities(
        supportedMimeTypes = setOf(PNG_MIME),
        minWidth = 64, maxWidth = 2048, minHeight = 64, maxHeight = 2048,
        supportsReferenceImages = false,
        supportsCancellation = false,
        supportsProgress = false,
        supportsQueuedCancellation = true,
        supportsRunningCancellation = false
    )

    override suspend fun submit(request: ImageGenerationRequest): String {
        require(request.referenceAssetIds.isEmpty()) { "This ComfyUI workflow does not support reference images yet" }
        // Validate the local workflow before writing an intent or making a network request.
        template.buildPrompt(request)

        // Derive one canonical ComfyUI UUID from the immutable Galaxy request ID.
        val promptId = UUID.nameUUIDFromBytes(request.id.toByteArray(Charsets.UTF_8)).toString()
        val record = ComfyUiJobRecord(promptId, request.id, request.createdAtEpochMillis)
        val existing = jobs.get(promptId)
        if (existing != null) {
            check(existing.requestId == request.id) {
                "The stable ComfyUI prompt ID is already associated with another Galaxy request"
            }
            if (existing.submissionStarted) return promptId
        } else {
            try {
                jobs.put(record)
            } catch (failure: Exception) {
                // Room may report an acknowledgement failure after the row was committed.
                // Continue only when a read-back proves that the exact PREPARED intent is durable.
                val committed = try {
                    jobs.get(promptId)
                } catch (readbackFailure: Exception) {
                    failure.addSuppressed(readbackFailure)
                    throw failure
                }
                if (committed != record) throw failure
            }
        }

        // This conditional SQL update is the single-writer claim. Only one concurrent caller
        // may transition PREPARED -> SUBMISSION_STARTED and issue POST /prompt.
        if (!jobs.markSubmissionStarted(promptId)) return promptId

        try {
            val submitted = client.submit(config, template, request, clientId, promptId)
            check(submitted.promptId == promptId) {
                "ComfyUI did not honor the stable prompt ID; automatic retry is unsafe"
            }
            return promptId
        } catch (cancelled: CancellationException) {
            // SUBMISSION_STARTED is durable before the HTTP call. The request may have reached
            // ComfyUI, so retain the ID and never enqueue a replacement automatically.
            throw cancelled
        } catch (failure: ComfyUiApiException) {
            val status = failure.httpStatusCode
            val definitelyRejected = status != null && status in 400..499 &&
                status != 408 && status != 429
            if (definitelyRejected) {
                try {
                    withContext(NonCancellable) { jobs.delete(promptId) }
                } catch (_: Exception) {
                    throw ComfyUiSubmissionUncertainException(promptId)
                }
                throw failure
            }
            throw ComfyUiSubmissionUncertainException(promptId)
        } catch (_: Exception) {
            // Keep the started intent after timeout, disconnect, malformed response or 5xx.
            throw ComfyUiSubmissionUncertainException(promptId)
        }
    }

    override suspend fun status(providerJobId: String): ImageGenerationStatus {
        val job = requireJob(providerJobId)
        return if (job.cancelled) ImageGenerationStatus(ImageGenerationState.CANCELLED)
        else client.status(config, providerJobId)
    }

    override suspend fun cancel(providerJobId: String) {
        val job = requireJob(providerJobId)
        check(!job.cancelled) { "Image generation job is already cancelled" }
        val current = client.status(config, providerJobId)
        check(current.state == ImageGenerationState.QUEUED) {
            if (current.state == ImageGenerationState.RUNNING) "ComfyUI running jobs cannot be safely cancelled"
            else "Only queued ComfyUI jobs can be cancelled"
        }
        client.deleteQueued(config, providerJobId)
        val queue = client.queue(config)
        check(providerJobId !in queue.runningPromptIds) { "ComfyUI job started before queued cancellation completed" }
        check(providerJobId !in queue.pendingPromptIds) { "ComfyUI did not confirm removal of the queued job" }
        check(!client.hasHistory(config, providerJobId)) {
            "ComfyUI job completed or entered history while queued cancellation was being verified"
        }
        // The remote deletion is already confirmed; finish durable local bookkeeping even
        // if the UI coroutine is cancelled while the SQLCipher write is pending.
        withContext(NonCancellable) {
            jobs.markCancelled(providerJobId)
        }
    }

    override suspend fun result(providerJobId: String): GeneratedImageAsset =
        completedImage(providerJobId).asset

    /** Bounded bytes in memory only; caller must persist them through encrypted media storage. */
    suspend fun downloadResult(providerJobId: String): ComfyUiImageDownload =
        completedImage(providerJobId).let { ComfyUiImageDownload(it.mimeType, it.bytes, it.sha256) }

    /** Downloads once and returns validated metadata together with the exact bytes it describes. */
    override suspend fun completedImage(providerJobId: String): GeneratedImagePayload {
        val job = requireJob(providerJobId)
        check(!job.cancelled) { "Cancelled image generation has no result" }
        val history = client.history(config, providerJobId)
        check(history.status.state == ImageGenerationState.COMPLETED) {
            "ComfyUI image generation has not completed"
        }
        val ref = selectOutput(history.images)
        val download = client.download(config, ref)
        check(download.mimeType == PNG_MIME) { "ComfyUI returned an unsupported image format" }
        val (width, height) = pngDimensions(download.bytes)
        val asset = GeneratedImageAsset(
            assetId = "comfyui:" + providerJobId + ":" + ref.filename,
            requestId = job.requestId,
            mimeType = download.mimeType,
            width = width,
            height = height,
            byteSize = download.byteSize,
            sha256 = download.sha256,
            createdAtEpochMillis = job.createdAtEpochMillis
        )
        return GeneratedImagePayload(asset, download.mimeType, download.bytes, download.sha256)
    }

    private fun selectOutput(images: List<ComfyUiImageReference>) =
        images.sortedWith(compareBy<ComfyUiImageReference>({ it.subfolder }, { it.filename }, { it.type }))
            .firstOrNull() ?: throw IllegalStateException("ComfyUI completed without an output image")

    private suspend fun requireJob(promptId: String): ComfyUiJobRecord {
        require(promptId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "ComfyUI prompt ID is invalid" }
        return jobs.get(promptId) ?: throw IllegalArgumentException("ComfyUI job is not registered in this Galaxy installation")
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size >= 24 && (bytes[0].toInt() and 0xff) == 137 &&
            String(bytes, 1, 3, Charsets.US_ASCII) == "PNG" &&
            String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR") { "PNG header is incomplete or invalid" }
        fun u32(offset: Int) =
            ((bytes[offset].toLong() and 0xff) shl 24) or
                ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or
                (bytes[offset + 3].toLong() and 0xff)
        val width = u32(16).toInt()
        val height = u32(20).toInt()
        require(width in 1..8192 && height in 1..8192) { "PNG dimensions are invalid" }
        return width to height
    }

    private companion object { const val PNG_MIME = "image/png" }
}


/** Carries the durable prompt ID when the remote submission acknowledgement is ambiguous. */
class ComfyUiSubmissionUncertainException(
    val promptId: String
) : IOException("ComfyUI submission outcome is unknown; check the existing prompt before retrying")
