package com.pitgpt.galaxy.core.model

/**
 * A generation request is independent of any image engine or workflow payload.
 * Stable character identity and scene-specific instructions are composed by the caller.
 */
data class ImageGenerationRequest(
    val id: String,
    val conversationId: String,
    val characterId: String,
    val prompt: String,
    val negativePrompt: String? = null,
    val preferredMimeType: String? = null,
    val workflowId: String? = null,
    val width: Int = 1024,
    val height: Int = 1024,
    val seed: Long? = null,
    val referenceAssetIds: List<String> = emptyList(),
    val createdAtEpochMillis: Long
) {
    init {
        require(id.isNotBlank()) { "Request ID must not be blank" }
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank" }
        require(characterId.isNotBlank()) { "Character ID must not be blank" }
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        require(prompt.length <= MAX_PROMPT_LENGTH) { "Prompt is too long" }
        require(negativePrompt == null || negativePrompt.length <= MAX_PROMPT_LENGTH) {
            "Negative prompt is too long"
        }
        require(preferredMimeType == null || preferredMimeType.contains("/")) {
            "Preferred MIME type is invalid"
        }
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(referenceAssetIds.all { it.isNotBlank() }) {
            "Reference asset IDs must not be blank"
        }
        require(referenceAssetIds.distinct().size == referenceAssetIds.size) {
            "Reference asset IDs must be unique"
        }
    }

    companion object {
        const val MAX_PROMPT_LENGTH = 12_000
    }
}

data class ImageGenerationCapabilities(
    val supportedMimeTypes: Set<String>,
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val supportsReferenceImages: Boolean,
    /** Legacy aggregate flag retained for source compatibility with existing providers. */
    val supportsCancellation: Boolean,
    val supportsProgress: Boolean,
    /** Whether a provider can remove a job before execution starts. */
    val supportsQueuedCancellation: Boolean = supportsCancellation,
    /** Whether a provider can stop a job that is already executing. */
    val supportsRunningCancellation: Boolean = supportsCancellation
) {
    init {
        require(supportedMimeTypes.isNotEmpty()) { "At least one image format is required" }
        require(minWidth > 0 && maxWidth >= minWidth) { "Invalid supported width range" }
        require(minHeight > 0 && maxHeight >= minHeight) { "Invalid supported height range" }
    }

    fun supports(request: ImageGenerationRequest): Boolean =
        request.width in minWidth..maxWidth &&
            request.height in minHeight..maxHeight &&
            (request.preferredMimeType == null || request.preferredMimeType in supportedMimeTypes) &&
            (request.referenceAssetIds.isEmpty() || supportsReferenceImages)
}

enum class ImageGenerationState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ImageGenerationStatus(
    val state: ImageGenerationState,
    val progressPercent: Int? = null,
    val userMessage: String? = null
) {
    init {
        require(progressPercent == null || progressPercent in 0..100) {
            "Progress must be between 0 and 100"
        }
    }
}

/** Metadata for an image returned by a provider; bytes are handled by the data layer. */
data class GeneratedImageAsset(
    val assetId: String,
    val requestId: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val createdAtEpochMillis: Long
) {
    init {
        require(assetId.isNotBlank()) { "Asset ID must not be blank" }
        require(requestId.isNotBlank()) { "Request ID must not be blank" }
        require(mimeType.isNotBlank()) { "MIME type must not be blank" }
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(byteSize > 0) { "Image byte size must be positive" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "SHA-256 must contain 64 hexadecimal characters"
        }
    }
}
