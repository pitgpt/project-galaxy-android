package com.pitgpt.galaxy.data.media

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

data class EncryptedMediaAsset(
    val assetId: String,
    val mimeType: String,
    val byteSize: Int,
    val sha256: String
)

/**
 * Stores image bytes only as AES-GCM ciphertext in app-private storage.
 *
 * The temporary file contains ciphertext only. A completed encrypted file is atomically
 * promoted into place; reads decrypt into memory and never create a plaintext temporary file.
 */
class EncryptedMediaStore(
    private val directory: File,
    private val keyProvider: MediaEncryptionKeyProvider,
    private val maxAssetBytes: Int = DEFAULT_MAX_ASSET_BYTES
) {
    init {
        require(maxAssetBytes > 0) { "The media size limit must be positive." }
        require(maxAssetBytes <= Int.MAX_VALUE - HEADER_BYTES - GCM_TAG_BYTES - 1) {
            "The media size limit is too large."
        }
    }

    @Synchronized
    fun save(assetId: String, mimeType: String, plaintext: ByteArray): EncryptedMediaAsset {
        validateAssetId(assetId)
        validateMimeType(mimeType)
        require(plaintext.isNotEmpty()) { "Media content must not be empty." }
        require(plaintext.size <= maxAssetBytes) { "Media content exceeds the configured size limit." }

        ensureDirectory()
        val destination = assetFile(assetId)
        check(!destination.exists()) { "A media asset with this ID already exists." }

        // Never generate a replacement key when committed ciphertext already exists.
        val key = if (hasStoredAssets()) keyProvider.getExistingKey() else keyProvider.getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == IV_BYTES) { "The media encryption provider returned an invalid IV." }
        cipher.updateAAD(associatedData(assetId, mimeType))
        val encrypted = cipher.doFinal(plaintext)

        val temporary = File.createTempFile(TEMP_PREFIX, ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(MAGIC)
                output.write(FORMAT_VERSION)
                output.write(cipher.iv)
                output.write(encrypted)
                output.fd.sync()
            }
            check(!destination.exists()) { "A media asset with this ID already exists." }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (temporary.exists()) temporary.delete()
        }

        return EncryptedMediaAsset(
            assetId = assetId,
            mimeType = mimeType,
            byteSize = plaintext.size,
            sha256 = sha256(plaintext)
        )
    }

    @Synchronized
    fun read(assetId: String, mimeType: String): ByteArray {
        validateAssetId(assetId)
        validateMimeType(mimeType)
        val source = assetFile(assetId)
        check(source.isFile) { "The requested encrypted media asset does not exist." }

        val maximumFileBytes = HEADER_BYTES + maxAssetBytes + GCM_TAG_BYTES
        val fileLength = source.length()
        require(fileLength in (HEADER_BYTES + GCM_TAG_BYTES + 1).toLong()..maximumFileBytes.toLong()) {
            "Encrypted media file has an invalid size."
        }

        // The length check above is only an early rejection. The file may change between
        // length() and open(), so the stream itself must enforce the same hard upper bound.
        val encoded = FileInputStream(source).use { readBounded(it, maximumFileBytes) }
        val input = DataInputStream(ByteArrayInputStream(encoded))
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Encrypted media header is invalid." }
        require(input.readUnsignedByte() == FORMAT_VERSION) { "Encrypted media version is unsupported." }
        val iv = ByteArray(IV_BYTES)
        input.readFully(iv)
        val ciphertext = ByteArray(input.available())
        input.readFully(ciphertext)
        require(ciphertext.size > GCM_TAG_BYTES) { "Encrypted media payload is truncated." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider.getExistingKey(),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(associatedData(assetId, mimeType))
        return cipher.doFinal(ciphertext).also {
            require(it.isNotEmpty() && it.size <= maxAssetBytes) {
                "Decrypted media content has an invalid size."
            }
        }
    }

    @Synchronized
    fun delete(assetId: String): Boolean {
        validateAssetId(assetId)
        val file = assetFile(assetId)
        if (!file.exists()) return false
        check(file.isFile && file.delete()) { "The encrypted media asset could not be deleted." }
        return true
    }

    @Synchronized
    fun contains(assetId: String): Boolean {
        validateAssetId(assetId)
        return assetFile(assetId).isFile
    }

    private fun ensureDirectory() {
        check(directory.exists() || directory.mkdirs()) {
            "The encrypted media directory could not be created."
        }
        check(directory.isDirectory) { "The encrypted media path is not a directory." }
    }

    private fun hasStoredAssets(): Boolean {
        val assets = directory.listFiles { file -> file.isFile && file.name.endsWith(FILE_EXTENSION) }
            ?: throw IllegalStateException("The encrypted media directory could not be inspected.")
        return assets.isNotEmpty()
    }

    private fun assetFile(assetId: String): File = File(directory, "$assetId$FILE_EXTENSION")

    private fun validateAssetId(assetId: String) {
        require(ASSET_ID_PATTERN.matches(assetId)) { "Media asset ID is invalid." }
    }

    private fun validateMimeType(mimeType: String) {
        require(mimeType in SUPPORTED_MIME_TYPES) { "Media MIME type is not supported." }
    }

    private fun associatedData(assetId: String, mimeType: String): ByteArray =
        "$FORMAT_VERSION\u0000$assetId\u0000$mimeType".toByteArray(Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_MAX_ASSET_BYTES = 25 * 1024 * 1024
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val HEADER_BYTES = 4 + 1 + IV_BYTES
        private const val FILE_EXTENSION = ".gcm"
        private const val TEMP_PREFIX = "galaxy-media-"
        private const val READ_BUFFER_BYTES = 8 * 1024
        private val MAGIC = byteArrayOf(0x47, 0x4C, 0x58, 0x4D) // GLXM
        private val ASSET_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")

        /**
         * Reads at most maxBytes + 1 bytes. The extra byte detects growth beyond the
         * configured limit without allowing an unbounded read or allocation.
         */
        internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
            require(maxBytes in 0 until Int.MAX_VALUE) { "The media read limit is invalid." }
            val output = ByteArrayOutputStream(minOf(maxBytes, READ_BUFFER_BYTES))
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var totalBytes = 0

            while (true) {
                val remaining = maxBytes - totalBytes
                val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
                if (count < 0) break
                if (count == 0) {
                    val nextByte = input.read()
                    if (nextByte < 0) break
                    require(totalBytes < maxBytes) {
                        "Encrypted media file exceeds the configured size limit."
                    }
                    output.write(nextByte)
                    totalBytes++
                    continue
                }

                require(count <= remaining) {
                    "Encrypted media file exceeds the configured size limit."
                }
                output.write(buffer, 0, count)
                totalBytes += count
            }

            return output.toByteArray()
        }

        /** Uses filesDir/galaxy_media, which is explicitly excluded from Android backup. */
        fun fromContext(context: Context): EncryptedMediaStore =
            EncryptedMediaStore(
                File(context.applicationContext.filesDir, MEDIA_DIRECTORY),
                AndroidKeystoreMediaKeyProvider()
            )

        const val MEDIA_DIRECTORY = "galaxy_media"
    }
}
