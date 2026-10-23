package com.hubra.app.system

import com.hubra.core.*
import java.time.*

data class ScheduledReminder(val key: String, val at: Instant, val kind: String, val title: String, val body: String, val page: String)

data class ReminderNotificationIdentity(val tag: String, val id: Int = 0)

/** Stable identity and stale-notification detection for one reminder. */
object ReminderNotification {
    fun identity(key: String): ReminderNotificationIdentity = ReminderNotificationIdentity(tag = key)

    fun planForJournal(
        currentPlan: List<ScheduledReminder>,
        previous: List<ScheduledReminder>,
        state: AppState,
        deliveredTokens: Set<String>,
        now: Instant
    ): List<ScheduledReminder> {
        val zone = ZoneId.of(state.settings.timezone)
        val today = now.atZone(zone).toLocalDate()
        val visibleDelivered = previous.filter {
            val token = ReminderReschedule.token(it)
            token in deliveredTokens && it.at.atZone(zone).toLocalDate() == today && ReminderPlan.isCurrent(state, it)
        }
        return (currentPlan + visibleDelivered).distinctBy(ReminderReschedule::token).sortedBy(ScheduledReminder::at)
    }

    fun obsolete(
        previous: List<ScheduledReminder>,
        currentPlan: List<ScheduledReminder>,
        state: AppState,
        deliveredTokens: Set<String>,
        now: Instant
    ): List<String> {
        val activeTokens = currentPlan.map(ReminderReschedule::token).toSet()
        val zone = ZoneId.of(state.settings.timezone)
        val today = now.atZone(zone).toLocalDate()
        return previous.asSequence()
            .filter { it.kind != "refresh" }
            .filter {
                val token = ReminderReschedule.token(it)
                val deliveredToday = token in deliveredTokens && it.at.atZone(zone).toLocalDate() == today
                !ReminderPlan.isCurrent(state, it) || (token !in activeTokens && !deliveredToday)
            }
            .map(ScheduledReminder::key)
            .distinct()
            .toList()
    }
}

/** The immutable information carried by a task notification's completion action. */
data class TaskCompletionRequest(val taskId: String, val date: String, val expectedVersion: Long)

/**
 * Validates an action before it reaches the domain engine. Notification actions can
 * outlive a task edit, deletion, or a completed repeated occurrence, so an invalid
 * request is represented by null and safely ignored by the receiver.
 */
object TaskReminderCompletion {
    fun request(state: AppState, key: String, expectedVersion: Long?): TaskCompletionRequest? {
        if (!key.startsWith("task:")) return null
        val body = key.removePrefix("task:")
        val separator = body.lastIndexOf(':')
        if (separator <= 0 || separator == body.lastIndex) return null
        val taskId = body.substring(0, separator)
        val date = body.substring(separator + 1)
        runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val task = state.tasks.firstOrNull { it.id == taskId && !it.deleted } ?: return null
        if (expectedVersion != null && expectedVersion != task.version) return null
        val occurrence = CoreFunctions.tasksForDate(state, LocalDate.parse(date))
            .firstOrNull { it.task.id == taskId && it.date == date }
            ?: return null
        if (occurrence.completed || !task.reminder || occurrence.date == null || occurrence.time == null) return null
        val zone = ZoneId.of(state.settings.timezone)
        val due = LocalDate.parse(occurrence.date).atTime(LocalTime.parse(occurrence.time)).atZone(zone).toInstant()
        val reminder = ScheduledReminder(key, due, "task", task.title, task.note, "tasks")
        if (!ReminderPlan.isCurrent(state, reminder)) return null
        return TaskCompletionRequest(taskId, date, task.version)
    }
}

