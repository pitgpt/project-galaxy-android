package com.pitgpt.galaxy.data.providers

/** Returns provider settings after an explicit user-confirmed provider removal. */
internal fun ProviderSettings.withProviderRemoved(providerId: String): ProviderSettings {
    val remaining = providers.filterNot { it.id == providerId }
    return copy(
        providers = remaining,
        activeProviderId = if (activeProviderId == providerId) remaining.firstOrNull()?.id else activeProviderId
    )
}
