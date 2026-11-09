package com.pitgpt.galaxy.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
