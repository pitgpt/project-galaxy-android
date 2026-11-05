package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.DefaultCharacter
import com.pitgpt.galaxy.core.repository.CharacterRepository

/** Installs the built-in character once without overwriting user changes. */
class InitializeGalaxy(private val characters: CharacterRepository) {
    suspend operator fun invoke() {
        val builtIn = DefaultCharacter.profile
        if (characters.getById(builtIn.id) == null) {
            characters.save(builtIn)
        }
    }
}
