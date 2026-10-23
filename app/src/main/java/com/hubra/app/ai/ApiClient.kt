package com.hubra.app.ai

import android.content.Context
import android.util.Base64
import com.hubra.app.hubra
import com.hubra.app.media.AttachmentManager
import com.hubra.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ApiReply(val text: String, val toolArguments: String? = null)

class ApiClient(private val context: Context) {
    private val schema = buildJsonObject {
        put("type","object"); putJsonObject("properties") {
            putJsonObject("reply") { put("type","string") }
            putJsonObject("actions") { put("type","array"); putJsonObject("items") {
                put("type","object"); putJsonObject("properties") {
                    putJsonObject("kind") { put("type","string"); put("enum",JsonArray(ApiProtocol.allowedKinds.sorted().map(::JsonPrimitive))) }
                    putJsonObject("targetId") { put("type","string") }; putJsonObject("expectedVersion") { put("type","integer") }
                    putJsonObject("args") { put("type","object");put("additionalProperties",true) }
                };put("required",JsonArray(listOf(JsonPrimitive("kind"),JsonPrimitive("args"))))
            } }
        };put("required",JsonArray(listOf(JsonPrimitive("reply"),JsonPrimitive("actions"))))
    }
    suspend fun request(profile: ApiProfile, system: String, user: String, attachmentId: String?=null, useTools: Boolean=false, jsonMode: Boolean=false, probeImage: Boolean=false): ApiReply = withContext(Dispatchers.IO) {
        val secret=context.hubra.secrets.get(profile.id) ?: error("请先在设置中填写 ${profile.name} 的 API Key")
        val image = if(probeImage) "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6WQAAAABJRU5ErkJggg==" else attachmentId?.let { id ->
            val a=context.hubra.repository.snapshot().attachments.firstOrNull { it.id==id } ?: error("图片不存在")
            Base64.encodeToString(AttachmentManager(context).file(a).readBytes(),Base64.NO_WRAP)
        }
        val payload=buildJsonObject {
            put("model",if(image!=null)profile.imageModel else profile.textModel);put("stream",profile.streaming)
            put("messages",buildJsonArray {
                add(buildJsonObject { put("role","system");put("content",system) })
                add(buildJsonObject { put("role","user"); if(image==null)put("content",user) else put("content",buildJsonArray {
                    add(buildJsonObject { put("type","text");put("text",user) })
                    add(buildJsonObject { put("type","image_url");putJsonObject("image_url") { put("url","data:${if(probeImage)"image/png" else "image/jpeg"};base64,$image") } })
                }) })
            })
            if(useTools) { put("tools",buildJsonArray { add(buildJsonObject { put("type","function");putJsonObject("function") { put("name","hubra_actions");put("description","Return validated Hubra action requests. The app executes them and reports actual outcomes.");put("parameters",schema) } }) });putJsonObject("tool_choice") { put("type","function");putJsonObject("function") { put("name","hubra_actions") } } }
            else if(jsonMode)putJsonObject("response_format") { put("type","json_object") }
        }
        val client=OkHttpClient.Builder().callTimeout(profile.timeoutSeconds.coerceIn(10,180).toLong(),TimeUnit.SECONDS).connectTimeout(20,TimeUnit.SECONDS).build()
        val request=Request.Builder().url(ApiProtocol.endpoint(profile.baseUrl)).header("Authorization","Bearer $secret").post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        CancellableHttp.read(client.newCall(request)) { response ->
            if(!response.isSuccessful)error(when(response.code){401,403->"API 密钥无效或无权限";429->"API 调用频率或额度受限，请稍后重试";else->"API 请求失败（HTTP ${response.code}）"})
            val body=response.body ?: error("API 返回空响应")
            if(profile.streaming && response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                val content=StringBuilder();val arguments=StringBuilder()
                BoundedInputStream(body.byteStream(),4_000_000).reader(Charsets.UTF_8).buffered().use { reader ->
                    while(true) {
                        val line=reader.readLine() ?: break
                        if(!line.startsWith("data:"))continue
                        val data=line.substringAfter("data:").trim();if(data=="[DONE]")break
                        val delta=runCatching { HubraJson.json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject }.getOrNull() ?: continue
                        delta["content"]?.jsonPrimitive?.contentOrNull?.let(content::append)
                        delta["tool_calls"]?.jsonArray?.firstOrNull()?.jsonObject?.get("function")?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull?.let(arguments::append)
                        require(content.length+arguments.length<1_000_000) { "AI 返回内容过长" }
                    }
                }
                ApiReply(content.toString(),arguments.toString().takeIf { it.isNotBlank() })
            } else {
                val raw=BoundedInputStream(body.byteStream(),4_000_000).reader(Charsets.UTF_8).use { it.readText() };require(raw.length<1_000_000) { "AI 返回内容过长" }
                val obj=HubraJson.json.parseToJsonElement(raw).jsonObject
                val message=obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: error("API 返回格式不兼容")
                ApiReply(message["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),message["tool_calls"]?.jsonArray?.firstOrNull()?.jsonObject?.get("function")?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull)
            }
        }
    }
    suspend fun probe(profile: ApiProfile): ApiProfile {
        request(profile.copy(streaming=false),"你是连接测试助手。只回答 OK。","连接测试")
        val jsonOk=runCatching { val r=request(profile.copy(streaming=false),"只输出JSON对象。","输出 {\"ok\":true}",jsonMode=true);ApiProtocol.parseEnvelope(r.text)["ok"]?.jsonPrimitive?.boolean==true }.getOrDefault(false)
        val toolsOk=runCatching { val r=request(profile.copy(streaming=false),"连接测试，不执行任何操作。","调用 hubra_actions，reply填写OK，actions为空数组。",useTools=true);r.toolArguments?.let { ApiProtocol.parseActions(it,"probe").isEmpty() }==true }.getOrDefault(false)
        val imageOk=runCatching { val r=request(profile.copy(streaming=false),"请观察图片，描述主色。","只回答图片主要是什么颜色。",probeImage=true);r.text.contains("白") || r.text.contains("white",true) }.getOrDefault(false)
        return profile.copy(supportsJson=jsonOk,supportsTools=toolsOk,supportsImages=imageOk,testedAt=Instant.now().toString())
    }
}
