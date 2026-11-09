package com.pitgpt.galaxy.data.providers

import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRoutePreference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderSettingsIoTest {
    @Test
    fun loadAndSaveRunOnTheConfiguredDispatcher() = runBlocking {
        val dispatcherThread = AtomicReference<Thread?>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "galaxy-settings-io-test").also(dispatcherThread::set)
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
            assertSame(dispatcherThread.get(), repository.loadThread)
            assertSame(dispatcherThread.get(), repository.saveThread)
        } finally {
            dispatcher.close()
        }
    }

    private class RecordingRepository : ProviderSettingsRepository {
        var settings = ProviderSettings()
        var loadThread: Thread? = null
        var saveThread: Thread? = null

        override fun load(): ProviderSettings {
            loadThread = Thread.currentThread()
            return settings
        }

        override fun save(settings: ProviderSettings) {
            saveThread = Thread.currentThread()
            this.settings = settings
        }

        override fun clear() = Unit
    }
}
