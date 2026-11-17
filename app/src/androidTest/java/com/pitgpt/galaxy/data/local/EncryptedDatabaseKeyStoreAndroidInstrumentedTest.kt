package com.pitgpt.galaxy.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseKeyStoreAndroidInstrumentedTest {
    @Test
    fun androidKeystoreUnwrapProducesSqlCipherKeyThatSurvivesHelperReopen() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_db_wrap_$suffix"
        val databaseName = "keystore-sqlcipher-$suffix.db"
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
        var iv: ByteArray? = null
        var encryptedKey: ByteArray? = null
        var wrappedPayload: ByteArray? = null
        var encodedPayload: ByteArray? = null
        var decodedPayload: ByteArray? = null
        var unwrappedKey: ByteArray? = null
        var helper: SupportSQLiteOpenHelper? = null
        val issuedPassphrases = mutableListOf<ByteArray>()

        try {
            val wrappingKey = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
            ).run {
                init(
                    KeyGenParameterSpec.Builder(
                        wrappingAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generateKey()
            }

            val encryptCipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey)
            }
            iv = encryptCipher.iv.copyOf()
            encryptedKey = encryptCipher.doFinal(databaseKey)
            wrappedPayload = iv!! + encryptedKey!!
            encodedPayload = Base64.encode(wrappedPayload, Base64.NO_WRAP)
            decodedPayload = Base64.decode(encodedPayload, Base64.NO_WRAP)

            val sqlCipherKey = decryptWrappedDatabaseKeyPayload(decodedPayload!!) { ivBytes, ciphertext ->
                Cipher.getInstance(TRANSFORMATION).run {
                    init(
                        Cipher.DECRYPT_MODE,
                        wrappingKey,
                        GCMParameterSpec(TAG_BITS, ivBytes)
                    )
                    doFinal(ciphertext)
                }
            }
            unwrappedKey = sqlCipherKey
            assertArrayEquals(databaseKey, sqlCipherKey)
            assertTrue(
                "The decoded wrapped payload must be zeroed after Android Keystore decryption",
                decodedPayload!!.all { it == 0.toByte() }
            )

            val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE keystore_round_trip (value TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        error("The isolated Keystore/SQLCipher test has no migrations.")
                    }
                })
                .build()

            helper = ZeroizingSqlCipherOpenHelperFactory(
                passphraseProvider = {
                    Base64.encode(sqlCipherKey, Base64.NO_WRAP).also(issuedPassphrases::add)
                },
                enableWriteAheadLogging = true
            ).create(configuration)

            val firstDatabase = helper!!.writableDatabase
            assertEquals(1, issuedPassphrases.size)
            assertTrue(
                "The first SQLCipher passphrase buffer must be zeroed after open",
                issuedPassphrases[0].all { it == 0.toByte() }
            )
            firstDatabase.execSQL(
                "INSERT INTO keystore_round_trip(value) VALUES ('android-keystore-round-trip')"
            )

            helper!!.close()
            assertFalse("Closing the helper must close the first SQLCipher connection", firstDatabase.isOpen)

            val reopenedDatabase = helper!!.writableDatabase
            assertEquals("A closed helper must obtain a fresh passphrase", 2, issuedPassphrases.size)
            assertTrue(
                "The reopened SQLCipher passphrase buffer must also be zeroed",
                issuedPassphrases[1].all { it == 0.toByte() }
            )
            reopenedDatabase.query("SELECT value FROM keystore_round_trip").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("android-keystore-round-trip", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
        } finally {
            runCatching { helper?.close() }
            issuedPassphrases.forEach { it.fill(0) }
            unwrappedKey?.fill(0)
            decodedPayload?.fill(0)
            encodedPayload?.fill(0)
            wrappedPayload?.fill(0)
            encryptedKey?.fill(0)
            iv?.fill(0)
            databaseKey.fill(0)
            runCatching {
                if (keyStore.containsAlias(wrappingAlias)) keyStore.deleteEntry(wrappingAlias)
            }
            context.deleteDatabase(databaseName)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(context.getDatabasePath(databaseName).path + suffix).delete()
            }
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DATABASE_KEY_BYTES = 32
        const val TAG_BITS = 128
    }
}
