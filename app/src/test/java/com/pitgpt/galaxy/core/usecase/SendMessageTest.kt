package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.repository.ConversationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SendMessageTest {
    @Test
    fun messageIsNormalizedPersistedAndReturned() = runBlocking {
        val repository = FakeConversationRepository()
        val useCase = SendMessage(repository, newId = { "message-1" }, now = { 123L })

        val result = useCase("galaxy.default", "  Привет!  ")

        assertEquals(
            ConversationMessage(
                id = "message-1",
                characterId = "galaxy.default",
                role = MessageRole.USER,
                content = "Привет!",
                createdAtEpochMillis = 123L
            ),
            result
        )
        assertEquals(listOf(result), repository.messages)
    }

    @Test
    fun blankMessageIsRejectedWithoutWritingToRepository() {
        val repository = FakeConversationRepository()
        val useCase = SendMessage(repository)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCase("galaxy.default", "   ") }
        }
        assertEquals(emptyList<ConversationMessage>(), repository.messages)
    }

    private class FakeConversationRepository : ConversationRepository {
        val messages = mutableListOf<ConversationMessage>()
        override suspend fun getForCharacter(characterId: String) =
            messages.filter { it.characterId == characterId }
        override suspend fun append(message: ConversationMessage) {
            messages += message
        }
        override suspend fun clearForCharacter(characterId: String) {
            messages.removeAll { it.characterId == characterId }
        }
    }
}
