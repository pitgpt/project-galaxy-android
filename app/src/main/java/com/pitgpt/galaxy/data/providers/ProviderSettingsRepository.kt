package com.pitgpt.galaxy.data.providers

/** Persistence boundary for provider configuration; implementations may use encrypted device storage. */
interface ProviderSettingsRepository {
    fun load(): ProviderSettings
    fun save(settings: ProviderSettings)
    fun clear()
}
