package com.pitgpt.galaxy.data.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedMediaStoreKeystoreInstrumentedTest {
    private lateinit var directory: File
    private lateinit var keyStore: KeyStore
    private val keyProvider = AndroidKeystoreMediaKeyProvider()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
            if (containsAlias(MEDIA_KEY_ALIAS)) deleteEntry(MEDIA_KEY_ALIAS)
        }
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "galaxy-media-keystore-").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        if (::keyStore.isInitialized && keyStore.containsAlias(MEDIA_KEY_ALIAS)) {
            keyStore.deleteEntry(MEDIA_KEY_ALIAS)
        }
    }

    @Test
    fun encryptsMediaWithANonExportableAndroidKeystoreKey() {
        val store = EncryptedMediaStore(directory, keyProvider, maxAssetBytes = 4096)
        val plaintext = "private-media-payload-for-keystore-round-trip".toByteArray()

        store.save("keystore_asset", "image/png", plaintext)

        assertArrayEquals(plaintext, store.read("keystore_asset", "image/png"))
        assertNull("Android Keystore AES keys must not be exportable", keyProvider.getExistingKey().encoded)
        val ciphertext = File(directory, "keystore_asset.gcm").readBytes()
        assertFalse("Plaintext must never be written to the media file", containsBytes(ciphertext, plaintext))
    }

    @Test
    fun missingKeyPreservesCiphertextAndDoesNotCreateAReplacement() {
        val store = EncryptedMediaStore(directory, keyProvider, maxAssetBytes = 4096)
        store.save("existing_asset", "image/jpeg", byteArrayOf(1, 2, 3, 4, 5))
        val assetFile = File(directory, "existing_asset.gcm")
        val originalCiphertext = assetFile.readBytes()

        keyStore.deleteEntry(MEDIA_KEY_ALIAS)

        val readFailure = runCatching { store.read("existing_asset", "image/jpeg") }.exceptionOrNull()
        assertTrue("Reading with a missing key must fail closed", readFailure is IllegalStateException)

        val saveFailure = runCatching {
            store.save("new_asset", "image/jpeg", byteArrayOf(6, 7, 8))
        }.exceptionOrNull()
        assertTrue("Saving beside existing ciphertext must not replace a missing key", saveFailure is IllegalStateException)
        assertTrue(assetFile.isFile)
        assertArrayEquals(originalCiphertext, assetFile.readBytes())
        assertFalse(File(directory, "new_asset.gcm").exists())
        assertFalse("A replacement Keystore key must not be generated", keyStore.containsAlias(MEDIA_KEY_ALIAS))
    }

    private fun containsBytes(container: ByteArray, candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > container.size) return false
        return (0..container.size - candidate.size).any { start ->
            candidate.indices.all { offset -> container[start + offset] == candidate[offset] }
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val MEDIA_KEY_ALIAS = "galaxy_media_aes_gcm_v1"
    }
}
