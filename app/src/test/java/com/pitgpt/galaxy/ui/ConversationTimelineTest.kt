package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTimelineTest {
    @Test
    fun mergesMessagesAndImagesChronologicallyAndFiltersOtherCharacters() {
        val earlierMessage = message("m1", "character-a", 10L)
        val image = image("asset-b", "character-a", 20L)
        val sameTimeMessage = message("m2", "character-a", 20L)
        val otherMessage = message("m3", "character-b", 30L)
        val otherImage = image("asset-c", "character-b", 40L)

        val timeline = conversationTimelineEntries(
            characterId = "character-a",
            messages = listOf(earlierMessage, sameTimeMessage, otherMessage),
            images = listOf(image, otherImage)
        )

        assertEquals(
            listOf("message:m1", "message:m2", "image:asset-b"),
            timeline.map { it.key }
        )
    }

    @Test
    fun sortsEqualTimestampImagesByStableAssetId() {
        val timeline = conversationTimelineEntries(
            characterId = "character-a",
            messages = emptyList(),
            images = listOf(
                image("asset-z", "character-a", 50L),
                image("asset-a", "character-a", 50L)
            )
        )

        assertEquals(listOf("image:asset-a", "image:asset-z"), timeline.map { it.key })
    }

    private fun message(id: String, characterId: String, createdAt: Long) =
        ConversationMessage(
            id = id,
            characterId = characterId,
            role = MessageRole.USER,
            content = "Message $id",
            createdAtEpochMillis = createdAt
        )

    private fun image(assetId: String, characterId: String, createdAt: Long) =
        StoredGeneratedImage(
            assetId = assetId,
            requestId = "request-$assetId",
            conversationId = characterId,
            characterId = characterId,
            mimeType = "image/png",
            width = 512,
            height = 768,
            byteSize = 128_000,
            sha256 = "a".repeat(64),
            createdAtEpochMillis = createdAt
        )
}
