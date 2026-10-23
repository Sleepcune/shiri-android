package com.hubra.core

import java.time.*
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private class Clarification(message: String): IllegalArgumentException(message)
private class Conflict(message: String): IllegalArgumentException(message)

object DomainEngine {
    private val json=HubraJson.json
    private val collections=listOf("accounts","transactions","meals","courses","courseRules","courseExceptions","tasks","taskCompletions","taskDeferrals","reconciliations","accountRules","profiles","semesters")
    private val createKinds=setOf("transaction.create","meal.create","course.create","courseRule.create","task.create","accountRule.create","course.exception","reconciliation.create","semester.create")
    fun apply(state: AppState, operation: Operation, now: Instant): Transition {
        val normalized=CoreFunctions.normalizeSemesters(state)
        if(normalized!=state)return apply(normalized,operation,now)
        state.operations.firstOrNull{it.id==operation.id}?.let{return Transition(state,it.result)}
        val a=operation.args
        val stamp=now.toString(); val today=now.atZone(CoreFunctions.zone).toLocalDate().toString()
        var s=state; var target=operation.targetId; var message="操作已保存"
        fun id()=target ?: UUID.randomUUID().toString().also{target=it}
        fun version(actual:Long) { if(operation.expectedVersion!=null && operation.expectedVersion!=actual) throw Conflict("记录已被修改，请重新查看后操作") }
        fun account(value:String): Account=s.accounts.firstOrNull{it.id==value} ?: throw IllegalArgumentException("账户不存在：$value")
        fun date()=a.string("date")?.also{LocalDate.parse(it)}?:today
        fun money(key:String="amountCents",positive:Boolean=true):Long=a.long(key)?.also{require(!positive || it>0){"金额必须大于0"}}?:throw Clarification("请补充金额（分）")
        fun transactionInput(old:Transaction?=null):Transaction {
            val type=a.string("type")?:old?.type?:"expense"
            require(type in listOf("expense","income","transfer","refund","adjustment")){"不支持的账务类型"}
            val note=a.string("note")?:old?.note?:""
            val accountId=a.string("accountId")?:old?.accountId?:CoreFunctions.defaultAccount(s,note)
            account(accountId)
            val to=a.nullableString("toAccountId",old?.toAccountId)
            if(type=="transfer") { require(to!=null && to!=accountId){"转账需要两个不同账户"}; account(to!!) }
            val amount=a.long("amountCents")?:old?.amountCents?:money()
            require(type=="adjustment" || amount>0){"金额必须大于0"}
            val d=a.string("date")?:old?.date?:today; LocalDate.parse(d);require(d<=today){"不能将未来日期的计划计为实际流水"}
            val related=a.nullableString("relatedId",old?.relatedId)
            if(related!=null) require(s.transactions.any{it.id==related && !it.deleted}){"关联流水不存在"}
            return Transaction(old?.id?:id(),type,accountId,amount,d,note,a.string("category")?:old?.category?:"其他",if(type=="transfer")to else null,related,old?.createdAt?:stamp,stamp,(old?.version?:0)+1,source=a.string("source")?:old?.source?:"manual")
        }
        fun dirty(date:String, accounts:Set<String> = emptySet(), createdAt:String = stamp) {
            s=s.copy(reconciliations=s.reconciliations.map {
                val cutoff=Instant.parse(it.cutoff);val cutoffDate=cutoff.atZone(CoreFunctions.zone).toLocalDate().toString()
                val recorded=Instant.parse(createdAt)
                val historicalBackfill=recorded.atZone(CoreFunctions.zone).toLocalDate().toString()>date
                if(it.accountId in accounts && (date<cutoffDate || date==cutoffDate && (recorded<=cutoff || historicalBackfill)))it.copy(stale=true,version=it.version+1) else it
            })
        }
        fun dirtyTransaction(t:Transaction)=dirty(t.date,setOfNotNull(t.accountId,t.toAccountId),t.createdAt)
        try {
            require(operation.id.isNotBlank()){"操作ID不能为空"}
            if(operation.kind.startsWith("course.") || operation.kind.startsWith("courseRule.") || operation.kind.startsWith("courseException.")) {
                val expectedSemester=a.string("semesterId")
                if(expectedSemester!=null && expectedSemester!=s.settings.activeSemesterId)throw Conflict("当前学期已切换，请重新查看后操作课程")
            }
            if(operation.kind in createKinds && operation.targetId!=null) {
                require(toEntities(state).values.none{it.containsKey(operation.targetId)}){"创建记录的ID已存在，请使用修改操作或新的ID"}
            }
            when(operation.kind) {
                "account.initialize" -> {
                    val acct=account(a.string("accountId")?:target?:throw Clarification("请选择账户")); version(acct.version)
                    require(acct.initialCents==null){"余额已初始化，请通过校准或补记更正"}
                    val amount=money(positive=false); val d=date(); require(d<=today){"初始余额生效日期不能在未来"}; target=acct.id
                    s=s.copy(accounts=s.accounts.replace(acct.id){acct.copy(initialCents=amount,initialDate=d,version=acct.version+1)})
                    message="已设置${acct.name}初始余额"
                }
                "transaction.create" -> { val t=transactionInput(); s=s.copy(transactions=s.transactions+t); dirtyTransaction(t); message="已记录${t.note.ifBlank{t.type}} ¥${formatMoney(t.amountCents)}" }
                "transaction.update","transaction.delete" -> {
                    val old=s.transactions.firstOrNull{it.id==target && !it.deleted}?:throw Clarification("请明确要修改的流水")
                    version(old.version)
                    val t=if(operation.kind.endsWith("delete"))old.copy(deleted=true,version=old.version+1,modifiedAt=stamp) else transactionInput(old)
                    s=s.copy(transactions=s.transactions.replace(old.id){t}); dirtyTransaction(old); dirtyTransaction(t); message=if(t.deleted)"已删除流水，可撤销" else "已修改流水"
                }
                "reconciliation.create" -> {
                    val acct=account(a.string("accountId")?:throw Clarification("请选择核账账户")); val actual=money("actualCents",false)
                    val predicted=CoreFunctions.balanceAt(s,acct.id,now)
                    val r=Reconciliation(id(),acct.id,actual,predicted,predicted?.let{Math.subtractExact(actual,it)},stamp)
                    s=s.copy(reconciliations=s.reconciliations+r); message="已保存实际余额观察值，账面余额未调整"
                }
                "account.calibrate" -> {
                    val acct=account(a.string("accountId")?:target?:throw Clarification("请选择账户")); version(acct.version)
                    val d=date();require(d<=today){"校准日期不能在未来"}
                    val cutoff=if(d==today)now else LocalDate.parse(d).plusDays(1).atStartOfDay(CoreFunctions.zone).toInstant().minusNanos(1)
                    val balance=CoreFunctions.balanceAt(s,acct.id,cutoff)?:throw Clarification("校准日期没有有效初始余额，请检查初始余额生效日期")
                    val amount=Math.subtractExact(money("actualCents",false),balance)
                    target=null
                    val t=Transaction(id(),"adjustment",acct.id,amount,d,a.string("note")?:"手动余额校准（截至$d）",createdAt=stamp,modifiedAt=stamp)
                    s=s.copy(transactions=s.transactions+t); dirtyTransaction(t); message="已登记余额调整 ¥${formatMoney(amount)}"
                }
                "accountRule.create","accountRule.update","accountRule.delete" -> {
                    val old=s.accountRules.firstOrNull{it.id==target && !it.deleted}
                    if(operation.kind!="accountRule.create") { if(old==null)throw Clarification("默认账户规则不存在"); version(old.version) }
                    val acct=a.string("accountId")?:old?.accountId?:throw Clarification("请选择默认账户"); account(acct)
                    val keyword=a.string("keyword")?:old?.keyword?:throw Clarification("请补充规则关键词"); require(keyword.isNotBlank()){"关键词不能为空"}
                    val rule=AccountRule(old?.id?:id(),keyword,acct,(old?.version?:0)+1,operation.kind.endsWith("delete"))
                    s=s.copy(accountRules=if(old==null)s.accountRules+rule else s.accountRules.replace(old.id){rule})
                }
                "meal.create","meal.update","meal.delete" -> {
                    val old=s.meals.firstOrNull{it.id==target && !it.deleted}
                    if(operation.kind!="meal.create") { if(old==null)throw Clarification("请明确餐食记录"); version(old.version) }
                    val linked=a.nullableString("transactionId",old?.transactionId)
                    if(linked!=null)require(s.transactions.any{it.id==linked && !it.deleted}){"关联账单不存在"}
                    val item=Meal(old?.id?:id(),a.string("date")?:old?.date?:today,a.string("name")?:old?.name?:"",a.foods()?:old?.foods?:"",a.string("portion")?:old?.portion?:"",
                        a.strings("attachmentIds")?:old?.attachmentIds?:emptyList(),linked,a.string("analysis")?:old?.analysis?:"",a.string("source")?:old?.source?:"manual",(old?.version?:0)+1,operation.kind.endsWith("delete"),old?.createdAt?.ifBlank{stamp}?:stamp,stamp)
                    LocalDate.parse(item.date); require(item.foods.isNotBlank() || item.attachmentIds.isNotEmpty() || item.deleted){"请填写吃了什么或添加图片"}
                    s=s.copy(meals=if(old==null)s.meals+item else s.meals.replace(old.id){item}); dirty(item.date)
                }
                "course.create","course.update","course.delete" -> {
                    val old=s.courses.firstOrNull{it.id==target && !it.deleted}
                    if(operation.kind!="course.create"){if(old==null)throw Clarification("请明确课程");version(old.version)}
                    if(old!=null && old.semesterId!=s.settings.activeSemesterId)throw Conflict("课程属于其他学期，请先切换学期")
                    val name=a.string("name")?:old?.name?:throw Clarification("请填写课程名称")
                    require(name.isNotBlank()){"课程名称不能为空"}
                    val item=Course(old?.id?:id(),name,a.string("code")?:old?.code?:"",a.string("teacher")?:old?.teacher?:"",a.string("color")?:old?.color?:"#8EADF0",a.bool("pending")?:old?.pending?:false,a.string("sourceText")?:old?.sourceText?:"",(old?.version?:0)+1,operation.kind.endsWith("delete"),old?.semesterId?:s.settings.activeSemesterId)
                    s=s.copy(courses=if(old==null)s.courses+item else s.courses.replace(old.id){item})
                }
                "courseRule.create","courseRule.update","courseRule.delete" -> {
                    val old=s.courseRules.firstOrNull{it.id==target && !it.deleted}
                    if(operation.kind!="courseRule.create"){if(old==null)throw Clarification("请明确上课规则");version(old.version)}
                    if(old!=null && s.courses.firstOrNull{it.id==old.courseId}?.semesterId!=s.settings.activeSemesterId)throw Conflict("上课规则属于其他学期，请先切换学期")
                    val item=CourseRule(old?.id?:id(),a.string("courseId")?:old?.courseId?:throw Clarification("请选择课程"),a.int("dayOfWeek")?:old?.dayOfWeek?:throw Clarification("请填写星期"),
                        a.int("startPeriod")?:old?.startPeriod?:throw Clarification("请填写开始节次"),a.int("endPeriod")?:old?.endPeriod?:throw Clarification("请填写结束节次"),
                        (a.ints("weeks")?:old?.weeks?:throw Clarification("请填写明确周次")).distinct().sorted(),a.string("room")?:old?.room?:"",a.string("sourceText")?:old?.sourceText?:"",(old?.version?:0)+1,operation.kind.endsWith("delete"))
                    validateRule(s,item)
                    require(s.courses.any{it.id==item.courseId && it.semesterId==s.settings.activeSemesterId}){"请先切换课程所属学期"}
                    require(item.deleted || s.courseRules.none{it.id!=item.id && !it.deleted && sameRule(it,item)}){"相同上课规则已存在"}
                    s=s.copy(courseRules=if(old==null)s.courseRules+item else s.courseRules.replace(old.id){item})
                }
                "course.exception","courseException.update","courseException.delete" -> {
                    val old=s.courseExceptions.firstOrNull{it.id==target && !it.deleted}
                    if(operation.kind!="course.exception"){if(old==null)throw Clarification("请明确调课记录");version(old.version)}
                    val oldCourseId=old?.let{exception->s.courseRules.firstOrNull{it.id==exception.ruleId}?.courseId}
                    if(old!=null && s.courses.firstOrNull{it.id==oldCourseId}?.semesterId!=s.settings.activeSemesterId)throw Conflict("调课记录属于其他学期，请先切换学期")
                    val item=CourseException(old?.id?:id(),a.string("ruleId")?:old?.ruleId?:throw Clarification("请选择上课规则"),a.string("date")?:old?.date?:throw Clarification("请说明哪天的课"),
                        a.bool("cancelled")?:old?.cancelled?:false,a.nullableString("movedDate",old?.movedDate),a.nullableInt("startPeriod",old?.startPeriod),a.nullableInt("endPeriod",old?.endPeriod),a.nullableString("room",old?.room),(old?.version?:0)+1,operation.kind.endsWith("delete"))
                    validateException(s,item)
                    val exceptionCourseId=s.courseRules.single{it.id==item.ruleId}.courseId
                    require(s.courses.any{it.id==exceptionCourseId && it.semesterId==s.settings.activeSemesterId}){"请先切换课程所属学期"}
                    require(item.deleted || s.courseExceptions.none{it.id!=item.id && !it.deleted && it.ruleId==item.ruleId && it.date==item.date}){"该次课程已有调整，请修改已有记录"}
                    s=s.copy(courseExceptions=if(old==null)s.courseExceptions+item else s.courseExceptions.replace(old.id){item})
                }
                "course.calendar.adjust" -> {
                    val holidays=a["holidays"]?.let{json.decodeFromJsonElement<List<CalendarHoliday>>(it)}?:emptyList()
                    val makeups=a["makeups"]?.let{json.decodeFromJsonElement<List<CalendarMakeup>>(it)}?:emptyList()
                    val adjusted=CalendarAdjustments.apply(s,holidays,makeups)
                    s=adjusted.state;message=adjusted.message;target=null
                }
                "course.day.copy" -> {
                    val adjusted=CalendarAdjustments.copyDay(s,a.string("sourceDate")?:throw Clarification("请说明参照哪一天的课程"),
                        a.string("targetDate")?:throw Clarification("请说明复制到哪一天"),a.bool("useBaseSchedule")?:true)
                    s=adjusted.state;message=adjusted.message;target=null
                }
                "course.import" -> {
                    val incomingCourses=a["courses"]?.let{json.decodeFromJsonElement<List<Course>>(it)}?:emptyList()
                    val incomingRules=a["rules"]?.let{json.decodeFromJsonElement<List<CourseRule>>(it)}?:emptyList()
                    val incomingExceptions=a["exceptions"]?.let{json.decodeFromJsonElement<List<CourseException>>(it)}?:emptyList()
                    val mapping=mutableMapOf<String,String>()
                    val ruleMapping=mutableMapOf<String,String>()
                    incomingCourses.forEach { course ->
                        require(course.name.isNotBlank()){"课程名称不能为空"}
                        val existing=s.courses.firstOrNull{!it.deleted && it.semesterId==s.settings.activeSemesterId && ((course.code.isNotBlank() && it.code==course.code) || (it.name==course.name && it.teacher==course.teacher))}
                        val collision=s.courses.firstOrNull{it.id==course.id}
                        val importedId=existing?.id?:if(collision!=null && collision.semesterId!=s.settings.activeSemesterId)UUID.randomUUID().toString() else course.id
                        mapping[course.id]=importedId
                        if(existing==null){require(s.courses.none{it.id==importedId}){"课程ID冲突"};s=s.copy(courses=s.courses+course.copy(id=importedId,version=1,deleted=false,semesterId=s.settings.activeSemesterId))}
                    }
                    incomingRules.forEach { r ->
                        val destinationCourseId=mapping[r.courseId]?:r.courseId
                        require(s.courses.any{it.id==destinationCourseId && it.semesterId==s.settings.activeSemesterId}){"导入上课规则必须属于当前学期"}
                        val collision=s.courseRules.firstOrNull{it.id==r.id}
                        val collisionSemester=collision?.let{oldRule->s.courses.firstOrNull{it.id==oldRule.courseId}?.semesterId}
                        val importedRuleId=if(collision!=null && collisionSemester!=s.settings.activeSemesterId)UUID.randomUUID().toString() else r.id
                        val rule=r.copy(id=importedRuleId,courseId=destinationCourseId,weeks=r.weeks.distinct().sorted(),version=1,deleted=false)
                        validateRule(s,rule)
                        val existing=s.courseRules.firstOrNull{!it.deleted && sameRule(it,rule)}
                        ruleMapping[r.id]=existing?.id?:rule.id
                        if(existing==null){
                            require(s.courseRules.none{it.id==rule.id}){"规则ID冲突"}
                            // Import conflicts are kept for user resolution instead of silently replacing.
                            val currentCourseIds=CoreFunctions.currentCourses(s).map{it.id}.toSet()
                            require(s.courseRules.none{!it.deleted && it.courseId in currentCourseIds && it.dayOfWeek==rule.dayOfWeek && it.weeks.intersect(rule.weeks.toSet()).isNotEmpty() && it.startPeriod<=rule.endPeriod && rule.startPeriod<=it.endPeriod}){"导入课程与已有课时冲突，请先核对"}
                            s=s.copy(courseRules=s.courseRules+rule)
                        }
                    }
                    incomingExceptions.forEach { source ->
                        val destinationRuleId=ruleMapping[source.ruleId]?:source.ruleId
                        val collision=s.courseExceptions.firstOrNull{it.id==source.id}
                        val collisionSemester=collision?.let{exception->s.courseRules.firstOrNull{it.id==exception.ruleId}}?.let{rule->s.courses.firstOrNull{it.id==rule.courseId}?.semesterId}
                        val importedExceptionId=if(collision!=null && collisionSemester!=s.settings.activeSemesterId)UUID.randomUUID().toString() else source.id
                        val item=source.copy(id=importedExceptionId,ruleId=destinationRuleId,version=1,deleted=false)
                        validateException(s,item)
                        val courseId=s.courseRules.single{it.id==item.ruleId}.courseId
                        require(s.courses.any{it.id==courseId && it.semesterId==s.settings.activeSemesterId}){"导入调课必须属于当前学期"}
                        val existing=s.courseExceptions.firstOrNull{!it.deleted && it.ruleId==item.ruleId && it.date==item.date}
                        if(existing!=null)require(existing.cancelled==item.cancelled && existing.movedDate==item.movedDate && existing.startPeriod==item.startPeriod && existing.endPeriod==item.endPeriod && existing.room==item.room){"导入调课与已有调整冲突，请先核对"}
                        else {
                            require(s.courseExceptions.none{it.id==item.id}){"调课记录ID冲突"}
                            s=s.copy(courseExceptions=s.courseExceptions+item)
                        }
                    }
                    message="已导入课程和调课记录，重复内容已跳过"
                }
                "task.create","task.update","task.delete" -> {
                    val old=s.tasks.firstOrNull{it.id==target && !it.deleted}
                    if(operation.kind!="task.create"){if(old==null)throw Clarification("请明确待办");version(old.version)}
                    val title=a.string("title")?:old?.title?:throw Clarification("请填写待办内容")
                    val d=a.nullableString("date",old?.date);d?.let{LocalDate.parse(it)}
                    val time=a.nullableString("time",old?.time);time?.let{LocalTime.parse(it)}
                    val repeat=a.string("repeat")?:old?.repeat?:"none";require(repeat in listOf("none","daily","weekdays","weekly")){"重复规则不支持"}
                    val linked=a.nullableString("courseRuleId",old?.courseRuleId);val courseDate=a.nullableString("courseDate",old?.courseDate)
                    if(linked!=null){require(s.courseRules.any{it.id==linked && !it.deleted}){"关联课程不存在"};require(courseDate!=null){"关联课程需要具体日期"};LocalDate.parse(courseDate!!)}
                    val reminder=a.bool("reminder")?:old?.reminder?:false
                    if(reminder && linked==null && (time==null || d==null))throw Clarification("请补充提醒日期和时间")
                    require(repeat=="none" || d!=null){"重复待办需要开始日期"};require(title.isNotBlank()){"待办不能为空"}
                    val parent=a.nullableString("parentId",old?.parentId)
                    if(parent!=null)require(s.tasks.any{it.id==parent && it.id!=target && !it.deleted && it.parentId==null}){"父任务不存在或不支持多层子任务"}
                    val item=Task(old?.id?:id(),title,a.string("note")?:old?.note?:"",d,time,a.int("priority")?:old?.priority?:0,a.strings("tags")?:old?.tags?:emptyList(),repeat,reminder,linked,courseDate,a.int("offsetMinutes")?:old?.offsetMinutes?:0,parent,old?.completed?:false,(old?.version?:0)+1,operation.kind.endsWith("delete"))
                    s=s.copy(tasks=if(old==null)s.tasks+item else s.tasks.replace(old.id){item})
                }
                "task.complete","task.restore" -> {
                    val task=s.tasks.firstOrNull{it.id==target && !it.deleted}?:throw Clarification("请明确待办");version(task.version)
                    val completed=operation.kind=="task.complete";val d=date()
                    if(task.repeat=="none")s=s.copy(tasks=s.tasks.replace(task.id){task.copy(completed=completed,version=task.version+1)})
                    else {
                        require(CoreFunctions.tasksForDate(s,LocalDate.parse(d)).any{it.task.id==task.id}){"该日期没有这项重复待办"}
                        val existing=s.taskCompletions.filterNot{it.taskId==task.id && it.date==d}
                        s=s.copy(taskCompletions=if(completed)existing+TaskCompletion("${task.id}:$d",task.id,d,stamp) else existing,tasks=s.tasks.replace(task.id){task.copy(version=task.version+1)})
                    }
                    message=if(completed)"已完成待办" else "已恢复待办"
                }
                "task.defer" -> {
                    val task=s.tasks.firstOrNull { it.id==target && !it.deleted } ?: throw Clarification("请明确待办")
                    version(task.version)
                    val source=a.string("date") ?: task.date ?: today
                    val targetDate=a.string("targetDate") ?: a.string("deferredDate") ?: a.string("deferDate") ?: a.string("newDate") ?: a.string("toDate")
                        ?: throw Clarification("请补充延期日期")
                    val targetTime=a.string("time")?.takeIf { it.isNotBlank() }?.also { LocalTime.parse(it) }
                    LocalDate.parse(source); LocalDate.parse(targetDate); require(source != targetDate) { "延期日期必须改变" }
                    if (task.repeat == "none") {
                        require(task.date == source) { "原日期与待办日期不一致" }
                        s=s.copy(tasks=s.tasks.replace(task.id) { task.copy(date=targetDate,time=targetTime ?: task.time,version=task.version+1) })
                    } else {
                        require(CoreFunctions.tasksForDate(s,LocalDate.parse(source)).any { it.task.id==task.id }) { "该日期没有这项重复待办" }
                        val existing=s.taskDeferrals.filterNot { it.taskId==task.id && it.sourceDate==source }
                        val deferral=TaskDeferral("${task.id}:$source",task.id,source,targetDate,targetTime,stamp)
                        s=s.copy(taskDeferrals=existing+deferral)
                    }
                    message="已延期待办"
                }
                "semester.create" -> {
                    val name=a.string("name")?:throw Clarification("请填写学期名称")
                    val start=a.string("startDate")?:throw Clarification("请填写学期第一周周一日期")
                    val weeks=a.int("weekCount")?:20
                    require(name.isNotBlank() && LocalDate.parse(start).dayOfWeek==DayOfWeek.MONDAY && weeks in 1..100){"学期名称、起始日期或周数无效"}
                    val semester=Semester(id(),name,start,weeks)
                    val appearance=s.settings.timetableAppearances[s.settings.activeSemesterId]?:TimetableAppearance()
                    s=s.copy(semesters=s.semesters+semester,settings=s.settings.copy(timetableAppearances=s.settings.timetableAppearances+(semester.id to appearance)))
                    if(a.bool("select")!=false)s=s.copy(settings=s.settings.copy(activeSemesterId=semester.id,semesterStart=start,semesterWeeks=weeks))
                    message="已新建学期：$name"
                }
                "semester.update" -> {
                    val semesterId=a.string("semesterId") ?: target ?: throw Clarification("请选择学期")
                    val old=s.semesters.firstOrNull { it.id==semesterId } ?: throw IllegalArgumentException("学期不存在")
                    version(old.version); target=old.id
                    val name=a.string("name") ?: old.name
                    val start=a.string("startDate") ?: old.startDate
                    val weeks=a.int("weekCount") ?: old.weekCount
                    require(name.isNotBlank() && LocalDate.parse(start).dayOfWeek==DayOfWeek.MONDAY && weeks in 1..100) { "学期名称、起始日期或周数无效" }
                    val updated=old.copy(name=name,startDate=start,weekCount=weeks,version=old.version+1)
                    var candidate=s.copy(semesters=s.semesters.replace(old.id){updated})
                    if(candidate.settings.activeSemesterId==old.id) candidate=candidate.copy(settings=candidate.settings.copy(semesterStart=start,semesterWeeks=weeks))
                    val courseIds=candidate.courses.filter { !it.deleted && it.semesterId==old.id }.map { it.id }.toSet()
                    candidate.courseRules.filter { !it.deleted && it.courseId in courseIds }.forEach { validateRule(candidate,it) }
                    val ruleIds=candidate.courseRules.filter { !it.deleted && it.courseId in courseIds }.map { it.id }.toSet()
                    candidate.courseExceptions.filter { !it.deleted && it.ruleId in ruleIds }.forEach { validateException(candidate,it) }
                    s=candidate
                    message="已更新学期：$name"
                }
                "semester.switch" -> {
                    val semesterId=a.string("semesterId")?:target?:throw Clarification("请选择学期")
                    val semester=s.semesters.firstOrNull{it.id==semesterId}?:throw IllegalArgumentException("学期不存在")
                    version(semester.version);target=semester.id
                    s=s.copy(settings=s.settings.copy(activeSemesterId=semester.id,semesterStart=semester.startDate,semesterWeeks=semester.weekCount))
                    message="已切换至${semester.name}"
                }
                "settings.update" -> {
                    val expectedSemester=a.string("activeSemesterId")
                    if(expectedSemester!=null && expectedSemester!=s.settings.activeSemesterId)throw Conflict("当前学期已切换，请重新打开设置后修改")
                    val settings=json.decodeFromJsonElement<Settings>(JsonObject(json.encodeToJsonElement(s.settings).jsonObject+a))
                    LocalDate.parse(settings.semesterStart);require(LocalDate.parse(settings.semesterStart).dayOfWeek==DayOfWeek.MONDAY){"学期起点应为周一"}
                    require(settings.semesterWeeks in 1..100 && settings.periods.isNotEmpty()){"学期设置无效"}
                    settings.periods.forEach{require(LocalTime.parse(it.start)<LocalTime.parse(it.end)){"课节结束时间必须晚于开始"}}
                    require(settings.periods.map{it.number}.distinct().size==settings.periods.size){"课节序号重复"}
                    require(settings.periods.zipWithNext().all{(x,y)->x.number<y.number && LocalTime.parse(x.end)<=LocalTime.parse(y.start)}){"课节必须按时间顺序排列"}
                    LocalTime.parse(settings.nightlyTime);require(settings.timezone=="Asia/Shanghai"){"当前版本使用中国标准时间"}
                    require(settings.themeMode in setOf("system","light","dark")){"外观模式无效"}
                    require(settings.aiContextMessageCount in 1..20){"AI上下文消息数应为1到20"}
                    require(settings.photoRetentionDays in 1..36500 && settings.lastPhotoCleanupCount>=0 && settings.lastPhotoCleanupBytes>=0){"图片清理设置无效"}
                    require(settings.timetableAppearances.keys.all{id->s.semesters.any{it.id==id}}){"课表外观对应的学期不存在"}
                    settings.timetableAppearances.values.forEach{appearance->
                        require(appearance.fontSizeSp in 8..32 && appearance.rowHeightDp in 32..160 && appearance.cornerRadiusDp in 0..48 && appearance.opacity.isFinite() && appearance.opacity in 0f..1f){"课表外观数值超出范围"}
                        require(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(appearance.backgroundColor)){"课表背景颜色无效"}
                        appearance.backgroundAttachmentId?.let{id->require(s.attachments.any{it.id==id && it.deletedAt==null && !it.deletionPending}){"课表背景图片不可用"}}
                    }
                    require(settings.activeSemesterId==s.settings.activeSemesterId){"请使用切换学期操作"}
                    val semester=s.semesters.first{it.id==settings.activeSemesterId}
                    val changed=semester.startDate!=settings.semesterStart || semester.weekCount!=settings.semesterWeeks
                    val semesters=if(changed)s.semesters.replace(semester.id){semester.copy(startDate=settings.semesterStart,weekCount=settings.semesterWeeks,version=semester.version+1)} else s.semesters
                    val updated=s.copy(settings=settings,semesters=semesters)
                    val activeCourseIds=updated.courses.filter{!it.deleted}.map{it.id}.toSet()
                    val activeRules=updated.courseRules.filter{!it.deleted && it.courseId in activeCourseIds}
                    activeRules.forEach{validateRule(updated,it)}
                    val activeRuleIds=activeRules.map{it.id}.toSet()
                    updated.courseExceptions.filter{!it.deleted && it.ruleId in activeRuleIds}.forEach{validateException(updated,it)}
                    s=updated
                }
                "undo" -> {
                    val requested=a.string("operationId")?:target?:throw Clarification("请选择要撤销的操作")
                    val record=s.operations.firstOrNull{it.id==requested}?:throw IllegalArgumentException("操作不存在")
                    require(!record.undone && record.result.status=="success" && record.changes.isNotEmpty()){"此操作不能撤销"}
                    val current=toEntities(s)
                    record.changes.forEach { c -> if(current[c.collection]?.get(c.id)!=c.after)throw Conflict("${c.collection}中的记录已有后续修改，无法直接撤销") }
                    val laterIds=s.operations.dropWhile{it.id!=record.id}.drop(1).filter{!it.undone}.flatMap{it.changes}.map{it.collection to it.id}.toSet()
                    val references=mapOf(
                        "accounts" to mapOf("transactions" to listOf("accountId","toAccountId"),"accountRules" to listOf("accountId"),"reconciliations" to listOf("accountId")),
                        "transactions" to mapOf("transactions" to listOf("relatedId"),"meals" to listOf("transactionId")),
                        "courses" to mapOf("courseRules" to listOf("courseId")),
                        "courseRules" to mapOf("courseExceptions" to listOf("ruleId"),"tasks" to listOf("courseRuleId")),
                        "tasks" to mapOf("tasks" to listOf("parentId"),"taskCompletions" to listOf("taskId"),"taskDeferrals" to listOf("taskId"))
                        ,"semesters" to mapOf("courses" to listOf("semesterId"))
                    )
                    record.changes.forEach { change ->
                        references[change.collection]?.forEach { (dependentCollection,fields) ->
                            current[dependentCollection]?.forEach { (entityId,serialized) ->
                                if(dependentCollection to entityId in laterIds) {
                                    val entity=json.parseToJsonElement(serialized).jsonObject
                                    if(entity["deleted"]?.jsonPrimitive?.booleanOrNull!=true && fields.any{entity[it]?.jsonPrimitive?.contentOrNull==change.id})throw Conflict("已有后续记录依赖此操作，请先处理关联记录")
                                }
                            }
                        }
                    }
                    val reversed=record.changes.map { change ->
                        val previous=change.before?.let{json.parseToJsonElement(it).jsonObject}
                        val currentEntity=change.after?.let{json.parseToJsonElement(it).jsonObject}
                        // Undo is a new edit: preserve deleted rows and never reuse old version tokens.
                        val restored=if(currentEntity?.containsKey("version")==true) {
                            val nextVersion=currentEntity["version"]!!.jsonPrimitive.long+1
                            if(previous!=null)JsonObject(previous+mapOf("version" to JsonPrimitive(nextVersion)))
                            else if(currentEntity.containsKey("deleted"))JsonObject(currentEntity+mapOf("version" to JsonPrimitive(nextVersion),"deleted" to JsonPrimitive(true)))
                            else null
                        } else previous
                        change.copy(after=restored?.toString())
                    }
                    val restored=restore(s,reversed)
                    if(reversed.any{it.collection=="settings"})restored.settings.timetableAppearances.values.forEach{appearance->
                        appearance.backgroundAttachmentId?.let{imageId->
                            if(restored.attachments.none{it.id==imageId && it.deletedAt==null && !it.deletionPending})throw Conflict("原课表背景图片已删除或正在删除，无法恢复该设置")
                        }
                    }
                    s=restored.copy(operations=s.operations.map{if(it.id==requested)it.copy(undone=true) else it})
                    message="已撤销操作";target=record.result.targetId
                }
                else -> throw IllegalArgumentException("不支持的操作：${operation.kind}")
            }
            // Reject identity collisions and overflow before a write becomes visible.
            val serialized=json.encodeToJsonElement(s).jsonObject
            collections.forEach { key ->
                val ids=serialized[key]!!.jsonArray.map{it.jsonObject["id"]!!.jsonPrimitive.content}
                require(ids.none{it.isBlank()} && ids.distinct().size==ids.size){"$key 的记录ID重复或为空"}
            }
            s.accounts.forEach{CoreFunctions.balance(s,it.id)}
            val changes=diff(state,s)
            val affected=changes.map{it.id}.distinct()
            val result=OperationResult("success",message,target,operation.id,affected)
            s=s.copy(revision=state.revision+1,reports=markReports(state,s),operations=s.operations+OperationRecord(operation.id,operation.kind,result,stamp,changes))
            return Transition(s,result)
        } catch(e:Exception) {
            val status=when(e){is Clarification->"needs_clarification";is Conflict->"conflict";else->"failed"}
            val result=OperationResult(status,e.message?:"操作无效",target,operation.id)
            return Transition(state.copy(operations=state.operations+OperationRecord(operation.id,operation.kind,result,stamp)),result)
        }
    }
    private fun validatePeriod(s:AppState,start:Int,end:Int){require(start<=end && s.settings.periods.any{it.number==start} && s.settings.periods.any{it.number==end}){"节次范围无效"}}
    private fun validateRule(s:AppState,r:CourseRule){val course=s.courses.firstOrNull{it.id==r.courseId && !it.deleted}?:throw IllegalArgumentException("课程不存在");val settings=CoreFunctions.settingsForCourse(s,course);require(r.dayOfWeek in 1..7 && r.weeks.isNotEmpty() && r.weeks.all{it in 1..settings.semesterWeeks}){"星期或周次无效"};validatePeriod(s,r.startPeriod,r.endPeriod)}
    private fun validateException(s:AppState,item:CourseException) {
        val original=LocalDate.parse(item.date);item.movedDate?.let{LocalDate.parse(it)}
        val rule=s.courseRules.firstOrNull{it.id==item.ruleId && !it.deleted}?:throw IllegalArgumentException("上课规则不存在")
        val course=s.courses.firstOrNull{it.id==rule.courseId && !it.deleted}?:throw IllegalArgumentException("课程不存在")
        require(original.dayOfWeek.value==rule.dayOfWeek && CoreFunctions.weekNumber(CoreFunctions.settingsForCourse(s,course),original) in rule.weeks){"该日期没有这次课程"}
        validatePeriod(s,item.startPeriod?:rule.startPeriod,item.endPeriod?:rule.endPeriod)
    }
    private fun sameRule(a:CourseRule,b:CourseRule)=a.courseId==b.courseId && a.dayOfWeek==b.dayOfWeek && a.startPeriod==b.startPeriod && a.endPeriod==b.endPeriod && a.weeks.toSet()==b.weeks.toSet() && a.room==b.room
    private fun markReports(before:AppState,after:AppState):List<DailyReport> = after.reports.map { report ->
        if(report.stale) report else {
            val day=LocalDate.parse(report.date);val tomorrow=day.plusDays(1)
            fun finances(state:AppState)=state.transactions.filter{!it.deleted && it.date==report.date}.map{it.copy(version=0,modifiedAt="")}
            fun balances(state:AppState)=state.accounts.associate{it.id to CoreFunctions.balanceAt(state,it.id,Instant.parse(report.cutoff))}
            fun meals(state:AppState)=state.meals.filter{!it.deleted && it.date==report.date}.map{it.copy(version=0,modifiedAt="")}
            fun tasks(state:AppState)=CoreFunctions.tasksForDate(state,tomorrow).map{it.copy(task=it.task.copy(version=0))}
            val changed=finances(before)!=finances(after) || balances(before)!=balances(after) || meals(before)!=meals(after) ||
                tasks(before)!=tasks(after) || CoreFunctions.occurrences(before,tomorrow)!=CoreFunctions.occurrences(after,tomorrow) || before.settings.health!=after.settings.health
            if(changed)report.copy(stale=true) else report
        }
    }
    private fun toEntities(s:AppState):Map<String,Map<String,String>> {
        val obj=json.encodeToJsonElement(s).jsonObject
        return collections.associateWith{key->obj[key]!!.jsonArray.associate{it.jsonObject["id"]!!.jsonPrimitive.content to it.toString()}}+mapOf("settings" to mapOf("settings" to obj["settings"].toString()))
    }
    private fun diff(before:AppState,after:AppState):List<EntityChange> {
        val a=toEntities(before);val b=toEntities(after)
        return a.keys.flatMap{collection->(a[collection]!!.keys+b[collection]!!.keys).distinct().mapNotNull{id->val old=a[collection]!![id];val new=b[collection]!![id];if(old==new)null else EntityChange(collection,id,old,new)}}
    }
    private fun restore(s:AppState,changes:List<EntityChange>):AppState {
        val obj=json.encodeToJsonElement(s).jsonObject.toMutableMap()
        changes.groupBy{it.collection}.forEach{(collection,patches)->
            if(collection=="settings")obj[collection]=json.parseToJsonElement(patches.single().after!!) else {
                val items=obj[collection]!!.jsonArray.associateBy{it.jsonObject["id"]!!.jsonPrimitive.content}.toMutableMap()
                patches.forEach{if(it.after==null)items.remove(it.id) else items[it.id]=json.parseToJsonElement(it.after)}
                obj[collection]=JsonArray(items.values.toList())
            }
        }
        return json.decodeFromJsonElement(JsonObject(obj))
    }
    private inline fun <reified T> List<T>.replace(id:String,transform:(T)->T):List<T> = map { if(json.encodeToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.content==id)transform(it) else it }
    fun formatMoney(cents:Long):String=java.math.BigDecimal.valueOf(cents,2).toPlainString()
}

