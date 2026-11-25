package com.pitgpt.galaxy.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_WORKFLOW_IMPORT_BYTES = 2 * 1024 * 1024
internal const val SDXL_WORKFLOW_ID = "lila.sdxl.v1"

/**
 * Builds a standard SDXL API graph so first-time setup does not require manual node mapping.
 * The checkpoint is a relative name under ComfyUI/models/checkpoints, never an absolute path.
 */
internal fun buildSdxlWorkflowTemplate(checkpointName: String): ComfyUiWorkflowTemplate {
    val checkpoint = checkpointName.trim()
    require(
        checkpoint.isNotEmpty() &&
            checkpoint.length <= 255 &&
            !checkpoint.startsWith("/") &&
            !checkpoint.contains('\\') &&
            !checkpoint.contains(':') &&
            checkpoint.none { it.code < 0x20 || it.code == 0x7f } &&
            checkpoint.split('/').none { it.isBlank() || it == "." || it == ".." }
    ) { "Checkpoint name must be a safe relative model path" }

    fun link(nodeId: String, outputIndex: Int) = JSONArray().put(nodeId).put(outputIndex)
    fun node(classType: String, inputs: JSONObject) =
        JSONObject().put("class_type", classType).put("inputs", inputs)

    val graph = JSONObject()
        .put(
            "4",
            node("CheckpointLoaderSimple", JSONObject().put("ckpt_name", checkpoint))
        )
        .put(
            "5",
            node(
                "CLIPTextEncode",
                JSONObject().put("text", "").put("clip", link("4", 1))
            )
        )
        .put(
            "6",
            node(
                "CLIPTextEncode",
                JSONObject().put("text", "").put("clip", link("4", 1))
            )
        )
        .put(
            "7",
            node(
                "EmptyLatentImage",
                JSONObject().put("width", 1024).put("height", 1024).put("batch_size", 1)
            )
        )
        .put(
            "3",
            node(
                "KSampler",
                JSONObject()
                    .put("seed", 0L)
                    .put("steps", 30)
                    .put("cfg", 5.5)
                    .put("sampler_name", "dpmpp_2m")
                    .put("scheduler", "karras")
                    .put("denoise", 1.0)
                    .put("model", link("4", 0))
                    .put("positive", link("5", 0))
                    .put("negative", link("6", 0))
                    .put("latent_image", link("7", 0))
            )
        )
        .put(
            "8",
            node(
                "VAEDecode",
                JSONObject().put("samples", link("3", 0)).put("vae", link("4", 2))
            )
        )
        .put(
            "9",
            node("SaveImage", JSONObject().put("images", link("8", 0)).put("filename_prefix", "lila"))
        )

    return ComfyUiWorkflowTemplate(
        id = SDXL_WORKFLOW_ID,
        workflowJson = graph.toString(),
        positivePrompt = ComfyUiNodeInput("5", "text"),
        negativePrompt = ComfyUiNodeInput("6", "text"),
        width = ComfyUiNodeInput("7", "width"),
        height = ComfyUiNodeInput("7", "height"),
        seed = ComfyUiNodeInput("3", "seed")
    ).also { it.validateMappings() }
}

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


/**
 * Parses and validates the workflow graph away from the Compose main thread.
 * Callers may inject a dispatcher in tests; production uses Dispatchers.Default.
 */
internal suspend fun buildComfyUiWorkflowTemplate(
    id: String,
    workflowJson: String,
    positiveNodeId: String,
    positiveInputName: String,
    negativeNodeId: String,
    negativeInputName: String,
    widthNodeId: String,
    widthInputName: String,
    heightNodeId: String,
    heightInputName: String,
    seedNodeId: String,
    seedInputName: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): ComfyUiWorkflowTemplate = withContext(dispatcher) {
    fun optionalNode(nodeId: String, inputName: String): ComfyUiNodeInput? {
        if (nodeId.isBlank()) return null
        return ComfyUiNodeInput(nodeId.trim(), inputName.trim())
    }

    ComfyUiWorkflowTemplate(
        id = id.trim(),
        workflowJson = workflowJson,
        positivePrompt = ComfyUiNodeInput(positiveNodeId.trim(), positiveInputName.trim()),
        negativePrompt = optionalNode(negativeNodeId, negativeInputName),
        width = optionalNode(widthNodeId, widthInputName),
        height = optionalNode(heightNodeId, heightInputName),
        seed = optionalNode(seedNodeId, seedInputName)
    ).also { it.validateMappings() }
}
