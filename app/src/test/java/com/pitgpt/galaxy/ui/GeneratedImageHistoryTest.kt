package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedImageHistoryTest {
    @Test
    fun filtersToSelectedConversationAndSortsNewestFirst() {
        val older = image("asset-b", "character-a", 10L)
        val newestOtherConversation = image("asset-c", "character-b", 30L)
        val newest = image("asset-a", "character-a", 20L)
        val sameTime = image("asset-d", "character-a", 20L)

        assertEquals(
            listOf(newest, sameTime, older),
            generatedImageHistoryForConversation(
                conversationId = "character-a",
                images = listOf(older, newestOtherConversation, sameTime, newest)
            )
        )
    }

    private fun image(assetId: String, conversationId: String, createdAt: Long) =
        StoredGeneratedImage(
            assetId = assetId,
            requestId = "request-$assetId",
            conversationId = conversationId,
            characterId = conversationId,
            mimeType = "image/png",
            width = 512,
            height = 768,
            byteSize = 128_000,
            sha256 = "a".repeat(64),
            createdAtEpochMillis = createdAt
        )
}
