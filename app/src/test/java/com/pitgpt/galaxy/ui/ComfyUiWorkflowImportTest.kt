package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

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
    @Test
    fun buildsConnectedSdxlApiWorkflowAndMapsConversationPrompt() {
        val template = buildSdxlWorkflowTemplate("juggernaut-xl/model.safetensors")
        val graph = JSONObject(template.workflowJson)

        assertEquals(SDXL_WORKFLOW_ID, template.id)
        assertEquals(
            "juggernaut-xl/model.safetensors",
            graph.getJSONObject("4").getJSONObject("inputs").getString("ckpt_name")
        )
        assertEquals("CheckpointLoaderSimple", graph.getJSONObject("4").getString("class_type"))
        assertEquals("CLIPTextEncode", graph.getJSONObject("5").getString("class_type"))
        assertEquals("CLIPTextEncode", graph.getJSONObject("6").getString("class_type"))
        assertEquals("KSampler", graph.getJSONObject("3").getString("class_type"))
        assertEquals("VAEDecode", graph.getJSONObject("8").getString("class_type"))
        assertEquals("SaveImage", graph.getJSONObject("9").getString("class_type"))
        assertEquals("4", graph.getJSONObject("3").getJSONObject("inputs").getJSONArray("model").getString(0))
        assertEquals("5", graph.getJSONObject("3").getJSONObject("inputs").getJSONArray("positive").getString(0))
        assertEquals("6", graph.getJSONObject("3").getJSONObject("inputs").getJSONArray("negative").getString(0))
        assertEquals("7", graph.getJSONObject("3").getJSONObject("inputs").getJSONArray("latent_image").getString(0))
        assertEquals("8", graph.getJSONObject("9").getJSONObject("inputs").getJSONArray("images").getString(0))
        assertEquals(1024, graph.getJSONObject("7").getJSONObject("inputs").getInt("width"))
        assertEquals(1024, graph.getJSONObject("7").getJSONObject("inputs").getInt("height"))

        val request = ImageGenerationRequest(
            id = "sdxl-request",
            conversationId = "conversation",
            characterId = "lila",
            prompt = "adult silver-haired heroine in a moonlit garden",
            negativePrompt = "blurry",
            workflowId = SDXL_WORKFLOW_ID,
            width = 768,
            height = 896,
            seed = 42L,
            createdAtEpochMillis = 1L
        )
        val mapped = template.buildPrompt(request)
        assertEquals(
            request.prompt,
            mapped.getJSONObject("5").getJSONObject("inputs").getString("text")
        )
        assertEquals(
            "blurry",
            mapped.getJSONObject("6").getJSONObject("inputs").getString("text")
        )
        assertEquals(768, mapped.getJSONObject("7").getJSONObject("inputs").getInt("width"))
        assertEquals(896, mapped.getJSONObject("7").getJSONObject("inputs").getInt("height"))
        assertEquals(42L, mapped.getJSONObject("3").getJSONObject("inputs").getLong("seed"))
    }

    @Test
    fun rejectsAbsoluteOrTraversalCheckpointPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            buildSdxlWorkflowTemplate("../outside.safetensors")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildSdxlWorkflowTemplate("/absolute/model.safetensors")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildSdxlWorkflowTemplate("folder\\\\model.safetensors")
        }
    }

}
