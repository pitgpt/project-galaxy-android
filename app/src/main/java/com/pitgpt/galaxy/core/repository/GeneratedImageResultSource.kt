package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.GeneratedImagePayload

/** Supplies a completed image result without persisting its binary payload. */
interface GeneratedImageResultSource {
    suspend fun completedImage(providerJobId: String): GeneratedImagePayload
}
