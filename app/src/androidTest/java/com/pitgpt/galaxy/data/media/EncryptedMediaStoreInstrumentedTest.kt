package com.pitgpt.galaxy.data.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.R
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedMediaStoreInstrumentedTest {
    private lateinit var directory: File
    private lateinit var keyAlias: String
    private lateinit var keyProvider: AndroidKeystoreMediaKeyProvider
    private lateinit var store: EncryptedMediaStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("galaxy-keystore-media-test").toFile()
        keyAlias = "galaxy_media_instrumented_test_${UUID.randomUUID()}"
        keyProvider = AndroidKeystoreMediaKeyProvider(keyAlias)
        store = EncryptedMediaStore(
            directory = directory,
            keyProvider = keyProvider,
            maxAssetBytes = 4 * 1024
        )
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        if (::keyAlias.isInitialized) {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    @Test
    fun androidKeystoreKeyIsNonExportableAndCanBeReadBack() {
        val created = keyProvider.getOrCreateKey()
        val loaded = keyProvider.getExistingKey()

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
    fun rejectsTamperedCiphertextWithAndroidKeystoreKey() {
        val plaintext = "authenticated-media-payload".toByteArray()
        val metadata = store.save("tampered_asset", "image/png", plaintext)
        val ciphertextFile = File(directory, "${metadata.assetId}.gcm")
        val tampered = ciphertextFile.readBytes()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
        ciphertextFile.writeBytes(tampered)

        assertThrows(AEADBadTagException::class.java) {
            store.read(metadata.assetId, metadata.mimeType)
        }
    }

    @Test
    fun missingKeystoreKeyPreservesCiphertextAndDoesNotCreateReplacement() {
        val metadata = store.save("missing_key_asset", "image/png", "private-media".toByteArray())
        val ciphertextFile = File(directory, "${metadata.assetId}.gcm")
        val ciphertextBefore = ciphertextFile.readBytes()
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

        assertTrue("The test-owned key must exist before simulated key loss", keyStore.containsAlias(keyAlias))
        keyStore.deleteEntry(keyAlias)
        assertFalse("The test-owned key must be absent after simulated key loss", keyStore.containsAlias(keyAlias))

        assertThrows(IllegalStateException::class.java) {
            store.read(metadata.assetId, metadata.mimeType)
        }

        assertTrue("The encrypted asset must remain available for explicit recovery", store.contains(metadata.assetId))
        assertTrue(ciphertextFile.isFile)
        assertArrayEquals("Missing-key reads must not modify ciphertext", ciphertextBefore, ciphertextFile.readBytes())
        assertFalse("Reading must not generate a replacement Android Keystore key", keyStore.containsAlias(keyAlias))

        assertThrows(IllegalStateException::class.java) {
            store.save("new_asset_after_key_loss", "image/png", byteArrayOf(1, 2, 3))
        }

        assertFalse(File(directory, "new_asset_after_key_loss.gcm").exists())
        assertArrayEquals("A failed save must leave the original ciphertext unchanged", ciphertextBefore, ciphertextFile.readBytes())
        assertFalse("Saving beside orphaned ciphertext must not generate a replacement key", keyStore.containsAlias(keyAlias))
    }

    @Test
    fun backupAndDeviceTransferRulesExcludeEncryptedMediaDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyRules = readExclusions(context.resources.getXml(R.xml.backup_rules))
        val extractionRules = readExclusions(context.resources.getXml(R.xml.data_extraction_rules))
        val mediaExclusion = "file" to "galaxy_media/"

        assertTrue("Legacy full-backup rules must exclude encrypted media",
            mediaExclusion in legacyRules.getValue("root"))
        assertTrue("Cloud backup rules must exclude encrypted media",
            mediaExclusion in extractionRules.getValue("cloud-backup"))
        assertTrue("Device-transfer rules must exclude encrypted media",
            mediaExclusion in extractionRules.getValue("device-transfer"))
        assertTrue(("database" to ".") in extractionRules.getValue("cloud-backup"))
        assertTrue(("database" to ".") in extractionRules.getValue("device-transfer"))
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }

    private fun readExclusions(
        parser: android.content.res.XmlResourceParser
    ): Map<String, Set<Pair<String, String>>> {
        val exclusions = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        var section = "root"
        try {
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "cloud-backup", "device-transfer" -> section = parser.name
                        "exclude" -> exclusions.getOrPut(section) { mutableSetOf() }.add(
                            parser.getAttributeValue(null, "domain").orEmpty() to
                                parser.getAttributeValue(null, "path").orEmpty()
                        )
                    }
                } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG &&
                    (parser.name == "cloud-backup" || parser.name == "device-transfer")
                ) {
                    section = "root"
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return exclusions
    }
}
