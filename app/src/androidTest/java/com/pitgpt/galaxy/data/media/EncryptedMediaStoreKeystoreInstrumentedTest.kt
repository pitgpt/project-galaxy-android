package com.pitgpt.galaxy.data.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
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
    private lateinit var keyAlias: String
    private lateinit var keyProvider: AndroidKeystoreMediaKeyProvider

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keyAlias = "galaxy_media_test_${UUID.randomUUID()}"
        keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
        keyProvider = AndroidKeystoreMediaKeyProvider(keyAlias)
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "galaxy-media-keystore-").toFile()
    }

    @After
    fun tearDown() {
        if (::directory.isInitialized) directory.deleteRecursively()
        if (::keyStore.isInitialized && ::keyAlias.isInitialized && keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
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

        keyStore.deleteEntry(keyAlias)

        val readFailure = runCatching { store.read("existing_asset", "image/jpeg") }.exceptionOrNull()
        assertTrue("Reading with a missing key must fail closed", readFailure is IllegalStateException)

        val saveFailure = runCatching {
            store.save("new_asset", "image/jpeg", byteArrayOf(6, 7, 8))
        }.exceptionOrNull()
        assertTrue(
            "Saving beside existing ciphertext must not replace a missing key",
            saveFailure is IllegalStateException
        )
        assertTrue(assetFile.isFile)
        assertArrayEquals(originalCiphertext, assetFile.readBytes())
        assertFalse(File(directory, "new_asset.gcm").exists())
        assertFalse("A replacement Keystore key must not be generated", keyStore.containsAlias(keyAlias))
    }

    private fun containsBytes(container: ByteArray, candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > container.size) return false
        return (0..container.size - candidate.size).any { start ->
            candidate.indices.all { offset -> container[start + offset] == candidate[offset] }
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
