package com.hubra.core

import java.time.*
import java.time.temporal.ChronoUnit

object CoreFunctions {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    fun normalizeSemesters(state:AppState):AppState {
        val legacy=state.semesters.isEmpty()
        val semesters=if(legacy) {
            val date=LocalDate.parse(state.settings.semesterStart)
            listOf(Semester("default","${date.year} ${if(date.monthValue>=7)"秋季" else "春季"}",state.settings.semesterStart,state.settings.semesterWeeks))
        } else state.semesters
        val selected=semesters.firstOrNull{it.id==state.settings.activeSemesterId}?:semesters.first()
        val courses=if(legacy)state.courses.map{if(it.semesterId=="default")it else it.copy(semesterId="default")} else state.courses
        return state.copy(semesters=semesters,courses=courses,settings=state.settings.copy(activeSemesterId=selected.id,semesterStart=selected.startDate,semesterWeeks=selected.weekCount))
    }
    fun currentCourses(state:AppState):List<Course> { val s=normalizeSemesters(state);return s.courses.filter{!it.deleted && it.semesterId==s.settings.activeSemesterId} }
    fun settingsForCourse(state:AppState,course:Course):Settings {
        val s=normalizeSemesters(state)
        val semester=s.semesters.firstOrNull{it.id==course.semesterId}?:throw IllegalArgumentException("课程所属学期不存在")
        return s.settings.copy(activeSemesterId=semester.id,semesterStart=semester.startDate,semesterWeeks=semester.weekCount)
    }
    fun balance(state: AppState, accountId: String): Long? = balanceAt(state, accountId, null)
    fun balanceAt(state: AppState, accountId: String, cutoff: Instant?): Long? {
        val a = state.accounts.firstOrNull { it.id == accountId } ?: return null
        var total = a.initialCents ?: return null
        val date = cutoff?.atZone(zone)?.toLocalDate()?.toString()
        if(date!=null && a.initialDate!=null && date<a.initialDate) return null
        state.transactions.filter { !it.deleted && (a.initialDate == null || it.date >= a.initialDate) && (date == null || it.date <= date) &&
            (cutoff == null || it.date < date!! || Instant.parse(it.createdAt) <= cutoff || Instant.parse(it.createdAt).atZone(zone).toLocalDate().toString()>it.date) }.forEach { t ->
            if (t.accountId == accountId) total = Math.addExact(total, when(t.type) { "expense", "transfer" -> -t.amountCents; else -> t.amountCents })
            if (t.type == "transfer" && t.toAccountId == accountId) total = Math.addExact(total,t.amountCents)
        }
        return total
    }
    fun expenseTotal(state: AppState, date: LocalDate): Long = state.transactions.filter { !it.deleted && it.type == "expense" && it.date == date.toString() }.fold(0L){ a,t -> Math.addExact(a,t.amountCents) }
    fun refundTotal(state: AppState, date: LocalDate): Long = state.transactions.filter { !it.deleted && it.type == "refund" && it.date == date.toString() }.fold(0L){a,t -> Math.addExact(a,t.amountCents)}
    fun weekNumber(settings: Settings, date: LocalDate): Int? {
        val days = ChronoUnit.DAYS.between(LocalDate.parse(settings.semesterStart),date)
        if(days < 0) return null
        return (days/7+1).toInt()
    }
    fun defaultAccount(state: AppState, text: String): String {
        val explicit = listOf("微信" to "wechat", "校园卡" to "campus", "交通卡" to "transport", "银行卡" to "bank").firstOrNull { text.contains(it.first) }
        if(explicit != null) return explicit.second
        state.accountRules.firstOrNull{!it.deleted && text.contains(it.keyword)}?.let{return it.accountId}
        if(listOf("网购","网上","淘宝","京东","拼多多","天猫").any{text.contains(it)}) return "bank"
        if(listOf("早餐","早饭","午餐","午饭","晚餐","晚饭","夜宵","吃饭","食堂","外卖","餐饮").any{text.contains(it)}) return "campus"
        if(listOf("公交","地铁","公共交通").any{text.contains(it)}) return "transport"
        return "wechat"
    }
    fun occurrences(state: AppState, date: LocalDate): List<CourseOccurrence> {
        val normalized=normalizeSemesters(state)
        if(normalized!=state)return occurrences(normalized,date)
        val items=mutableListOf<CourseOccurrence>()
        state.courseRules.filter{!it.deleted}.forEach { rule ->
            val course=state.courses.firstOrNull{it.id==rule.courseId && !it.deleted && it.semesterId==state.settings.activeSemesterId}?:return@forEach
            val overrides=state.courseExceptions.filter{!it.deleted && it.ruleId==rule.id}
            fun add(actual: LocalDate, original: String, exception: CourseException?) {
                val sp=exception?.startPeriod ?: rule.startPeriod; val ep=exception?.endPeriod ?: rule.endPeriod
                val start=state.settings.periods.firstOrNull{it.number==sp}?:return
                val end=state.settings.periods.firstOrNull{it.number==ep}?:return
                items.add(CourseOccurrence("${rule.id}:$original",course.id,rule.id,course.name,course.teacher,course.color,actual.toString(),sp,ep,start.start,end.end,exception?.room?:rule.room,course.pending))
            }
            val week=weekNumber(state.settings,date)
            if(date.dayOfWeek.value==rule.dayOfWeek && week in rule.weeks) {
                val ex=overrides.firstOrNull{it.date==date.toString()}
                if(ex?.cancelled!=true && (ex?.movedDate==null || ex.movedDate==date.toString())) add(date,date.toString(),ex)
            }
            overrides.filter{!it.cancelled && it.movedDate==date.toString() && it.date!=date.toString()}.forEach{add(date,it.date,it)}
        }
        return items.sortedBy{it.start}
    }
    fun nextCourse(state: AppState, now: Instant): CourseOccurrence? {
        val local=now.atZone(zone)
        for(offset in 0L..140L) {
            val date=local.toLocalDate().plusDays(offset)
            occurrences(state,date).firstOrNull{ LocalDateTime.parse("${it.date}T${it.end}").atZone(zone).toInstant()>now }?.let{return it}
        }
        return null
    }
    fun taskOccurrence(state:AppState,task:Task):TaskOccurrence? {
        if(task.deleted)return null
        val s=normalizeSemesters(state)
        val ruleId=task.courseRuleId?:return TaskOccurrence(task,task.date,task.time,task.completed)
        val rule=s.courseRules.firstOrNull{it.id==ruleId && !it.deleted}?:return null
        val course=s.courses.firstOrNull{it.id==rule.courseId && !it.deleted}?:return null
        val original=task.courseDate?:task.date?:return null
        if(course.pending)return TaskOccurrence(task,original,null,task.completed)
        val override=s.courseExceptions.firstOrNull{!it.deleted && it.ruleId==ruleId && it.date==original}
        if(override?.cancelled==true)return null
        val actual=override?.movedDate?:original
        val semesterView=s.copy(settings=settingsForCourse(s,course))
        val linked=occurrences(semesterView,LocalDate.parse(actual)).firstOrNull{it.ruleId==ruleId && it.id=="$ruleId:$original"}?:return null
        val due=LocalDateTime.parse("${linked.date}T${linked.end}").plusMinutes(task.offsetMinutes.toLong())
        return TaskOccurrence(task,due.toLocalDate().toString(),due.toLocalTime().toString(),task.completed)
    }
    fun tasksForDate(state: AppState, date: LocalDate): List<TaskOccurrence> = state.tasks.filter{!it.deleted}.mapNotNull { task ->
        val resolved=taskOccurrence(state,task)?:return@mapNotNull null
        val due=resolved.date?.let(LocalDate::parse)
        val time=resolved.time
        fun applies(day: LocalDate): Boolean = if(due==null) true else when(task.repeat) {
            "daily" -> day>=due
            "weekdays" -> day>=due && day.dayOfWeek.value<=5
            "weekly" -> day>=due && day.dayOfWeek==due.dayOfWeek
            else -> day==due
        }
        val movedIn = state.taskDeferrals.asSequence().filter { !it.deleted && it.taskId==task.id && it.targetDate==date.toString() }
            .map { it to LocalDate.parse(it.sourceDate) }.firstOrNull { applies(it.second) }
        val movedOut = state.taskDeferrals.any { !it.deleted && it.taskId==task.id && it.sourceDate==date.toString() && it.targetDate!=date.toString() && applies(date) }
        val shouldShow = movedIn != null || (applies(date) && !movedOut)
        if(!shouldShow) null else TaskOccurrence(task,if(due==null)null else date.toString(),movedIn?.first?.targetTime ?: time, if(task.repeat=="none")task.completed else state.taskCompletions.any{it.taskId==task.id && it.date==date.toString()})
    }.sortedWith(compareBy<TaskOccurrence>{it.completed}.thenBy{it.time?:"23:59"}.thenByDescending{it.task.priority})