/** Pure planner: daily refresh extends the horizon; individual occurrence IDs prevent collisions. */
object ReminderPlan {
    fun courseOccurrence(state:AppState,reminder:ScheduledReminder):CourseOccurrence? {
        if(reminder.kind!="course" || state.settings.courseReminderMinutes<0)return null
        val actualDate=reminder.at.plusSeconds(state.settings.courseReminderMinutes*60L).atZone(ZoneId.of(state.settings.timezone)).toLocalDate()
        return CoreFunctions.occurrences(state,actualDate).firstOrNull{"course:${it.id}"==reminder.key && !it.pending}
    }
    fun isCurrent(state: AppState, reminder: ScheduledReminder): Boolean {
        if (reminder.kind == "refresh") return true
        val zone = ZoneId.of(state.settings.timezone)
        if (reminder.kind == "nightly") return state.settings.nightlyReminder &&
            reminder.at.atZone(zone).toLocalTime() == LocalTime.parse(state.settings.nightlyTime)
        val date = runCatching { LocalDate.parse(reminder.key.substringAfterLast(':')) }.getOrNull() ?: return false
        if (reminder.kind == "course") {
            if (state.settings.courseReminderMinutes < 0) return false
            val course = courseOccurrence(state,reminder) ?: return false
            return LocalDate.parse(course.date).atTime(LocalTime.parse(course.start)).atZone(zone).toInstant().minusSeconds(state.settings.courseReminderMinutes * 60L) == reminder.at
        }
        if (reminder.kind == "task") {
            val occurrence = CoreFunctions.tasksForDate(state, date).firstOrNull { "task:${it.task.id}:${it.date}" == reminder.key && !it.completed && it.task.reminder && it.time != null } ?: return false
            if (occurrence.task.courseRuleId != null) {
                val rule = state.courseRules.firstOrNull { it.id == occurrence.task.courseRuleId && !it.deleted }
                val course = state.courses.firstOrNull { it.id == rule?.courseId && !it.deleted }
                if (course == null || course.pending) return false
            }
            return date.atTime(LocalTime.parse(occurrence.time)).atZone(zone).toInstant() == reminder.at
        }
        return false
    }
    fun build(state: AppState, now: Instant): List<ScheduledReminder> {
        val zone = ZoneId.of(state.settings.timezone)
        val today = now.atZone(zone).toLocalDate()
        val reminders = mutableListOf<ScheduledReminder>()
        if (state.settings.nightlyReminder) {
            var next = today.atTime(LocalTime.parse(state.settings.nightlyTime)).atZone(zone).toInstant()
            if (next <= now) next = today.plusDays(1).atTime(LocalTime.parse(state.settings.nightlyTime)).atZone(zone).toInstant()
            reminders += ScheduledReminder("nightly:$next", next, "nightly", "晚间核账", "核对今天的支出和账户余额", "ledger")
        }
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            CoreFunctions.occurrences(state, date).filter { !it.pending }.forEach { course ->
                val start = date.atTime(LocalTime.parse(course.start)).atZone(zone).toInstant()
                val end = date.atTime(LocalTime.parse(course.end)).atZone(zone).toInstant()
                if (state.settings.courseReminderMinutes >= 0) {
                    val at = start.minusSeconds(state.settings.courseReminderMinutes * 60L)
                    if (at > now) reminders += ScheduledReminder("course:${course.id}", at, "course", course.name, "${course.start} · ${course.room}", "timetable")
                }
                if (start > now) reminders += ScheduledReminder("boundary:start:${course.id}", start, "refresh", "", "", "today")
                if (end > now) reminders += ScheduledReminder("boundary:end:${course.id}", end, "refresh", "", "", "today")
            }
            CoreFunctions.tasksForDate(state, date).filter { !it.completed && it.task.reminder && it.date != null && it.time != null }.forEach { task ->
                if (task.task.courseRuleId != null) {
                    val rule = state.courseRules.firstOrNull { it.id == task.task.courseRuleId && !it.deleted }
                    val course = state.courses.firstOrNull { it.id == rule?.courseId && !it.deleted }
                    if (course == null || course.pending) return@forEach
                }
                val at = date.atTime(LocalTime.parse(task.time)).atZone(zone).toInstant()
                if (at > now) reminders += ScheduledReminder("task:${task.task.id}:${task.date}", at, "task", task.task.title, task.task.note.ifBlank { "待办提醒" }, "tasks")
            }
        }
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
        reminders += ScheduledReminder("midnight:$midnight", midnight, "refresh", "", "", "today")
        return reminders.distinctBy { it.key }.sortedBy { it.at }
    }
}

object ReportSnapshot {
    fun at(state: AppState, cutoff: Instant): AppState = state.copy(
        transactions = state.transactions.filter { Instant.parse(it.createdAt) <= cutoff },
        meals = state.meals.filter { it.createdAt.isBlank() || Instant.parse(it.createdAt) <= cutoff }
    )
}

data class ReportRecoveryPlan(val enqueueIds: List<String>, val missingIds: List<String>)
object ReportRecovery {
    const val missingSourceMessage = "日报原始快照缺失，请手动重新生成。"
    fun plan(reports: List<DailyReport>, availableSnapshotIds: Set<String>): ReportRecoveryPlan {
        val pending = reports.filter { it.status in setOf("pending", "failed") && it.attempts < 3 }
        val missing = reports.filter { it.status in setOf("pending", "failed") && it.id !in availableSnapshotIds &&
            !(it.status == "failed" && it.attempts >= 3 && it.stale && it.content.contains(missingSourceMessage)) }
        return ReportRecoveryPlan(pending.filter { it.id in availableSnapshotIds }.map { it.id }, missing.map { it.id })
    }
}
