package com.hubra.app.ai

import com.hubra.core.*
import kotlinx.serialization.json.*
import java.net.URI

object ApiProtocol {
    fun argumentsInMode(mode:String,kind:String,args:JsonObject):JsonObject =
        if(mode=="course" && kind=="course.import")JsonObject(args.filterKeys { it in setOf("courses","rules") })else args
    fun permitsInMode(mode:String,kind:String):Boolean = when(mode) {
        "course" -> kind in setOf("course.import","course.create","courseRule.create","query")
        "meal" -> kind in setOf("meal.create","query")
        else -> kind in allowedKinds
    }
    val allowedKinds = setOf("semester.create","semester.switch","account.initialize","transaction.create","transaction.update","transaction.delete","reconciliation.create","account.calibrate",
        "accountRule.create","accountRule.update","accountRule.delete","meal.create","meal.update","meal.delete","course.create","course.update","course.delete",
        "courseRule.create","courseRule.update","courseRule.delete","course.exception","courseException.update","courseException.delete","course.import","course.calendar.adjust","course.day.copy",
        "task.create","task.update","task.delete","task.defer","task.complete","task.restore","semester.update","settings.update","undo","query")
    fun capabilitiesFor(previous: ApiProfile?, baseUrl: String, textModel: String, imageModel: String, keyChanged: Boolean): ApiProfile? =
        previous?.takeUnless { keyChanged || it.baseUrl != baseUrl || it.textModel != textModel || it.imageModel != imageModel }
    fun endpoint(base: String): String {
        val uri=URI(base.trim()); require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) { "API 地址必须是有效 HTTPS 地址" }
        val url=base.trim().trimEnd('/')
        return if(url.endsWith("/chat/completions"))url else "$url/chat/completions"
    }
    fun parseEnvelope(text: String): JsonObject {
        val cleaned=text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root=HubraJson.json.parseToJsonElement(cleaned) as? JsonObject ?: error("AI 响应需要是 JSON 对象")
        root["reply"]?.takeUnless { it==JsonNull }?.let { require(it is JsonPrimitive && it.isString) { "AI 回复说明应为文字" } }
        root["actions"]?.let { require(it is JsonArray) { "AI 操作列表应为数组" } }
        return root
    }
    fun parseActions(text: String, requestId: String): List<Operation> {
        val root=parseEnvelope(text)
        val array=root["actions"]?.jsonArray ?: JsonArray(emptyList())
        require(array.size<=50) { "单次操作过多，请分批处理" }
        return array.mapIndexed { index, element ->
            val obj=element as? JsonObject ?: error("每项操作需要是 JSON 对象")
            val kind=(obj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("操作类型需要是文字")
            require(kind in allowedKinds) { "不允许执行该操作：$kind" }
            val supplied=obj["expectedVersion"]
            val version=if(supplied==null || supplied==JsonNull)null else {
                val primitive=supplied.jsonPrimitive
                require(!primitive.isString && primitive.longOrNull!=null && primitive.long>0) { "记录版本必须是正整数" }
                primitive.long
            }
            Operation("$requestId:$index",kind,obj["targetId"]?.jsonPrimitive?.contentOrNull,version,obj["args"]?.jsonObject ?: JsonObject(emptyMap()))
        }
    }
}
