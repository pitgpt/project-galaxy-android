package com.pitgpt.galaxy.ui

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun buildsAndValidatesWorkflowOnProvidedDispatcher() = runBlocking {
        var dispatched = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched = true
                block.run()
            }
        }

        val template = buildComfyUiWorkflowTemplate(
            id = "test-workflow-v1",
            workflowJson = """{"1":{"inputs":{"text":""}}}""",
            positiveNodeId = "1",
            positiveInputName = "text",
            negativeNodeId = "",
            negativeInputName = "text",
            widthNodeId = "",
            widthInputName = "width",
            heightNodeId = "",
            heightInputName = "height",
            seedNodeId = "",
            seedInputName = "seed",
            dispatcher = dispatcher
        )

        assertTrue("Workflow parsing must use the supplied background dispatcher", dispatched)
        assertEquals("1", template.positivePrompt.nodeId)
    }

    @Test
    fun rejectsInvalidMappingBeforeReturningWorkflowTemplate() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                buildComfyUiWorkflowTemplate(
                    id = "test-workflow-v1",
                    workflowJson = """{"1":{"inputs":{"text":""}}}""",
                    positiveNodeId = "1",
                    positiveInputName = "missing",
                    negativeNodeId = "",
                    negativeInputName = "text",
                    widthNodeId = "",
                    widthInputName = "width",
                    heightNodeId = "",
                    heightInputName = "height",
                    seedNodeId = "",
                    seedInputName = "seed"
                )
            }
        }
    }
}
