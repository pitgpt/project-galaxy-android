package com.pitgpt.galaxy.data.remote

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobStore
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LilaCoreImageGenerationProviderTest {
    @Test fun providerConfigurationLoadsEditableWorkflowWithoutReturningCredentials() = runBlocking {
        val workflow = JSONObject()
            .put("workflow_id", "basic-v1")
            .put("workflow_json", """{"3":{"inputs":{"text":""}}}""")
            .put("positive_prompt", JSONObject().put("node_id", "3").put("input_name", "text"))
        val api = FakeApi().apply {
            json.add(
                JSONObject()
                    .put("configured", true)
                    .put("base_url", "http://host.docker.internal:8188")
                    .put("timeout_seconds", 30.0)
                    .put("workflow", workflow)
                    .put("has_bearer_token", true)
            )
        }

        val configuration = LilaCoreImageGenerationProvider(api, FakeStore()).providerConfiguration()

        assertTrue(configuration.configured)
        assertEquals("http://host.docker.internal:8188", configuration.baseUrl)
        assertEquals("basic-v1", configuration.workflow?.id)
        assertTrue(configuration.hasBearerToken)
        assertEquals("/v1/lila/image-providers/comfyui/config", api.calls.single().path)
    }

    @Test fun testProviderParsesSafeCheckpointNamesAndDiscoveryState() = runBlocking {
        val api = FakeApi().apply {
            json.add(
                JSONObject()
                    .put("provider_id", "comfyui")
                    .put("connection_reachable", true)
                    .put("workflow_id", "lila.sdxl.v1")
                    .put("checkpoint_discovery_supported", true)
                    .put("available_checkpoints", JSONArray()
                        .put("sdxl.safetensors")
                        .put("models/refiner.safetensors")
                        .put("../outside.safetensors")
                        .put("bad\\path.safetensors"))
                    .put("checkpoint_list_truncated", true)
            )
        }

        val result = LilaCoreImageGenerationProvider(api, FakeStore()).testProvider()

        assertTrue(result.connectionReachable)
        assertTrue(result.checkpointDiscoverySupported)
        assertEquals(listOf("sdxl.safetensors", "models/refiner.safetensors"), result.availableCheckpoints)
        assertTrue(result.checkpointListTruncated)
        assertEquals("/v1/lila/image-providers/comfyui/test", api.calls.single().path)
    }
    @Test fun historyIsBoundToTheRequestedConversationAndParsesAssetSize() = runBlocking {
        val job = JSONObject()
            .put("job_id", JOB)
            .put("conversation_id", CONV)
            .put("prompt", "portrait")
            .put("negative_prompt", "blur")
            .put("width", 512)
            .put("height", 768)
            .put("seed", 42)
            .put("state", "completed")
            .put("created_at_epoch_millis", 1234L)
            .put("asset", JSONObject().put("byte_size", 4096))
        val api = FakeApi().apply {
            json.add(JSONObject().put("conversation_id", CONV).put("jobs", JSONArray().put(job)))
        }

        val history = LilaCoreImageGenerationProvider(api, FakeStore()).history(CONV)

        assertEquals(1, history.size)
        assertEquals(JOB, history.single().jobId)
        assertEquals("portrait", history.single().prompt)
        assertEquals(4096L, history.single().assetByteSize)
        assertTrue(api.calls.single().path.contains("conversation_id=$CONV"))
    }

    @Test fun historyPagesCarryTheOpaqueCursorWithoutChangingConversationScope() = runBlocking {
        val newestJob = historyJob(JOB, "newest portrait", 2_000L)
        val olderJob = historyJob(PREVIOUS_JOB, "older portrait", 1_000L)
        val api = FakeApi().apply {
            json.add(
                JSONObject()
                    .put("conversation_id", CONV)
                    .put("jobs", JSONArray().put(newestJob))
                    .put("next_cursor", "opaque-next-page")
            )
            json.add(
                JSONObject()
                    .put("conversation_id", CONV)
                    .put("jobs", JSONArray().put(olderJob))
                    .put("next_cursor", JSONObject.NULL)
            )
        }
        val provider = LilaCoreImageGenerationProvider(api, FakeStore())

        val first = provider.historyPage(CONV)
        assertEquals(1, first.jobs.size)
        assertEquals(JOB, first.jobs.single().jobId)
        assertEquals("opaque-next-page", first.nextCursor)

        val second = provider.historyPage(CONV, first.nextCursor)
        assertEquals(1, second.jobs.size)
        assertEquals(PREVIOUS_JOB, second.jobs.single().jobId)
        assertNull(second.nextCursor)
        assertTrue(api.calls[0].path.contains("limit=50"))
        assertTrue(api.calls[1].path.contains("cursor=opaque-next-page"))
        assertTrue(api.calls[1].path.contains("conversation_id=$CONV"))
    }

    @Test fun historyRejectsAnEmptyPageWithAContinuationCursor() = runBlocking {
        val api = FakeApi().apply {
            json.add(
                JSONObject()
                    .put("conversation_id", CONV)
                    .put("jobs", JSONArray())
                    .put("next_cursor", "opaque-next-page")
            )
        }

        var rejected = false
        try {
            LilaCoreImageGenerationProvider(api, FakeStore()).historyPage(CONV)
        } catch (_: GalaxyApiException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(1, api.calls.size)
    }

    @Test fun historyRejectsAResponseForAnotherConversation() = runBlocking {
        val otherConversation = "00000000-0000-0000-0000-000000000009"
        val api = FakeApi().apply {
            json.add(JSONObject().put("conversation_id", otherConversation).put("jobs", JSONArray()))
        }

        var rejected = false
        try {
            LilaCoreImageGenerationProvider(api, FakeStore()).history(CONV)
        } catch (_: GalaxyApiException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun submitUsesIdempotencyAndPersistsCoreJob() = runBlocking {
        val api=FakeApi().apply{json.add(job("queued"))};val store=FakeStore()
        val request=ImageGenerationRequest(REQ,CONV,"lila","portrait",width=512,height=512,seed=42L,createdAtEpochMillis=1234L)
        assertEquals(JOB,LilaCoreImageGenerationProvider(api,store).submit(request))
        assertEquals(REQ,api.calls.single().key)
        assertEquals(CONV,api.calls.single().body?.getString("conversation_id"))
        assertEquals(REQ,store.get(JOB)?.requestId)
    }
    @Test fun submissionUnknownRemainsRunning()=runBlocking{
        val api=FakeApi().apply{json.add(job("submission_unknown"))}
        val status=LilaCoreImageGenerationProvider(api,FakeStore()).status(JOB)
        assertEquals(ImageGenerationState.RUNNING,status.state)
        assertTrue(status.userMessage.orEmpty().contains("не подтвердил"))
    }
    @Test fun completedImageValidatesChecksumAndDimensions()=runBlocking{
        val bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/nY8AAAAASUVORK5CYII=")
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val meta=JSONObject().put("mime_type","image/png").put("width",1).put("height",1).put("byte_size",bytes.size).put("sha256",hash)
        val api=FakeApi().apply{json.add(job("completed",meta));binary.add(LilaBinaryResponse("image/png",bytes.copyOf()))}
        val store=FakeStore().apply{put(ComfyUiJobRecord(JOB,REQ,1234L,submissionStarted=true))}
        val result=LilaCoreImageGenerationProvider(api,store).completedImage(JOB)
        assertEquals(REQ,result.asset.requestId);assertArrayEquals(bytes,result.bytes);result.bytes.fill(0)
    }
    @Test fun cancellationOnlyAppliesToQueuedJobs()=runBlocking{
        val api=FakeApi().apply{json.add(job("queued"));json.add(job("cancelled"))}
        val store=FakeStore().apply{put(ComfyUiJobRecord(JOB,REQ,1234L,submissionStarted=true))}
        LilaCoreImageGenerationProvider(api,store).cancel(JOB)
        assertEquals("GET",api.calls[0].method);assertEquals("POST",api.calls[1].method);assertTrue(store.get(JOB)?.cancelled==true)
    }
    private fun historyJob(jobId: String, prompt: String, createdAt: Long) = JSONObject()
        .put("job_id", jobId)
        .put("conversation_id", CONV)
        .put("prompt", prompt)
        .put("width", 512)
        .put("height", 512)
        .put("state", "queued")
        .put("created_at_epoch_millis", createdAt)

    private fun job(state:String,asset:JSONObject?=null)=JSONObject().put("job_id",JOB).put("conversation_id",CONV).put("state",state).apply{if(asset!=null)put("asset",asset)}
    private class FakeApi:LilaImageApiTransport{
        val json=ArrayDeque<JSONObject>();val binary=ArrayDeque<LilaBinaryResponse>();val calls=mutableListOf<Call>()
        override suspend fun requestJson(method:String,path:String,body:JSONObject?,idempotencyKey:String?,maxResponseBytes:Int,maxRequestBytes:Int):JSONObject{
            calls+=Call(method,path,body?.let{JSONObject(it.toString())},idempotencyKey);return json.removeFirst()
        }
        override suspend fun requestBinary(path:String,maxResponseBytes:Int,expectedContentType:String):LilaBinaryResponse{calls+=Call("GET_BINARY",path,null,null);return binary.removeFirst()}
    }
    private data class Call(val method:String,val path:String,val body:JSONObject?,val key:String?)
    private class FakeStore:ComfyUiJobStore{
        private val map=linkedMapOf<String,ComfyUiJobRecord>()
        override suspend fun put(record:ComfyUiJobRecord){check(map.putIfAbsent(record.promptId,record)==null)}
        override suspend fun get(promptId:String)=map[promptId]
        override suspend fun markCancelled(promptId:String){map[promptId]=checkNotNull(map[promptId]).copy(cancelled=true)}
        override suspend fun all()=map.values.toList()
        override suspend fun markSubmissionStarted(promptId:String):Boolean{val r=map[promptId]?:return false;if(r.submissionStarted||r.cancelled)return false;map[promptId]=r.copy(submissionStarted=true);return true}
    }
    private companion object{const val REQ="00000000-0000-0000-0000-000000000002";const val CONV="00000000-0000-0000-0000-000000000001";const val JOB="00000000-0000-0000-0000-000000000003";const val PREVIOUS_JOB="00000000-0000-0000-0000-000000000004"}
}