    /** Whether an unfinished task occurrence is before the supplied day. */
    fun isTaskExpired(task: Task, today: LocalDate): Boolean {
        if (task.deleted || task.completed) return false
        val due = task.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return when (task.repeat) {
            "none" -> due < today
            else -> due < today
        }
    }
    fun isTaskExpired(state: AppState, task: Task, today: LocalDate): Boolean {
        if (task.deleted) return false
        val due = taskOccurrence(state, task)?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        if (due >= today) return false
        if (task.repeat == "none") return !task.completed
        var day = due
        while (day < today) {
            val occurrence = tasksForDate(state, day).firstOrNull { it.task.id == task.id }
            if (occurrence != null && !occurrence.completed) return true
            day = day.plusDays(1)
        }
        return false
    }
    fun isExpiredTask(state: AppState, task: Task, today: LocalDate): Boolean = isTaskExpired(state, task, today)
    fun isExpired(task: Task, today: LocalDate): Boolean = isTaskExpired(task, today)
    fun expiredTasks(state: AppState, today: LocalDate): List<TaskOccurrence> = state.tasks.filter { isTaskExpired(state, it, today) }
        .mapNotNull { task -> taskOccurrence(state, task)?.let { it.copy(date = it.date ?: task.date) } }
    fun overdueTasks(state: AppState, today: LocalDate): List<TaskOccurrence> = expiredTasks(state, today)

    /** Sum the current balances of accounts which have an initialized opening balance. */
    fun initializedAccountTotal(state: AppState): Long = state.accounts.filter { it.initialCents != null }
        .mapNotNull { balance(state, it.id) }.fold(0L) { total, value -> Math.addExact(total, value) }
    fun accountInitializedTotal(state: AppState): Long = initializedAccountTotal(state)
    fun initializedAccountsTotal(state: AppState): Long = initializedAccountTotal(state)
    fun initialBalanceTotal(state: AppState): Long = initializedAccountTotal(state)
    fun initializedAccountTotal(state: AppState, accountId: String): Long? = state.accounts.firstOrNull { it.id == accountId && it.initialCents != null }?.let { balance(state, it.id) }

    /** Keep the newest messages for an AI request while preserving their original order. */
    fun truncateAiContext(messages: List<Message>, maxCount: Int): List<Message> = messages.takeLast(maxCount.coerceAtLeast(0))
    fun aiContextMessages(state: AppState): List<Message> = truncateAiContext(state.messages, state.settings.aiContextMessageCount)
    fun truncateAiMessages(messages: List<Message>, maxCount: Int): List<Message> = truncateAiContext(messages, maxCount)
}
