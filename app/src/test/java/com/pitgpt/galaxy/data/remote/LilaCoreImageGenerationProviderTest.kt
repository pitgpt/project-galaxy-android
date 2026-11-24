package com.pitgpt.galaxy.data.remote

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobStore
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LilaCoreImageGenerationProviderTest {
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
    private companion object{const val REQ="00000000-0000-0000-0000-000000000002";const val CONV="00000000-0000-0000-0000-000000000001";const val JOB="00000000-0000-0000-0000-000000000003"}
}
