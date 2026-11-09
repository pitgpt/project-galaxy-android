package com.pitgpt.galaxy.data.providers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves the synchronous provider-vault API off the Android main thread.
 *
 * The underlying repository remains synchronous for existing callers, while Compose screens can
 * safely use this adapter for Keystore and SharedPreferences operations.
 */
class ProviderSettingsIo(
    private val repository: ProviderSettingsRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun load(): ProviderSettings = withContext(dispatcher) {
        repository.load()
    }

    suspend fun save(settings: ProviderSettings) = withContext(dispatcher) {
        repository.save(settings)
    }
}
