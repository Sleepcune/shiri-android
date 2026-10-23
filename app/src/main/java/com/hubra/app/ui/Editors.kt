package com.hubra.app.ui

import com.hubra.core.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

typealias Execute = suspend (Operation) -> String
private fun taskRuleOptions(s:AppState,t:Task?):List<Pair<String,String>> {
    val rules=(activeRules(s)+s.courseRules.filter{!it.deleted && it.id==t?.courseRuleId}).distinctBy{it.id}
    return listOf("" to "不关联")+rules.map{r->
        val course=s.courses.find{it.id==r.courseId}
        val old=if(course?.semesterId!=s.settings.activeSemesterId)"（历史关联，保留）"else ""
        r.id to "${course?.name?:"原课程"}$old 周${r.dayOfWeek} ${r.startPeriod}-${r.endPeriod}节"
    }
}
fun newSemesterEditor(s:AppState,execute:Execute)=Editor("新建学期",listOf(
    Input("name","学期名称（如 2027 春季）",required=true),
    Input("startDate","第一周周一 YYYY-MM-DD",required=true),
    Input("weekCount","总周数",s.settings.semesterWeeks.toString(),required=true)
),help="新建后切换到空课表，旧学期课程保留。上课作息由各学期共享，无需重新填写。",save={v->
    execute(operation("semester.create",values=args("name" to v.text("name"),"startDate" to v.text("startDate"),"weekCount" to v.text("weekCount").toInt(),"select" to true)))
})
fun semesterRenameEditor(s: AppState, execute: Execute): Editor {
    val semester = s.semesters.firstOrNull { it.id == s.settings.activeSemesterId }
    return Editor("重命名学期", listOf(Input("name", "学期名称", semester?.name.orEmpty(), required = true)), help = "只修改学期名称，不会影响课程、周数或账本。", save = { v ->
        execute(operation("semester.rename", semester?.id ?: s.settings.activeSemesterId, semester?.version, args("name" to v.text("name"))))
    })
}
fun targetEditor(s:AppState,id:String?,execute:Execute):Editor? {
    if(id==null)return null
    s.transactions.find{it.id==id && !it.deleted}?.let{return transactionEditor(s,execute,it)}
    s.meals.find{it.id==id && !it.deleted}?.let{return mealEditor(s,execute,it)}
    s.tasks.find{it.id==id && !it.deleted}?.let{return taskEditor(s,execute,it)}
    activeCourses(s).find{it.id==id}?.let{return courseEditor(execute,it)}
    activeRules(s).find{it.id==id}?.let{return ruleEditor(s,execute,it)}
    activeExceptions(s).find{it.id==id}?.let{return exceptionEditor(s,execute,e=it)}
    return null
}
fun transactionEditor(s:AppState,execute:Execute,t:Transaction?=null,type:String="expense"):Editor = Editor(if(t==null)"记录账务" else "修改账务",listOf(
    Input("type","类型",t?.type?:type,options=typeOptions),Input("accountId","付款 / 入账账户",t?.accountId?:"campus",options=accountOptions(s)),
    Input("amount","金额（元）",t?.let{DomainEngine.formatMoney(it.amountCents)}?:"",required=true),Input("date","日期 YYYY-MM-DD",t?.date?:today().toString(),required=true),
    Input("note","说明",t?.note?:""),Input("category","分类",t?.category?:"餐饮"),Input("toAccountId","转入账户（仅转账）",t?.toAccountId?:"bank",options=accountOptions(s)),
    Input("relatedId","关联原流水（退款，可选）",t?.relatedId?:"",options=listOf("" to "无")+s.transactions.filter{!it.deleted && it.type=="expense"}.takeLast(40).map{it.id to "${it.date} ${it.note} ${money(it.amountCents)}"})
),help="转账和充值不会计入消费；金额精确到分。",save={v->execute(operation(if(t==null)"transaction.create" else "transaction.update",t?.id,t?.version,args("type" to v.text("type"),"accountId" to v.text("accountId"),"amountCents" to cents(v.text("amount")),"date" to v.text("date"),"note" to v.text("note"),"category" to v.text("category"),"toAccountId" to v.optional("toAccountId"),"relatedId" to v.optional("relatedId"))))},remove=t?.let{{execute(operation("transaction.delete",it.id,it.version))}})
fun accountEditor(a:Account,execute:Execute,mode:String):Editor = Editor(when(mode){"initialize"->"${a.name} · 初始余额";"calibrate"->"${a.name} · 校准余额";else->"${a.name} · 核账"},
    listOf(Input("amount","${if(mode=="initialize")"初始" else "实际"}余额（元）",required=true))+
        (if(mode=="initialize")listOf(Input("date","生效日期 YYYY-MM-DD",today().toString(),required=true))else emptyList())+
        (if(mode=="calibrate")listOf(Input("note","调整说明","手动余额校准"))else emptyList()),
    help=if(mode=="reconcile")"按当前提交时刻保存实际余额观察值与差额，不调整账面余额。" else if(mode=="calibrate")"根据当前推算余额，登记今天的一笔余额调整，保留历史流水。" else "生效日期之前的流水不计入当前余额。",
    save={v->execute(operation(when(mode){"initialize"->"account.initialize";"calibrate"->"account.calibrate";else->"reconciliation.create"},a.id,if(mode=="reconcile")null else a.version,args("accountId" to a.id,(if(mode=="initialize")"amountCents" else "actualCents") to cents(v.text("amount")),"date" to (v.optional("date")?:today().toString()),"note" to v.text("note"))))})
