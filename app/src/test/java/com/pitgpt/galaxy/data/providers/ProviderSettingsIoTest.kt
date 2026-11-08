package com.pitgpt.galaxy.data.providers

import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRoutePreference
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSettingsIoTest {
    @Test
    fun loadAndSaveRunOnTheConfiguredDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "galaxy-settings-io-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val repository = RecordingRepository()
            val io = ProviderSettingsIo(repository, dispatcher)
            val expected = ProviderSettings(
                comfyUi = ComfyUiSavedSettings(
                    lanBaseUrl = "https://comfy.example.test",
                    preference = ComfyUiRoutePreference.LAN_ONLY
                )
            )

            assertEquals(ProviderSettings(), io.load())
            io.save(expected)

            assertEquals(expected, repository.settings)
            assertEquals("galaxy-settings-io-test", repository.loadThread)
            assertEquals("galaxy-settings-io-test", repository.saveThread)
        } finally {
            dispatcher.close()
        }
    }

    private class RecordingRepository : ProviderSettingsRepository {
        var settings = ProviderSettings()
        var loadThread: String? = null
        var saveThread: String? = null

        override fun load(): ProviderSettings {
            loadThread = Thread.currentThread().name
            return settings
        }

        override fun save(settings: ProviderSettings) {
            saveThread = Thread.currentThread().name
            this.settings = settings
        }

        override fun clear() = Unit
    }
}
