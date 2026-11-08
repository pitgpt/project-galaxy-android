package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationCapabilities
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationStatus

/**
 * Provider boundary for local or remote image engines.
 * Implementations must not expose credentials or raw provider responses in errors/logs.
 */
interface ImageGenerationProvider {
    suspend fun capabilities(): ImageGenerationCapabilities

    /** Returns the provider-owned job ID used for status, cancellation, and result retrieval. */
    suspend fun submit(request: ImageGenerationRequest): String

    suspend fun status(providerJobId: String): ImageGenerationStatus

    suspend fun cancel(providerJobId: String)

    /** Returns validated metadata; binary transfer is handled separately by the data layer. */
    suspend fun result(providerJobId: String): GeneratedImageAsset
}