fun mealEditor(s:AppState,execute:Execute,m:Meal?=null,attachmentId:String?=null):Editor = Editor(if(m==null)"记录餐食" else "修改餐食",listOf(Input("date","日期 YYYY-MM-DD",m?.date?:today().toString(),required=true),Input("name","餐次",m?.name?:"午餐",options=listOf("早餐" to "早餐","午餐" to "午餐","晚餐" to "晚餐","加餐" to "加餐")),Input("foods","吃了什么",m?.foods?:"",type="multiline"),Input("portion","份量说明",m?.portion?:""),Input("transactionId","关联流水",m?.transactionId?:"",options=listOf("" to "不关联")+s.transactions.filter{!it.deleted}.takeLast(40).map{it.id to "${it.date} ${it.note} ${money(it.amountCents)}"})),help=if(attachmentId!=null)"已附照片。食物不确定时请补充文字；照片不会自动推断金额。" else "没记录不等于没吃；不确定份量可留空。",save={v->execute(operation(if(m==null)"meal.create" else "meal.update",m?.id,m?.version,args("date" to v.text("date"),"name" to v.text("name"),"foods" to v.text("foods"),"portion" to v.text("portion"),"transactionId" to v.optional("transactionId"),"attachmentIds" to JsonArray(((m?.attachmentIds?:emptyList())+listOfNotNull(attachmentId)).distinct().map(::JsonPrimitive)))))},remove=m?.let{{execute(operation("meal.delete",it.id,it.version))}})
fun taskEditor(s:AppState,execute:Execute,t:Task?=null,parent:String?=null):Editor = Editor(if(t==null)"新增待办" else "修改待办",listOf(
    Input("title","待办内容",t?.title?:"",required=true),Input("note","备注",t?.note?:"",type="multiline"),Input("date","截止 / 开始日期 YYYY-MM-DD",t?.date?:today().toString()),Input("time","时间 HH:mm（全天可空）",t?.time?:""),
    Input("priority","优先级",(t?.priority?:0).toString(),options=listOf("0" to "普通","1" to "重要","2" to "紧急")),Input("tags","标签（逗号分隔）",t?.tags?.joinToString(",")?:""),Input("repeat","重复",t?.repeat?:"none",options=repeatOptions),Input("reminder","提醒",(t?.reminder?:false).toString(),type="bool"),
    Input("parentId","父任务",t?.parentId?:parent?:"",options=listOf("" to "无")+s.tasks.filter{!it.deleted && it.parentId==null && it.id!=t?.id}.map{it.id to it.title}),
    Input("courseRuleId","关联上课规则",t?.courseRuleId?:"",options=taskRuleOptions(s,t)),Input("courseDate","关联课程原日期 YYYY-MM-DD",t?.courseDate?:""),Input("offsetMinutes","下课后多少分钟",(t?.offsetMinutes?:0).toString())
),help="重复待办按每次分别完成。课程关联提醒会跟随单次调课移动。",save={v->execute(operation(if(t==null)"task.create" else "task.update",t?.id,t?.version,args("title" to v.text("title"),"note" to v.text("note"),"date" to v.optional("date"),"time" to v.optional("time"),"priority" to v.text("priority").toInt(),"tags" to JsonArray(v.text("tags").replace('，',',').split(',').filter{it.isNotBlank()}.map{JsonPrimitive(it.trim())}),"repeat" to v.text("repeat"),"reminder" to v.text("reminder").toBoolean(),"parentId" to v.optional("parentId"),"courseRuleId" to v.optional("courseRuleId"),"courseDate" to v.optional("courseDate"),"offsetMinutes" to v.text("offsetMinutes").toInt())))},remove=t?.let{{execute(operation("task.delete",it.id,it.version))}})
fun taskDeferEditor(t: Task, execute: Execute): Editor = Editor("延期待办", listOf(
    Input("targetDate", "延期至 YYYY-MM-DD", t.date ?: today().toString(), required = true),
    Input("time", "新的时间 HH:mm（留空保留原时间）", t.time ?: "")
), help = "延期会保留原待办的完成状态与提醒设置。", save = { v ->
    java.time.LocalDate.parse(v.text("targetDate"))
    v.optional("time")?.let(java.time.LocalTime::parse)
    execute(operation("task.defer", t.id, t.version, args("date" to (t.date ?: today().toString()), "targetDate" to v.text("targetDate"), "time" to v.optional("time"))))
})
fun courseEditor(execute:Execute,c:Course?=null,semesterId:String?=null):Editor = Editor(if(c==null)"新增课程" else "课程信息",listOf(Input("name","名称",c?.name?:"",required=true),Input("code","课程代码",c?.code?:""),Input("teacher","教师",c?.teacher?:""),Input("color","颜色 #RRGGBB",c?.color?:"#71A7A1",required=true),Input("pending","周次仍待补全",(c?.pending?:false).toString(),type="bool"),Input("sourceText","来源 / 备注",c?.sourceText?:"",type="multiline")),save={v->android.graphics.Color.parseColor(v.text("color"));execute(operation(if(c==null)"course.create" else "course.update",c?.id,c?.version,args("name" to v.text("name"),"code" to v.text("code"),"teacher" to v.text("teacher"),"color" to v.text("color"),"pending" to v.text("pending").toBoolean(),"sourceText" to v.text("sourceText"),"semesterId" to (c?.semesterId?:semesterId))))},remove=c?.let{{execute(operation("course.delete",it.id,it.version,args("semesterId" to it.semesterId)))}})
fun ruleEditor(s:AppState,execute:Execute,r:CourseRule?=null,courseId:String?=null):Editor=Editor(if(r==null)"新增上课规则" else "修改上课规则",listOf(Input("courseId","课程",r?.courseId?:courseId?:activeCourses(s).firstOrNull()?.id.orEmpty(),options=activeCourses(s).map{it.id to it.name}),Input("dayOfWeek","星期",(r?.dayOfWeek?:1).toString(),options=(1..7).map{it.toString() to listOf("一","二","三","四","五","六","日")[it-1]}),Input("startPeriod","开始节次",(r?.startPeriod?:1).toString(),required=true),Input("endPeriod","结束节次",(r?.endPeriod?:2).toString(),required=true),Input("weeks","周次（如 3,5-10）",r?.weeks?.joinToString(",")?:"1-${s.settings.semesterWeeks}",required=true),Input("room","教室",r?.room?:""),Input("sourceText","来源文字",r?.sourceText?:"",type="multiline")),save={v->execute(operation(if(r==null)"courseRule.create" else "courseRule.update",r?.id,r?.version,args("courseId" to v.text("courseId"),"dayOfWeek" to v.text("dayOfWeek").toInt(),"startPeriod" to v.text("startPeriod").toInt(),"endPeriod" to v.text("endPeriod").toInt(),"weeks" to JsonArray(parseWeeks(v.text("weeks")).map(::JsonPrimitive)),"room" to v.text("room"),"sourceText" to v.text("sourceText"),"semesterId" to s.settings.activeSemesterId)))},remove=r?.let{{execute(operation("courseRule.delete",it.id,it.version,args("semesterId" to s.settings.activeSemesterId)))}})
fun exceptionEditor(s:AppState,execute:Execute,o:CourseOccurrence?=null,e:CourseException?=null):Editor=Editor("单次课程调整",listOf(Input("ruleId","原上课规则",e?.ruleId?:o?.ruleId?:activeRules(s).firstOrNull()?.id.orEmpty(),options=activeRules(s).map{it.id to "${s.courses.find{c->c.id==it.courseId}?.name} 周${it.dayOfWeek}"}),Input("date","原课程日期",e?.date?:o?.id?.substringAfter(':')?:today().toString(),required=true),Input("cancelled","停课",(e?.cancelled?:false).toString(),type="bool"),Input("movedDate","调至日期（留空恢复原日期）",e?.movedDate?:""),Input("startPeriod","开始节次（可空）",e?.startPeriod?.toString()?:""),Input("endPeriod","结束节次（可空）",e?.endPeriod?.toString()?:""),Input("room","新教室（可空）",e?.room?:"")),help="仅改变这一次课程，保留原始规则。",save={v->execute(operation(if(e==null)"course.exception" else "courseException.update",e?.id,e?.version,args("ruleId" to v.text("ruleId"),"date" to v.text("date"),"cancelled" to v.text("cancelled").toBoolean(),"movedDate" to v.optional("movedDate"),"startPeriod" to v.optional("startPeriod")?.toInt(),"endPeriod" to v.optional("endPeriod")?.toInt(),"room" to v.optional("room"),"semesterId" to s.settings.activeSemesterId)))},remove=e?.let{{execute(operation("courseException.delete",it.id,it.version,args("semesterId" to s.settings.activeSemesterId)))}})
fun semesterEditor(s:AppState,execute:Execute):Editor=Editor("${activeSemesterName(s)} · 学期与作息",listOf(Input("start","第一周周一",s.settings.semesterStart,required=true),Input("weeks","总周数",s.settings.semesterWeeks.toString(),required=true),Input("periods","每行：序号,上课,下课",s.settings.periods.joinToString("\n"){"${it.number},${it.start},${it.end}"},type="multiline",required=true)),help="只修改当前学期的起点和周数；上课作息由所有学期共享。格式示例：1,08:00,08:50。新学期请使用“新建学期”，以保留旧课程。",save={v->val periods=v.text("periods").lines().filter{it.isNotBlank()}.map{line->val p=line.replace('，',',').split(',').map{it.trim()};require(p.size==3){"每行需要序号、上课、下课三个字段"};Period(p[0].toInt(),p[1],p[2])};execute(operation("settings.update",values=args("activeSemesterId" to s.settings.activeSemesterId,"semesterStart" to v.text("start"),"semesterWeeks" to v.text("weeks").toInt(),"periods" to HubraJson.json.parseToJsonElement(HubraJson.json.encodeToString(periods)))))})



