package com.pitgpt.galaxy.data.remote

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.GeneratedImagePayload
import com.pitgpt.galaxy.core.model.ImageGenerationCapabilities
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.model.ImageGenerationStatus
import com.pitgpt.galaxy.core.repository.GeneratedImageResultSource
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobStore
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

interface LilaImageApiTransport {
    suspend fun requestJson(method: String, path: String, body: JSONObject? = null, idempotencyKey: String? = null,
        maxResponseBytes: Int = 128 * 1024, maxRequestBytes: Int = 2 * 1024 * 1024): JSONObject
    suspend fun requestBinary(path: String, maxResponseBytes: Int, expectedContentType: String): LilaBinaryResponse
}

data class LilaBinaryResponse(val contentType: String, val bytes: ByteArray) {
    override fun toString(): String = "LilaBinaryResponse(contentType=$contentType, bytes=${bytes.size})"
}
data class LilaComfyUiProviderSummary(val configured: Boolean, val workflowId: String?, val hasBearerToken: Boolean,
    val supportsQueuedCancellation: Boolean, val supportsRunningCancellation: Boolean)
data class LilaComfyUiProviderTestResult(val providerId: String, val connectionReachable: Boolean, val workflowId: String?)

/** Owner-scoped image adapter; ComfyUI credentials and workflow stay in Lila Core. */
class LilaCoreImageGenerationProvider(private val api: LilaImageApiTransport, private val jobs: ComfyUiJobStore) :
    ImageGenerationProvider, GeneratedImageResultSource {

    override suspend fun capabilities() = ImageGenerationCapabilities(
        supportedMimeTypes=setOf(PNG), minWidth=64, maxWidth=2048, minHeight=64, maxHeight=2048,
        supportsReferenceImages=false, supportsCancellation=false, supportsProgress=false,
        supportsQueuedCancellation=true, supportsRunningCancellation=false
    )

    suspend fun providerSummary(): LilaComfyUiProviderSummary {
        val json=api.requestJson("GET", PROVIDER_PATH)
        return LilaComfyUiProviderSummary(json.optBoolean("configured"), optionalString(json,"workflow_id",96),
            json.optBoolean("has_bearer_token"), json.optBoolean("supports_queued_cancellation",true),
            json.optBoolean("supports_running_cancellation"))
    }

    suspend fun configureProvider(baseUrl:String, workflow:ComfyUiWorkflowTemplate, bearerToken:String?=null,
        clearBearerToken:Boolean=false, timeoutSeconds:Double=30.0):LilaComfyUiProviderSummary {
        require(baseUrl.isNotBlank() && baseUrl.length<=2048)
        require(timeoutSeconds in 1.0..30.0)
        workflow.validateMappings()
        val body=JSONObject().put("base_url",baseUrl.trim()).put("timeout_seconds",timeoutSeconds)
            .put("workflow",workflow.toCoreJson()).put("clear_bearer_token",clearBearerToken)
        bearerToken?.takeIf{it.isNotBlank()}?.let{body.put("bearer_token",it)}
        return parseSummary(api.requestJson("PUT",PROVIDER_PATH,body,maxRequestBytes=3*1024*1024,maxResponseBytes=2*1024*1024))
    }

    suspend fun testProvider():LilaComfyUiProviderTestResult {
        val json=api.requestJson("POST","$PROVIDER_PATH/test")
        val providerId=json.optString("provider_id")
        if(providerId!="comfyui") throw GalaxyApiException(null,"Сервис Lila вернул некорректный тип image provider.")
        return LilaComfyUiProviderTestResult(providerId,json.optBoolean("connection_reachable"),optionalString(json,"workflow_id",96))
    }

    override suspend fun submit(request:ImageGenerationRequest):String {
        requireUuid(request.id)
        val conversationId=canonicalUuid(request.conversationId)
        require(request.referenceAssetIds.isEmpty())
        require(request.preferredMimeType==null || request.preferredMimeType==PNG)
        require(request.width in 64..2048 && request.height in 64..2048)
        require(request.seed==null || request.seed>=0)
        require(request.negativePrompt==null || request.negativePrompt.length<=4000)
        if(request.workflowId!=null){
            val summary=providerSummary()
            if(!summary.configured || summary.workflowId!=request.workflowId)
                throw GalaxyApiException(null,"Выбранный ComfyUI workflow не совпадает с настройкой Lila Core.")
        }
        val body=JSONObject().put("conversation_id",conversationId).put("prompt",request.prompt)
            .put("width",request.width).put("height",request.height)
        request.negativePrompt?.takeIf{it.isNotBlank()}?.let{body.put("negative_prompt",it)}
        request.seed?.let{body.put("seed",it)}
        val json=try{
            api.requestJson("POST",IMAGES_PATH,body,idempotencyKey=request.id,maxResponseBytes=256*1024)
        }catch(e:GalaxyApiException){
            if(e.httpStatus==409) throw GalaxyApiException(409,"Этот ID запроса уже использован с другими параметрами. Создай новое задание.")
            throw e
        }
        val jobId=canonicalUuid(json.optString("job_id"))
        if(canonicalUuid(json.optString("conversation_id"))!=conversationId)
            throw GalaxyApiException(null,"Lila Core вернул задание для другого диалога.")
        requireKnownState(json.optString("state"))
        val byRequest=jobs.all().firstOrNull{it.requestId==request.id}
        if(byRequest!=null && byRequest.promptId!=jobId) throw GalaxyApiException(null,"Локальная история содержит конфликтующий ID image job.")
        val byJob=jobs.get(jobId)
        if(byJob!=null && byJob.requestId!=request.id) throw GalaxyApiException(null,"ID image job уже связан с другим локальным запросом.")
        if(byJob==null){
            try{jobs.put(ComfyUiJobRecord(jobId,request.id,request.createdAtEpochMillis,submissionStarted=true))}
            catch(e:CancellationException){throw e}
            catch(_:Exception){
                if(jobs.get(jobId)?.requestId!=request.id)
                    throw GalaxyApiException(null,"Задание создано в Core, но локальная связь не сохранена. Повтори тот же запрос для восстановления.")
            }
        }
        return jobId
    }

    override suspend fun status(providerJobId:String):ImageGenerationStatus {
        val json=fetchJob(canonicalUuid(providerJobId))
        return when(json.optString("state")){
            "queued"->ImageGenerationStatus(ImageGenerationState.QUEUED,userMessage="Изображение ожидает обработки в ComfyUI.")
            "running"->ImageGenerationStatus(ImageGenerationState.RUNNING,userMessage="ComfyUI генерирует изображение.")
            "prepared"->ImageGenerationStatus(ImageGenerationState.RUNNING,userMessage="Lila Core подготавливает задание.")
            "submitting"->ImageGenerationStatus(ImageGenerationState.RUNNING,userMessage="Lila Core отправляет задание в ComfyUI.")
            "submission_unknown"->ImageGenerationStatus(ImageGenerationState.RUNNING,userMessage="ComfyUI не подтвердил приём задания. Повторная отправка заблокирована для защиты от дубликата.")
            "provider_completed","result_pending"->ImageGenerationStatus(ImageGenerationState.RUNNING,userMessage="ComfyUI завершил генерацию; Lila Core сохраняет результат.")
            "completed"->ImageGenerationStatus(ImageGenerationState.COMPLETED,progressPercent=100,userMessage="Изображение готово.")
            "failed"->ImageGenerationStatus(ImageGenerationState.FAILED,userMessage="ComfyUI не смог выполнить генерацию. Проверь настройки workflow.")
            "cancelled"->ImageGenerationStatus(ImageGenerationState.CANCELLED,userMessage="Генерация отменена.")
            else->throw GalaxyApiException(null,"Lila Core вернул неизвестный статус image job.")
        }
    }

    override suspend fun cancel(providerJobId:String){
        val id=canonicalUuid(providerJobId)
        if(status(id).state!=ImageGenerationState.QUEUED) throw GalaxyApiException(409,"Отмена доступна только пока задание находится в очереди.")
        val json=try{api.requestJson("POST","$IMAGES_PATH/$id/cancel",maxResponseBytes=256*1024)}
        catch(e:GalaxyApiException){if(e.httpStatus==409) throw GalaxyApiException(409,"Задание уже выполняется или завершено; отмена недоступна.");throw e}
        if(json.optString("job_id")!=id || json.optString("state")!="cancelled") throw GalaxyApiException(null,"Lila Core не подтвердил отмену задания.")
        if(jobs.get(id)!=null) withContext(NonCancellable){jobs.markCancelled(id)}
    }

    override suspend fun result(providerJobId:String):GeneratedImageAsset {
        val payload=completedImage(providerJobId)
        return try{payload.asset}finally{payload.bytes.fill(0)}
    }

    override suspend fun completedImage(providerJobId:String):GeneratedImagePayload {
        val id=canonicalUuid(providerJobId)
        val job=fetchJob(id)
        if(job.optString("state")!="completed") throw GalaxyApiException(null,"Изображение ещё не готово в Lila Core.")
        val record=jobs.get(id)?:throw GalaxyApiException(null,"Локальная связь image job не найдена.")
        val meta=job.optJSONObject("asset")?:throw GalaxyApiException(null,"Lila Core не вернул сохранённый image asset.")
        val asset=parseAsset(id,record,meta)
        val binary=api.requestBinary("$IMAGES_PATH/$id/asset",25*1024*1024,PNG)
        val bytes=binary.bytes
        try{
            if(binary.contentType!=PNG || bytes.size.toLong()!=asset.byteSize) throw GalaxyApiException(null,"Размер или формат изображения Lila не совпадает с метаданными.")
            val digest=sha256(bytes)
            if(!digest.equals(asset.sha256,true)) throw GalaxyApiException(null,"Контрольная сумма изображения Lila не совпадает.")
            val dimensions=pngDimensions(bytes)
            if(dimensions.first!=asset.width || dimensions.second!=asset.height) throw GalaxyApiException(null,"Размеры PNG не совпадают с метаданными Lila.")
            return GeneratedImagePayload(asset,PNG,bytes,digest)
        }catch(e:Exception){bytes.fill(0);throw e}
    }

    private suspend fun fetchJob(id:String):JSONObject{
        val json=api.requestJson("GET","$IMAGES_PATH/$id",maxResponseBytes=256*1024)
        if(canonicalUuid(json.optString("job_id"))!=id) throw GalaxyApiException(null,"Lila Core вернул данные другого image job.")
        requireKnownState(json.optString("state"))
        return json
    }

    private fun parseAsset(id:String,record:ComfyUiJobRecord,json:JSONObject):GeneratedImageAsset{
        val mime=json.optString("mime_type");val width=json.optInt("width",0);val height=json.optInt("height",0)
        val size=json.optLong("byte_size",-1L);val digest=json.optString("sha256").lowercase()
        if(mime!=PNG || width !in 1..2048 || height !in 1..2048 || size !in 1..25L*1024*1024 || !digest.matches(Regex("[0-9a-f]{64}")))
            throw GalaxyApiException(null,"Lila Core вернул некорректные метаданные изображения.")
        return GeneratedImageAsset("lila-core:$id",record.requestId,mime,width,height,size,digest,record.createdAtEpochMillis)
    }

    private fun parseSummary(json:JSONObject)=LilaComfyUiProviderSummary(json.optBoolean("configured"),
        optionalString(json,"workflow_id",96),json.optBoolean("has_bearer_token"),
        json.optBoolean("supports_queued_cancellation",true),json.optBoolean("supports_running_cancellation"))

    private fun ComfyUiWorkflowTemplate.toCoreJson():JSONObject{
        fun node(value:ComfyUiNodeInput?)=value?.let{JSONObject().put("node_id",it.nodeId).put("input_name",it.inputName)}
        val json=JSONObject().put("workflow_id",id).put("workflow_json",workflowJson).put("positive_prompt",node(positivePrompt))
        negativePrompt?.let{json.put("negative_prompt",node(it))};width?.let{json.put("width",node(it))}
        height?.let{json.put("height",node(it))};seed?.let{json.put("seed",node(it))}
        return json
    }

    private fun optionalString(json:JSONObject,key:String,max:Int):String?{
        if(json.isNull(key))return null
        val value=json.optString(key).takeIf{it.isNotBlank()&&it!="null"}?:return null
        if(value.length>max)throw GalaxyApiException(null,"Lila Core вернул некорректные данные image provider.")
        return value
    }
    private fun requireUuid(value:String){try{UUID.fromString(value)}catch(_:IllegalArgumentException){throw IllegalArgumentException("Request ID must be a UUID")}}
    private fun canonicalUuid(value:String):String=try{UUID.fromString(value).toString()}catch(_:IllegalArgumentException){throw GalaxyApiException(null,"Lila Core вернул некорректный ID.")}
    private fun requireKnownState(value:String){if(value !in STATES)throw GalaxyApiException(null,"Lila Core вернул неизвестный статус image job.")}
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 0xff)}
    private fun pngDimensions(bytes:ByteArray):Pair<Int,Int>{
        val sig=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
        if(bytes.size<24||!bytes.copyOfRange(0,8).contentEquals(sig)||String(bytes,12,4,Charsets.US_ASCII)!="IHDR")throw GalaxyApiException(null,"Сервис Lila вернул некорректный PNG.")
        fun read(o:Int)=((bytes[o].toInt() and 255) shl 24) or ((bytes[o+1].toInt() and 255) shl 16) or ((bytes[o+2].toInt() and 255) shl 8) or (bytes[o+3].toInt() and 255)
        val w=read(16);val h=read(20);if(w !in 1..2048||h !in 1..2048)throw GalaxyApiException(null,"PNG содержит недопустимые размеры.")
        return w to h
    }
    override fun toString()="LilaCoreImageGenerationProvider(provider=comfyui, credentials=<redacted>)"
    private companion object{
        const val PROVIDER_PATH="/v1/lila/image-providers/comfyui";const val IMAGES_PATH="/v1/lila/images";const val PNG="image/png"
        const val MAX_JSON_REQUEST_BYTES=3*1024*1024;const val MAX_JSON_RESPONSE_BYTES=2*1024*1024
        val STATES=setOf("prepared","submitting","submission_unknown","queued","running","provider_completed","result_pending","completed","failed","cancelled")
    }
}
