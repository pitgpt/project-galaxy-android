package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.data.remote.LilaHistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyHomeHistoryTest {
    @Test
    fun mergeReplacesPendingLocalMessageWithCanonicalRemoteIdentityWhenTurnCommitted() {
        val characterId = "owner:partition:galaxy.default"
        val conversationId = "00000000-0000-4000-8000-000000000402"
        val pending = ConversationMessage(
            id = pendingLilaMessageId("00000000-0000-4000-8000-000000000499"),
            characterId = characterId,
            role = MessageRole.USER,
            content = "hello",
            createdAtEpochMillis = 900
        )
        val remote = listOf(
            LilaHistoryMessage(1, "user", "hello", 1000),
            LilaHistoryMessage(2, "assistant", "world", 1001)
        )

        val merged = mergeRemoteHistory(characterId, conversationId, listOf(pending), remote)

        assertEquals(
            listOf(
                "lila-$conversationId-00000000000000000001",
                "lila-$conversationId-00000000000000000002"
            ),
            merged.map { it.id }
        )
        assertTrue(merged.none { it.id.startsWith(PENDING_LILA_MESSAGE_PREFIX) })
    }

    @Test
    fun mergeRetainsPendingLocalMessageWhenCoreHasNotCommittedTheTurn() {
        val characterId = "owner:partition:galaxy.default"
        val conversationId = "00000000-0000-4000-8000-000000000403"
        val pending = ConversationMessage(
            id = pendingLilaMessageId("00000000-0000-4000-8000-000000000498"),
            characterId = characterId,
            role = MessageRole.USER,
            content = "still pending",
            createdAtEpochMillis = 900
        )

        val merged = mergeRemoteHistory(characterId, conversationId, listOf(pending), emptyList())

        assertEquals(listOf(pending), merged)
    }

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
