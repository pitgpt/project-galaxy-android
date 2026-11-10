package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.ImageGenerationRequest

/**
 * Durable storage for the request context needed to restore an interrupted generation.
 * The production implementation must use Galaxy's SQLCipher-backed Room database.
 */
interface ImageGenerationRequestStore {
    suspend fun put(request: ImageGenerationRequest)
    suspend fun get(requestId: String): ImageGenerationRequest?
    suspend fun all(): List<ImageGenerationRequest>
    suspend fun delete(requestId: String)
}
