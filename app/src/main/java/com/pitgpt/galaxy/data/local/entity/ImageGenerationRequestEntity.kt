package com.pitgpt.galaxy.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import org.json.JSONArray

@Entity(
    tableName = "image_generation_requests",
    indices = [
        Index(value = ["conversationId", "createdAtEpochMillis"]),
        Index(value = ["characterId", "createdAtEpochMillis"])
    ]
)
data class ImageGenerationRequestEntity(
    @PrimaryKey val requestId: String,
    val conversationId: String,
    val characterId: String,
    val prompt: String,
    val negativePrompt: String?,
    val preferredMimeType: String?,
    val workflowId: String?,
    val width: Int,
    val height: Int,
    val seed: Long?,
    val referenceAssetIdsJson: String,
    val createdAtEpochMillis: Long
) {
    override fun toString(): String =
        "ImageGenerationRequestEntity(requestId=$requestId, conversationId=$conversationId, " +
            "characterId=$characterId, prompt=<redacted>, " +
            "negativePrompt=${if (negativePrompt == null) "null" else "<redacted>"}, " +
            "preferredMimeType=$preferredMimeType, workflowId=$workflowId, width=$width, " +
            "height=$height, seed=$seed, referenceAssetCountJsonChars=${referenceAssetIdsJson.length}, " +
            "createdAtEpochMillis=$createdAtEpochMillis)"
}

fun ImageGenerationRequest.toEntity() = ImageGenerationRequestEntity(
    requestId = id,
    conversationId = conversationId,
    characterId = characterId,
    prompt = prompt,
    negativePrompt = negativePrompt,
    preferredMimeType = preferredMimeType,
    workflowId = workflowId,
    width = width,
    height = height,
    seed = seed,
    referenceAssetIdsJson = JSONArray().apply {
        referenceAssetIds.forEach { put(it) }
    }.toString(),
    createdAtEpochMillis = createdAtEpochMillis
)

fun ImageGenerationRequestEntity.toDomain(): ImageGenerationRequest {
    val referenceIds = JSONArray(referenceAssetIdsJson)
    val references = ArrayList<String>(referenceIds.length())
    for (index in 0 until referenceIds.length()) {
        references += referenceIds.getString(index)
    }
    return ImageGenerationRequest(
        id = requestId,
        conversationId = conversationId,
        characterId = characterId,
        prompt = prompt,
        negativePrompt = negativePrompt,
        preferredMimeType = preferredMimeType,
        workflowId = workflowId,
        width = width,
        height = height,
        seed = seed,
        referenceAssetIds = references,
        createdAtEpochMillis = createdAtEpochMillis
    )
}
