package com.pitgpt.galaxy.data.providers.comfyui

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ComfyUiConnectionProbeTest {
    @Test
    fun propagatesCoroutineCancellationInsteadOfTreatingItAsEndpointFailure() {
        val cancellation = CancellationException("probe cancelled")
        val probe = ComfyUiConnectionProbe(
            ComfyUiHttpTransport { _, _, _, _, _ -> throw cancellation }
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                probe(ComfyUiConnectionConfig("http://192.168.1.20:8188"))
            }
        }

        assertSame(cancellation, thrown)
    }
}
