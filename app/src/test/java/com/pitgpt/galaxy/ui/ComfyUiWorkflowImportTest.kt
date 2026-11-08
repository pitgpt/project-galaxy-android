package com.pitgpt.galaxy.ui

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ComfyUiWorkflowImportTest {
    @Test
    fun readsValidUtf8WorkflowWithinLimit() {
        val json = """{"1":{"inputs":{"text":"Привет"}}}"""

        val result = readBoundedWorkflowJson(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))

        assertEquals(json, result)
    }

    @Test
    fun acceptsDocumentAtExactByteLimit() {
        val bytes = ByteArray(MAX_WORKFLOW_IMPORT_BYTES) { 'x'.code.toByte() }

        val result = readBoundedWorkflowJson(ByteArrayInputStream(bytes))

        assertEquals(MAX_WORKFLOW_IMPORT_BYTES, result.length)
    }

    @Test
    fun rejectsDocumentAboveByteLimit() {
        val bytes = ByteArray(MAX_WORKFLOW_IMPORT_BYTES + 1) { 'x'.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            readBoundedWorkflowJson(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun rejectsMalformedUtf8() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)

        assertThrows(CharacterCodingException::class.java) {
            readBoundedWorkflowJson(ByteArrayInputStream(malformed))
        }
    }

    @Test
    fun rejectsEmptyDocument() {
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedWorkflowJson(ByteArrayInputStream(ByteArray(0)))
        }
    }
}
