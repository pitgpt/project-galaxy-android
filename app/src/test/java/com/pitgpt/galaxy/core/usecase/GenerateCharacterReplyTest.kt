package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.repository.ConversationRepository
import com.pitgpt.galaxy.data.providers.AiProviderConfig
import com.pitgpt.galaxy.data.providers.ChatCompletion
import com.pitgpt.galaxy.data.providers.ChatTurn
import com.pitgpt.galaxy.data.providers.ProviderSettings
import com.pitgpt.galaxy.data.providers.ProviderSettingsRepository
import com.pitgpt.galaxy.data.providers.TextChatProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GenerateCharacterReplyTest {
    private val provider = AiProviderConfig(
        id = "provider-1",
        name = "Test provider",
        baseUrl = "https://api.example.test/v1",
        apiKey = "test-key",
        model = "test-model"
    )

    @Test
    fun sendsPersonaAndBoundedHistoryToActiveProviderAndPersistsReply() = runBlocking {
        val characterId = "character-1"
        val conversations = FakeConversationRepository(
            listOf(
                message("old-user", characterId, MessageRole.USER, "Older message", 1L),
                message("user-1", characterId, MessageRole.USER, "Hello", 2L),
                message("assistant-1", characterId, MessageRole.CHARACTER, "Hi", 3L),
                message("system-1", characterId, MessageRole.SYSTEM, "Internal marker", 4L),
                message("user-2", characterId, MessageRole.USER, "How are you?", 5L)
            )
        )
        val settings = FakeProviderSettingsRepository(
            ProviderSettings(providers = listOf(provider), activeProviderId = provider.id)
        )
        var capturedProvider: AiProviderConfig? = null
        var capturedTurns: List<ChatTurn> = emptyList()
        val client = TextChatProvider { selectedProvider, turns, _ ->
            capturedProvider = selectedProvider
            capturedTurns = turns
            ChatCompletion(text = "I am well.", model = "test-model")
        }
        val useCase = GenerateCharacterReply(
            conversations = conversations,
            providerStore = settings,
            client = client,
            newId = { "reply-id" },
            now = { 1234L }
        )

        val reply = useCase(
            characterId = characterId,
            systemPrompt = "You are Galaxy.",
            historyLimit = 4
        )

        assertEquals(provider, capturedProvider)
        assertEquals(
            listOf(
                ChatTurn("system", "You are Galaxy."),
                ChatTurn("user", "Hello"),
                ChatTurn("assistant", "Hi"),
                ChatTurn("user", "How are you?")
            ),
            capturedTurns
        )
        assertEquals(
            ConversationMessage(
                id = "reply-id",
                characterId = characterId,
                role = MessageRole.CHARACTER,
                content = "I am well.",
                createdAtEpochMillis = 1234L
            ),
            reply
        )
        assertEquals(listOf(reply), conversations.appended)
    }

    @Test
    fun rejectsHistoryWithoutPendingUserMessageBeforeCallingProvider() {
        val characterId = "character-1"
        val conversations = FakeConversationRepository(
            listOf(
                message("user-1", characterId, MessageRole.USER, "Hello", 1L),
                message("assistant-1", characterId, MessageRole.CHARACTER, "Hi", 2L)
            )
        )
        var providerCalled = false
        val client = TextChatProvider { _, _, _ ->
            providerCalled = true
            ChatCompletion(text = "Unexpected", model = "test-model")
        }
        val useCase = GenerateCharacterReply(
            conversations = conversations,
            providerStore = FakeProviderSettingsRepository(
                ProviderSettings(providers = listOf(provider), activeProviderId = provider.id)
            ),
            client = client
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCase(characterId, "You are Galaxy.") }
        }
        assertEquals(false, providerCalled)
        assertEquals(emptyList<ConversationMessage>(), conversations.appended)
    }


    @Test
    fun failsClearlyWhenNoActiveProviderIsConfigured() {
        val characterId = "character-1"
        val conversations = FakeConversationRepository(
            listOf(message("user-1", characterId, MessageRole.USER, "Hello", 1L))
        )
        var providerCalled = false
        val client = TextChatProvider { _, _, _ ->
            providerCalled = true
            ChatCompletion(text = "Unexpected", model = "test-model")
        }
        val useCase = GenerateCharacterReply(
            conversations = conversations,
            providerStore = FakeProviderSettingsRepository(ProviderSettings()),
            client = client
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { useCase(characterId, "You are Galaxy.") }
        }

        assertEquals("No active text provider is configured", failure.message)
        assertEquals(false, providerCalled)
        assertEquals(emptyList<ConversationMessage>(), conversations.appended)
    }

    @Test
    fun doesNotPersistAssistantReplyWhenProviderRequestFails() {
        val characterId = "character-1"
        val conversations = FakeConversationRepository(
            listOf(message("user-1", characterId, MessageRole.USER, "Hello", 1L))
        )
        val networkFailure = IllegalStateException("Provider unavailable")
        val client = TextChatProvider { _, _, _ -> throw networkFailure }
        val useCase = GenerateCharacterReply(
            conversations = conversations,
            providerStore = FakeProviderSettingsRepository(
                ProviderSettings(providers = listOf(provider), activeProviderId = provider.id)
            ),
            client = client
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { useCase(characterId, "You are Galaxy.") }
        }

        assertEquals(networkFailure, failure)
        assertEquals(emptyList<ConversationMessage>(), conversations.appended)
    }

    private fun message(
        id: String,
        characterId: String,
        role: MessageRole,
        content: String,
        timestamp: Long
    ) = ConversationMessage(id, characterId, role, content, timestamp)

    private class FakeConversationRepository(
        private val history: List<ConversationMessage>
    ) : ConversationRepository {
        val appended = mutableListOf<ConversationMessage>()

        override suspend fun getForCharacter(characterId: String): List<ConversationMessage> =
            history.filter { it.characterId == characterId }

        override suspend fun append(message: ConversationMessage) {
            appended += message
        }

        override suspend fun clearForCharacter(characterId: String) = Unit
    }

    private class FakeProviderSettingsRepository(
        private val settings: ProviderSettings
    ) : ProviderSettingsRepository {
        override fun load(): ProviderSettings = settings
        override fun save(settings: ProviderSettings) = Unit
        override fun clear() = Unit
    }
}
