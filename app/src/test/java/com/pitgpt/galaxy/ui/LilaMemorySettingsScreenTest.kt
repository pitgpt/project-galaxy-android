package com.pitgpt.galaxy.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LilaMemorySettingsScreenTest {
    @Test
    fun confirmedDeleteShowsSuccessOnlyWhenCoreDeletedTheMemory() {
        assertEquals("Воспоминание удалено.", lilaMemoryDeleteNotice(true))
    }

    @Test
    fun missingOrInaccessibleMemoryDoesNotShowFalseSuccess() {
        assertEquals(
            "Запись уже отсутствует или недоступна.",
            lilaMemoryDeleteNotice(false)
        )
    }
}
