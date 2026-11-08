package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.data.local.dao.ComfyUiJobDao
import com.pitgpt.galaxy.data.local.entity.toDomain
import com.pitgpt.galaxy.data.local.entity.toEntity

/**
 * Durable job correlation backed by Galaxy's SQLCipher-encrypted Room database.
 * Only opaque IDs and lifecycle metadata are stored; prompts, credentials and image bytes
 * are deliberately excluded.
 */
class RoomComfyUiJobStore(
    private val dao: ComfyUiJobDao
) : ComfyUiJobStore {
    override suspend fun put(record: ComfyUiJobRecord) {
        require(record.promptId.isNotBlank() && record.requestId.isNotBlank()) {
            "ComfyUI job identifiers must not be blank"
        }
        dao.insert(record.toEntity())
    }

    override suspend fun get(promptId: String): ComfyUiJobRecord? =
        dao.get(promptId)?.toDomain()

    override suspend fun markCancelled(promptId: String) {
        check(dao.markCancelled(promptId) == 1) {
            "ComfyUI job could not be marked as cancelled"
        }
    }
}
