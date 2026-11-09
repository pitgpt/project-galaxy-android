package com.pitgpt.galaxy.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Test

class ComfyUiSettingsOperationTest {
    @Test
    fun cancellationIsRethrownUnchanged() = runBlocking {
        val cancellation = CancellationException("settings screen disposed")

        val thrown = runCatching {
            captureComfyUiSettingsResult<String> { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun ordinaryFailureIsReturnedForUiHandling() = runBlocking {
        val failure = IllegalStateException("settings unavailable")

        val result = captureComfyUiSettingsResult<String> { throw failure }

        assertSame(failure, result.exceptionOrNull())
    }
}