private fun fieldName(key:String)=mapOf("foods" to "菜品","name" to "名称","portion" to "份量","analysis" to "饮食分析","date" to "日期","time" to "时间", "note" to "备注","title" to "标题",
    "attachmentIds" to "图片列表","tags" to "标签","accountId" to "账户","toAccountId" to "转入账户","category" to "分类","type" to "类型","transactionId" to "关联账单",
    "courseId" to "课程","ruleId" to "上课安排","sourceDate" to "原日期","targetDate" to "目标日期","room" to "教室","teacher" to "教师","code" to "课程代码",
    "semesterId" to "学期","activeSemesterId" to "当前学期","startDate" to "开始日期","keyword" to "关键词","repeat" to "重复规则","source" to "记录来源","operationId" to "操作记录")[key]?:"填写内容"
private fun JsonObject.string(k:String):String? {
    val raw=this[k]?:return null
    if(raw==JsonNull)return null
    val primitive=raw as? JsonPrimitive
    if(primitive==null || !primitive.isString)throw Clarification("${fieldName(k)}需要填写文字，请补充或更正")
    return primitive.content
}
private fun JsonObject.foods():String? {
    val raw=this["foods"]?:return null
    if(raw==JsonNull)return null
    if(raw is JsonPrimitive && raw.isString)return raw.content.trim()
    if(raw is JsonArray && raw.all{it is JsonPrimitive && it.isString})return raw.map{(it as JsonPrimitive).content.trim()}.filter{it.isNotEmpty()}.joinToString("、")
    throw Clarification("菜品需要填写文字或菜品名称列表，请补充吃了什么")
}
private fun JsonObject.nullableString(k:String,previous:String?):String?=if(containsKey(k))string(k) else previous
private fun JsonObject.nullableInt(k:String,previous:Int?):Int?=if(containsKey(k))if(this[k] is JsonNull)null else int(k) else previous
private fun JsonObject.long(k:String):Long? {
    if(!containsKey(k))return null
    val value=this[k] as? JsonPrimitive
    require(value!=null && value !is JsonNull && !value.isString && value.longOrNull!=null){"$k 必须是整数，不能为null"}
    return value.long
}
private fun JsonObject.int(k:String):Int? {
    if(!containsKey(k))return null
    val value=this[k] as? JsonPrimitive
    require(value!=null && value !is JsonNull && !value.isString && value.intOrNull!=null){"$k 必须是有效整数，不能为null"}
    return value.int
}
private fun JsonObject.bool(k:String):Boolean? {
    if(!containsKey(k))return null
    val value=this[k] as? JsonPrimitive
    require(value!=null && value !is JsonNull && !value.isString && value.booleanOrNull!=null){"$k 必须是布尔值，不能为null"}
    return value.boolean
}
private fun JsonObject.strings(k:String):List<String>? {
    val raw=this[k]?:return null
    if(raw !is JsonArray || raw.any{it !is JsonPrimitive || !it.isString})throw Clarification("${fieldName(k)}需要填写文字列表，请补充或更正")
    return raw.map{(it as JsonPrimitive).content}
}
private fun JsonObject.ints(k:String):List<Int>?=this[k]?.let { value -> if(value is JsonArray)value.map{it.jsonPrimitive.int} else parseWeeks(value.jsonPrimitive.content) }

fun parseWeeks(text:String):List<Int> = text.replace("周","").replace("第","").replace('，',',').replace('、',',').replace('；',',').replace('–','-').replace('—','-').split(',').flatMap { piece ->
    val p=piece.trim();if(p.isBlank())emptyList() else if(p.contains('-')){val range=p.split('-').map{it.trim().toInt()};require(range.size==2 && range[0]>=1 && range[0]<=range[1] && range[1]<=100);(range[0]..range[1]).toList()}else listOf(p.toInt().also{require(it in 1..100)})
}.distinct().sorted()
