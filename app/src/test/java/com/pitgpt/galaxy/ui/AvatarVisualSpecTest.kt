package com.pitgpt.galaxy.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarVisualSpecTest {
    @Test
    fun knownPresetsExposeStableTitlesAndSymbols() {
        assertEquals("Космос", avatarVisualSpec("avatar.cosmic").title)
        assertEquals("✦", avatarVisualSpec("avatar.cosmic").symbol)
        assertEquals("Кибер", avatarVisualSpec("avatar.cyber").title)
        assertEquals("⌁", avatarVisualSpec("avatar.cyber").symbol)
        assertEquals("Минимал", avatarVisualSpec("avatar.minimal").title)
        assertEquals("◉", avatarVisualSpec("avatar.minimal").symbol)
    }

    @Test
    fun nullAndUnknownIdsFallBackToCosmicPreset() {
        val cosmic = avatarVisualSpec("avatar.cosmic")
        assertEquals(cosmic, avatarVisualSpec(null))
        assertEquals(cosmic, avatarVisualSpec("avatar.unknown"))
    }
}
