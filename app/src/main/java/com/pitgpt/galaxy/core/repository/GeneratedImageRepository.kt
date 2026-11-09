package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage

/** Stores image bytes encrypted and keeps only metadata in the encrypted local database. */
interface GeneratedImageRepository {
    suspend fun save(
        request: ImageGenerationRequest,
        asset: GeneratedImageAsset,
        imageBytes: ByteArray
    ): StoredGeneratedImage

    suspend fun get(assetId: String): StoredGeneratedImage?
    suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage>
    suspend fun read(assetId: String): ByteArray
    suspend fun delete(assetId: String)
}
