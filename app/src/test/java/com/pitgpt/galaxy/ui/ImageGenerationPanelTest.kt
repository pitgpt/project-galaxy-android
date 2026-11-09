package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ImageGenerationState
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGenerationPanelTest {
    @Test
    fun mapsEveryProviderStateToARussianLabel() {
        assertEquals("В очереди", imageGenerationStatusLabel(ImageGenerationState.QUEUED))
        assertEquals("Выполняется", imageGenerationStatusLabel(ImageGenerationState.RUNNING))
        assertEquals("Готово", imageGenerationStatusLabel(ImageGenerationState.COMPLETED))
        assertEquals("Ошибка генерации", imageGenerationStatusLabel(ImageGenerationState.FAILED))
        assertEquals("Отменено", imageGenerationStatusLabel(ImageGenerationState.CANCELLED))
    }
}
