package com.pitgpt.galaxy.data.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.R
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedMediaStoreInstrumentedTest {
    private lateinit var directory: File
    private lateinit var store: EncryptedMediaStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("galaxy-keystore-media-test").toFile()
        store = EncryptedMediaStore(
            directory = directory,
            keyProvider = AndroidKeystoreMediaKeyProvider(),
            maxAssetBytes = 4 * 1024
        )
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun androidKeystoreKeyIsNonExportableAndCanBeReadBack() {
        val provider = AndroidKeystoreMediaKeyProvider()
        val created = provider.getOrCreateKey()
        val loaded = provider.getExistingKey()

        assertNull("Android Keystore must not export the AES key bytes", created.encoded)
        assertNull("Reloaded Android Keystore key must remain non-exportable", loaded.encoded)
        val plaintext = "keystore-key-round-trip".toByteArray()
        val encryptor = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, created)
        }
        val ciphertext = encryptor.doFinal(plaintext)
        val decryptor = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, loaded, GCMParameterSpec(128, encryptor.iv))
        }
        assertArrayEquals(plaintext, decryptor.doFinal(ciphertext))
    }

    @Test
    fun encryptedMediaRoundTripsWithoutWritingPlaintextToDisk() {
        val plaintext = "Galaxy encrypted media instrumentation payload".toByteArray()
        val metadata = store.save("instrumented_asset", "image/png", plaintext)
        val ciphertextFile = File(directory, "${metadata.assetId}.gcm")
        val onDisk = ciphertextFile.readBytes()

        assertTrue(ciphertextFile.isFile)
        assertFalse(
            String(onDisk, Charsets.ISO_8859_1).contains(
                String(plaintext, Charsets.ISO_8859_1)
            )
        )
        assertArrayEquals(plaintext, store.read(metadata.assetId, metadata.mimeType))
    }

    @Test
    fun backupAndDeviceTransferRulesExcludeEncryptedMediaDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyRules = readExclusions(context.resources.getXml(R.xml.backup_rules))
        val extractionRules = readExclusions(context.resources.getXml(R.xml.data_extraction_rules))

        assertTrue("Legacy full-backup rules must exclude encrypted media",
            ("file" to "galaxy_media/") in legacyRules)
        assertTrue("Cloud/device-transfer rules must exclude encrypted media",
            ("file" to "galaxy_media/") in extractionRules)
        assertTrue(("database" to ".") in extractionRules)
    }

    private fun readExclusions(parser: android.content.res.XmlResourceParser): Set<Pair<String, String>> {
        val exclusions = mutableSetOf<Pair<String, String>>()
        try {
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude") {
                    exclusions += parser.getAttributeValue(null, "domain").orEmpty() to
                        parser.getAttributeValue(null, "path").orEmpty()
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return exclusions
    }
}
