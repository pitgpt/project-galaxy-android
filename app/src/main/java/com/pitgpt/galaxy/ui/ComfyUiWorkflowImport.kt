package com.pitgpt.galaxy.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_WORKFLOW_IMPORT_BYTES = 2 * 1024 * 1024

/**
 * Reads a workflow document with a strict byte limit and rejects malformed UTF-8.
 * The caller owns the dispatcher; this function closes the supplied stream.
 */
internal fun readBoundedWorkflowJson(input: InputStream): String {
    val output = ByteArrayOutputStream()
    input.use { stream ->
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(count <= MAX_WORKFLOW_IMPORT_BYTES - total) {
                "Workflow file exceeds the size limit"
            }
            total += count
            output.write(buffer, 0, count)
        }
    }

    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    require(text.isNotBlank()) { "Workflow file is empty" }
    return text
}
