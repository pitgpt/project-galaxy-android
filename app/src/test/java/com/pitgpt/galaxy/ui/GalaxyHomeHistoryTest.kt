package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.data.remote.LilaHistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class GalaxyHomeHistoryTest {
    @Test
    fun mergePreservesLocalRowsAndOrdersRemoteMessagesWithEqualTimestamps() {
        val characterId = "owner:partition:galaxy.default"
        val conversationId = "00000000-0000-4000-8000-000000000401"
        val local = listOf(
            ConversationMessage(
                id = "local-user",
                characterId = characterId,
                role = MessageRole.USER,
                content = "hello",
                createdAtEpochMillis = 900
            ),
            ConversationMessage(
                id = "local-assistant",
                characterId = characterId,
                role = MessageRole.CHARACTER,
                content = "world",
                createdAtEpochMillis = 901
            ),
            ConversationMessage(
                id = "local-only",
                characterId = characterId,
                role = MessageRole.USER,
                content = "offline note",
                createdAtEpochMillis = 1500
            )
        )
        val remote = listOf(
            LilaHistoryMessage(1, "user", "hello", 1000),
            LilaHistoryMessage(2, "assistant", "world", 1000),
            LilaHistoryMessage(3, "user", "later user", 2000),
            LilaHistoryMessage(4, "assistant", "later answer", 2000)
        )

        val merged = mergeRemoteHistory(characterId, conversationId, local, remote)

        assertEquals(
            listOf(
                "local-user",
                "local-assistant",
                "local-only",
                "lila-$conversationId-00000000000000000003",
                "lila-$conversationId-00000000000000000004"
            ),
            merged.map { it.id }
        )
        assertEquals(
            listOf("hello", "world", "offline note", "later user", "later answer"),
            merged.map { it.content }
        )
        assertEquals(listOf(900L, 901L, 1500L, 2000L, 2000L), merged.map { it.createdAtEpochMillis })
    }
}
