package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ImageGenerationState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageGenerationPanelTest {
    @Test
    fun locksConversationNavigationWhileSubmittingOrTrackingAnActiveJob() {
        assertTrue(imageGenerationNavigationLocked(isSubmitting = true, hasActiveJob = false))
        assertTrue(imageGenerationNavigationLocked(isSubmitting = false, hasActiveJob = true))
        assertTrue(imageGenerationNavigationLocked(isSubmitting = true, hasActiveJob = true))
        assertFalse(imageGenerationNavigationLocked(isSubmitting = false, hasActiveJob = false))
    }

    @Test
    fun nonTerminalProviderStatesRemainActiveAfterStatusPollingFailure() {
        assertFalse(isTerminalImageGenerationState(ImageGenerationState.QUEUED))
        assertFalse(isTerminalImageGenerationState(ImageGenerationState.RUNNING))
        assertTrue(isTerminalImageGenerationState(ImageGenerationState.COMPLETED))
        assertTrue(isTerminalImageGenerationState(ImageGenerationState.FAILED))
        assertTrue(isTerminalImageGenerationState(ImageGenerationState.CANCELLED))
        assertTrue(
            imageGenerationNavigationLocked(
                isSubmitting = false,
                hasActiveJob = !isTerminalImageGenerationState(ImageGenerationState.RUNNING)
            )
        )
    }

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

    @Test
    fun rejectsInvalidOrExcessiveGeneratedImageDimensions() {
        assertFalse(isSafeGeneratedImageDimensions(0, 1024))
        assertFalse(isSafeGeneratedImageDimensions(8193, 1))
        assertFalse(isSafeGeneratedImageDimensions(8192, 2049))
        assertTrue(isSafeGeneratedImageDimensions(1024, 1024))
        assertTrue(isSafeGeneratedImageDimensions(8192, 2048))
    }

    @Test
    fun downsamplesLargeGeneratedImagesForMemoryBoundedPreview() {
        assertEquals(1, generatedImagePreviewSampleSize(1024, 1024))
        assertEquals(2, generatedImagePreviewSampleSize(4096, 2048))
        assertEquals(4, generatedImagePreviewSampleSize(8192, 2048))
        assertThrows(IllegalArgumentException::class.java) {
            generatedImagePreviewSampleSize(8192, 2049)
        }
    }
}
