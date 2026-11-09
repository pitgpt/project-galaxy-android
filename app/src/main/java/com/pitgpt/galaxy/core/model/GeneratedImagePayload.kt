package com.pitgpt.galaxy.core.model

/** Validated image metadata and its bounded bytes, held in memory until encrypted persistence. */
data class GeneratedImagePayload(
    val asset: GeneratedImageAsset,
    val mimeType: String,
    val bytes: ByteArray,
    val sha256: String
) {
    val byteSize: Long get() = bytes.size.toLong()

    init {
        require(bytes.isNotEmpty()) { "Generated image payload must not be empty" }
        require(mimeType.isNotBlank()) { "Generated image MIME type must not be blank" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Generated image checksum is invalid" }
    }
}
