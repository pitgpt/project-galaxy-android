package com.pitgpt.galaxy.ui

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ComfyUiSettingsCancellationTest {
    @Test
    fun connectionCheckUsesFallbackForOrdinaryFailure() = runBlocking {
        val result = withFallbackPreservingCancellation(
            block = { throw IOException("temporary connection failure") },
            fallback = { "Проверь подключение." }
        )

        assertEquals("Проверь подключение.", result)
    }

    @Test
    fun connectionCheckPropagatesCoroutineCancellation() {
        val cancellation = CancellationException("settings screen left composition")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                withFallbackPreservingCancellation(
                    block = { throw cancellation },
                    fallback = { "Проверь подключение." }
                )
            }
        }

        assertSame(cancellation, thrown)
    }
}
