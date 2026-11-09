package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ImageGenerationState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    @Test
    fun statusRefreshFallsBackToCurrentStatusOnOrdinaryFailure() = runBlocking {
        assertEquals(
            "queued",
            refreshStatusPreservingCancellation("queued") {
                throw IOException("temporary status lookup failure")
            }
        )
    }

    @Test
    fun statusRefreshPropagatesCoroutineCancellation() {
        val cancellation = CancellationException("scope cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                refreshStatusPreservingCancellation("queued") {
                    throw cancellation
                }
            }
        }

        assertSame(cancellation, thrown)
    }
}
