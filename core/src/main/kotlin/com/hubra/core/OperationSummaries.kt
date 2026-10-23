package com.hubra.core

import kotlinx.serialization.json.*

data class ChangeField(val label:String,val before:String?,val after:String?)
data class OperationSummary(val title:String,val status:String,val fields:List<ChangeField>,val targetCollection:String?,val targetId:String?)

/** Cards read the operation's snapshots rather than the current business object's mutable fields. */
object OperationSummaries {
    private val metadata=setOf("id","version","createdAt","modifiedAt","source","sourceText","deleted","semesterId","courseId","ruleId","parentId","transactionId","relatedId","attachmentIds","activeSemesterId","timetableAppearances","lastExportAt","lastPhotoCleanupAt","lastPhotoCleanupCount","lastPhotoCleanupBytes")
    private val labels=mapOf("title" to "标题","note" to "备注","date" to "日期","time" to "时间","priority" to "优先级","tags" to "标签","repeat" to "重复",
        "reminder" to "提醒","courseDate" to "关联课程日期","offsetMinutes" to "课后间隔","completed" to "完成状态","name" to "名称","foods" to "菜品","portion" to "份量","analysis" to "饮食分析",
        "type" to "类型","accountId" to "账户","toAccountId" to "转入账户","amountCents" to "金额","category" to "分类","initialCents" to "初始余额","initialDate" to "生效日期",
        "actualCents" to "实际余额","predictedCents" to "推算余额","differenceCents" to "差额","cutoff" to "核账截止时间","stale" to "核账状态",
        "code" to "课程代码","teacher" to "教师","color" to "颜色","pending" to "待补全","dayOfWeek" to "星期","startPeriod" to "开始节次","endPeriod" to "结束节次","weeks" to "周次","room" to "教室",
        "cancelled" to "停课","movedDate" to "调整日期","keyword" to "关键词","startDate" to "第一周首日","weekCount" to "学期周数","semesterStart" to "学期首日","semesterWeeks" to "学期周数",
        "periods" to "作息","nightlyReminder" to "晚间核账提醒","nightlyTime" to "核账时间","courseReminderMinutes" to "课前提醒","themeMode" to "外观模式",
        "photoCleanupEnabled" to "定期清理","photoRetentionDays" to "图片保留天数","health" to "健康资料","onboardingComplete" to "首次设置",
        "activeProfileId" to "对话 AI 配置","imageProfileId" to "图片 AI 配置","reportProfileId" to "日报 AI 配置","baseUrl" to "接口地址","textModel" to "文字模型","imageModel" to "图片模型","timeoutSeconds" to "超时秒数","streaming" to "流式输出")
    private fun objectOf(value:String?):JsonObject?=value?.let{runCatching{HubraJson.json.parseToJsonElement(it).jsonObject}.getOrNull()}
    fun describe(state:AppState,record:OperationRecord):OperationSummary {
        if(record.kind=="course.day.copy") {
            val title=when{record.undone->"已撤销课程复制";record.result.status!="success"->"课程复制未完成";record.changes.isEmpty()->"课程已安排";else->"已复制课程"}
            return OperationSummary(title,if(record.undone)"undone" else record.result.status,
                listOf(ChangeField("复制详情",null,record.result.message.substringAfter('\n',record.result.message))),"courseRules",null)
        }
        if(record.kind=="course.calendar.adjust") {
            val fields=record.changes.filter{it.collection=="courseExceptions"}.mapNotNull{change->objectOf(change.after)}
                .filter{(it["deleted"] as? JsonPrimitive)?.booleanOrNull!=true}.groupBy{content(it,"date") to content(it,"movedDate")}.map{(dates,items)->
                    val (source,target)=dates
                    ChangeField(if(target==null)"停课" else "补课",source,if(target==null)"${items.size}门课程已取消" else "$target · ${items.size}门课程")
                }
            val title=when{record.undone->"已撤销课表调整";record.result.status!="success"->"课表调整未完成";record.changes.isEmpty()->"课表已安排";else->"已调整课表"}
            return OperationSummary(title,if(record.undone)"undone" else record.result.status,fields,"courseExceptions",null)
        }
        val targetCollection=record.changes.firstOrNull{it.id==record.result.targetId && it.collection!="settings"}?.collection
            ?: record.changes.firstOrNull{it.collection!="taskCompletions"}?.collection ?: collectionFor(record.kind)
        val primary=record.changes.firstOrNull{it.collection==targetCollection && it.id==record.result.targetId}
            ?:record.changes.firstOrNull{it.collection==targetCollection}
        val before=objectOf(primary?.before);val after=objectOf(primary?.after)
        val obj=after?:before
        val name=when(targetCollection) {
            "tasks"->content(obj,"title")?:"待办"
            "transactions"->content(obj,"note")?.takeIf{it.isNotBlank()}?:"流水"
            "meals"->content(obj,"name")?.takeIf{it.isNotBlank()}?:content(obj,"foods")?.take(24)?.takeIf{it.isNotBlank()}?:"餐食"
            "courses","accounts","semesters","profiles"->content(obj,"name")?:"记录"
            "courseRules"->"上课安排";"courseExceptions"->"单次调课";"reconciliations"->"账户核账";"settings"->"设置";"accountRules"->"默认账户规则";else->"记录"
        }
        val status=if(record.undone)"undone" else record.result.status
        val verb=when {
            record.undone || record.kind=="undo"->"已撤销"
            status=="conflict"->"修改冲突"
            status=="needs_clarification"->"待补充"
            status!="success"->"操作失败"
            record.kind.endsWith("delete") || (after?.get("deleted") as? JsonPrimitive)?.booleanOrNull==true->"已删除"
            record.kind=="task.complete"->"已完成"
            record.kind=="task.restore"->"已恢复"
            record.kind=="semester.switch"->"已切换"
            record.kind.endsWith("create") || record.kind.endsWith("import") || before==null->"已新建"
            else->"已修改"
        }
        val fields=mutableListOf<ChangeField>()
        if(status=="success" || status=="undone") {
            // Each changed object contributes readable business fields; metadata-only changes are omitted.
            record.changes.filter{it.collection==targetCollection}.forEach{change->
                val old=objectOf(change.before);val new=objectOf(change.after)
                if(new==null || (new["deleted"] as? JsonPrimitive)?.booleanOrNull==true) {
                    old?.forEach{(key,value)->if(key !in metadata && key in labels && value!=JsonNull)fields+=ChangeField(labels.getValue(key),readable(state,key,value),null)}
                } else (old.orEmpty().keys+new.keys).distinct().forEach { key ->
                    if(key !in metadata && key in labels && old?.get(key)!=new[key]) {
                        val previous=old?.get(key)?.let{readable(state,key,it)}
                        val next=new[key]?.let{readable(state,key,it)}
                        if(old!=null || !next.isNullOrBlank() && new[key]!=JsonNull)fields+=ChangeField(labels.getValue(key),previous,next)
                    }
                }
            }
            record.changes.filter{it.collection=="taskCompletions"}.forEach{change->
                val old=objectOf(change.before);val new=objectOf(change.after)
                fields+=ChangeField("完成日期",content(old,"date"),content(new,"date"))
                fields+=ChangeField("本次状态",if(old==null)"未完成" else "已完成",if(new==null)"未完成" else "已完成")
            }
            if(targetCollection=="settings" && before?.get("timetableAppearances")!=after?.get("timetableAppearances"))fields+=ChangeField("课表外观",if(before==null)null else "原设置","已更新")
        }
        return OperationSummary("$verb：$name",status,fields.distinct(),targetCollection,primary?.id?:record.result.targetId)
    }
    private fun collectionFor(kind:String)=when(kind.substringBefore('.')) {"task"->"tasks";"transaction"->"transactions";"meal"->"meals";"course"->if(kind=="course.exception")"courseExceptions" else "courses";"courseRule"->"courseRules";"courseException"->"courseExceptions";"account"->"accounts";"reconciliation"->"reconciliations";"settings"->"settings";"semester"->"semesters";"accountRule"->"accountRules";else->null}
    private fun content(obj:JsonObject?,key:String):String? {
        val value=obj?.get(key)?:return null
        if(value is JsonPrimitive)return value.contentOrNull
        if(key=="foods" && value is JsonArray && value.all{it is JsonPrimitive && it.isString})return value.joinToString("、"){(it as JsonPrimitive).content}
        return null
    }
    private fun readable(state:AppState,key:String,value:JsonElement):String? {
        if(value==JsonNull)return "未设置"
        if(value is JsonArray) {
            if(key=="periods")return value.joinToString("；"){p->val obj=p as? JsonObject;if(obj==null)"作息格式异常" else "${content(obj,"number")?: "?"}节 ${content(obj,"start")?:"?"}–${content(obj,"end")?:"?"}"}
            return value.joinToString("、"){(it as? JsonPrimitive)?.contentOrNull?:"内容格式异常"}
        }
        if(value is JsonObject)return if(key=="health")"健康资料已更新" else "已更新"
        val raw=value.jsonPrimitive.content
        return when {
            key.endsWith("Cents")->value.jsonPrimitive.longOrNull?.let{"¥${DomainEngine.formatMoney(it)}"}?:raw
            key in setOf("accountId","toAccountId")->state.accounts.firstOrNull{it.id==raw}?.name?:"账户"
            key in setOf("activeProfileId","imageProfileId","reportProfileId")->state.profiles.firstOrNull{it.id==raw}?.name?:"AI 配置"
            key=="type"->mapOf("expense" to "支出","income" to "收入","refund" to "退款","transfer" to "转账","adjustment" to "余额调整")[raw]?:raw
            key=="repeat"->mapOf("none" to "不重复","daily" to "每天","weekdays" to "工作日","weekly" to "每周")[raw]?:raw
            key=="themeMode"->mapOf("system" to "跟随系统","light" to "浅色","dark" to "深色")[raw]?:raw
            key=="completed"->if(raw=="true")"已完成" else "未完成"
            key=="stale"->if(raw=="true")"受后续修改影响" else "有效"
            key=="dayOfWeek"->listOf("周一","周二","周三","周四","周五","周六","周日").getOrNull(raw.toIntOrNull()?.minus(1)?:-1)?:raw
            key=="priority"->mapOf("0" to "普通","1" to "重要","2" to "紧急")[raw]?:raw
            value.jsonPrimitive.booleanOrNull!=null->if(value.jsonPrimitive.boolean)"已开启" else "未开启"
            else->raw
        }
    }
}
