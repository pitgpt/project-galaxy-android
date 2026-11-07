package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.repository.ConversationRepository
import com.pitgpt.galaxy.data.providers.ChatTurn
import com.pitgpt.galaxy.data.providers.EncryptedProviderStore
import com.pitgpt.galaxy.data.providers.OpenAiCompatibleChatClient
import com.pitgpt.galaxy.data.providers.TextChatProvider
import java.util.UUID

/**
 * Generates and persists a character reply using the currently selected text provider.
 * The caller supplies the character's current system/persona prompt.
 */
class GenerateCharacterReply(
    private val conversations: ConversationRepository,
    private val providerStore: EncryptedProviderStore,
    private val client: TextChatProvider = OpenAiCompatibleChatClient(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend operator fun invoke(
        characterId: String,
        systemPrompt: String,
        historyLimit: Int = 40
    ): ConversationMessage {
        require(characterId.isNotBlank()) { "Character ID must not be blank" }
        require(systemPrompt.isNotBlank()) { "Character system prompt must not be blank" }
        require(historyLimit in 1..200) { "History limit must be between 1 and 200" }

        val settings = providerStore.load()
        val provider = settings.providers.firstOrNull { it.id == settings.activeProviderId }
            ?: throw IllegalStateException("No active text provider is configured")

        val history = conversations.getForCharacter(characterId)
            .takeLast(historyLimit)
            .mapNotNull { message ->
                val role = when (message.role) {
                    MessageRole.USER -> "user"
                    MessageRole.CHARACTER -> "assistant"
                    MessageRole.SYSTEM -> null
                }
                role?.let { ChatTurn(it, message.content) }
            }

        require(history.isNotEmpty() && history.last().role == "user") {
            "A user message is required before generating a character reply"
        }

        val completion = client.complete(
            provider = provider,
            turns = listOf(ChatTurn("system", systemPrompt)) + history
        )
        val reply = ConversationMessage(
            id = newId(),
            characterId = characterId,
            role = MessageRole.CHARACTER,
            content = completion.text,
            createdAtEpochMillis = now()
        )
        conversations.append(reply)
        return reply
    }
}
