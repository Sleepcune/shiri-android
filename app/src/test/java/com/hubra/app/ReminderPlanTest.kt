package com.hubra.app

import com.hubra.app.system.ReminderNotification
import com.hubra.app.system.ReminderPlan
import com.hubra.app.system.ReminderReschedule
import com.hubra.app.system.ReportSnapshot
import com.hubra.app.system.ReportRecovery
import com.hubra.app.system.ScheduledReminder
import com.hubra.core.*
import java.time.Instant
import kotlin.test.*

class ReminderPlanTest {
    private val state = InitialData.create()
    @Test fun nextNightlyIsTodayAtTen() {
        val plan = ReminderPlan.build(state, Instant.parse("2026-10-07T13:00:00Z"))
        assertEquals(Instant.parse("2026-10-07T14:00:00Z"), plan.single { it.kind == "nightly" }.at)
    }
    @Test fun exactTenMovesNightlyToTomorrow() {
        val plan = ReminderPlan.build(state, Instant.parse("2026-10-07T14:00:00Z"))
        assertEquals(Instant.parse("2026-10-08T14:00:00Z"), plan.single { it.kind == "nightly" }.at)
    }
    @Test fun confirmedCourseRemindsFifteenMinutesBeforeClass() {
        val plan = ReminderPlan.build(state, Instant.parse("2026-10-07T13:00:00Z"))
        assertEquals(Instant.parse("2026-10-09T05:45:00Z"), plan.single { it.key == "course:control-fri:2026-10-09" }.at)
    }
    @Test fun pendingCourseAndLinkedTaskDoNotSendReminders() {
        val task = Task("linked", "问建模老师", date = "2026-10-07", reminder = true, courseRuleId = "modeling-candidate", courseDate = "2026-10-07", offsetMinutes = 30)
        val plan = ReminderPlan.build(state.copy(tasks = listOf(task)), Instant.parse("2026-10-06T14:00:00Z"))
        assertFalse(plan.any { it.kind == "course" && it.key.contains("modeling") })
        assertFalse(plan.any { it.kind == "task" && it.key.contains("linked") })
    }
    @Test fun completedRepeatOccurrenceIsSkippedButTomorrowIsKept() {
        val task = Task("daily", "喝水", date = "2026-10-07", time = "20:00", reminder = true, repeat = "daily")
        val completion = TaskCompletion("completed", "daily", "2026-10-07", "2026-10-07T08:00:00Z")
        val plan = ReminderPlan.build(state.copy(tasks = listOf(task), taskCompletions = listOf(completion)), Instant.parse("2026-10-07T09:00:00Z"))
        assertFalse(plan.any { it.key == "task:daily:2026-10-07" })
        assertTrue(plan.any { it.key == "task:daily:2026-10-08" })
    }
    @Test fun linkedReminderMovesWithCourseAndKeepsOffset() {
        val task = Task("linked", "问老师", date = "2026-10-09", reminder = true, courseRuleId = "control-fri", courseDate = "2026-10-09", offsetMinutes = 30)
        val exception = CourseException("move", "control-fri", "2026-10-09", movedDate = "2026-10-10", startPeriod = 3, endPeriod = 4)
        val plan = ReminderPlan.build(state.copy(tasks = listOf(task), courseExceptions = listOf(exception)), Instant.parse("2026-10-07T09:00:00Z"))
        assertEquals(Instant.parse("2026-10-10T04:30:00Z"), plan.single { it.key == "task:linked:2026-10-10" }.at)
        assertFalse(plan.any { it.key == "task:linked:2026-10-09" })
    }
    @Test fun disabledRemindersKeepOnlyNecessaryRefreshes() {
        val plan = ReminderPlan.build(state.copy(settings = state.settings.copy(nightlyReminder = false, courseReminderMinutes = -1)), Instant.parse("2026-10-07T09:00:00Z"))
        assertTrue(plan.all { it.kind == "refresh" })
    }
    @Test fun switchingToEmptySemesterStopsOldCourseRemindersButKeepsGlobalTasks() {
        val emptySemester = Semester("empty", "2027春季", "2027-02-22", 20)
        val task = Task("global", "取快递", date = "2026-10-07", time = "20:00", reminder = true)
        val switched = state.copy(semesters = state.semesters + emptySemester,
            settings = state.settings.copy(activeSemesterId = "empty", semesterStart = emptySemester.startDate, semesterWeeks = emptySemester.weekCount), tasks = listOf(task))
        val plan = ReminderPlan.build(switched, Instant.parse("2026-10-07T09:00:00Z"))
        assertFalse(plan.any { it.kind == "course" })
        assertTrue(plan.any { it.key == "task:global:2026-10-07" })
        val staleCourse = ScheduledReminder("course:control-fri:2026-10-09", Instant.parse("2026-10-09T05:45:00Z"), "course", "", "", "timetable")
        assertFalse(ReminderPlan.isCurrent(switched, staleCourse))
    }
    @Test fun queuedTaskAlarmIsDiscardedAfterCompletionOrTimeChange() {
        val task = Task("global", "取快递", date = "2026-10-07", time = "20:00", reminder = true)
        val current = state.copy(tasks = listOf(task))
        val reminder = ReminderPlan.build(current, Instant.parse("2026-10-07T09:00:00Z")).single { it.kind == "task" }
        assertTrue(ReminderPlan.isCurrent(current, reminder))
        assertFalse(ReminderPlan.isCurrent(current.copy(tasks = listOf(task.copy(completed = true))), reminder))
        assertFalse(ReminderPlan.isCurrent(current.copy(tasks = listOf(task.copy(time = "21:00"))), reminder))
    }
    @Test fun completedTaskNotificationIsObsoleteWithoutAffectingOtherReminders() {
        val completed = Task("completed", "取快递", date = "2026-10-07", time = "20:00", reminder = true)
        val kept = Task("kept", "交作业", date = "2026-10-07", time = "21:00", reminder = true)
        val before = state.copy(tasks = listOf(completed, kept))
        val now = Instant.parse("2026-10-07T09:00:00Z")
        val previous = ReminderPlan.build(before, now)
        val after = before.copy(tasks = listOf(completed.copy(completed = true), kept))
        val currentPlan = ReminderPlan.build(after, now)

        assertEquals(
            listOf("task:completed:2026-10-07"),
            ReminderNotification.obsolete(previous, currentPlan, after, emptySet(), now)
        )
    }
    @Test fun deliveredReminderStaysVisibleDuringSameDayRefresh() {
        val task = Task("delivered", "取快递", date = "2026-10-07", time = "20:00", reminder = true)
        val stateWithTask = state.copy(tasks = listOf(task))
        val due = Instant.parse("2026-10-07T12:00:00Z")
        val previous = ReminderPlan.build(stateWithTask, due.minusSeconds(1))
        val delivered = previous.single { it.kind == "task" }
        val currentPlan = ReminderPlan.build(stateWithTask, due.plusSeconds(1))

        assertTrue(
            ReminderNotification.obsolete(
                previous, currentPlan, stateWithTask, setOf(ReminderReschedule.token(delivered)), due.plusSeconds(1)
            ).isEmpty()
        )
        assertEquals(
            listOf(delivered.key),
            ReminderNotification.planForJournal(currentPlan, previous, stateWithTask, setOf(ReminderReschedule.token(delivered)), due.plusSeconds(1))
                .filter { it.kind == "task" }
                .map { it.key }
        )
    }
    @Test fun notificationIdentityKeepsDifferentReminderKeysIndependent() {
        assertNotEquals(ReminderNotification.identity("task:a:2026-10-07"), ReminderNotification.identity("task:b:2026-10-07"))
        assertEquals(ReminderNotification.identity("task:a:2026-10-07"), ReminderNotification.identity("task:a:2026-10-07"))
    }
    @Test fun delayedNightlySnapshotExcludesMealsAndTransactionsAddedAfterCutoff() {
        val cutoff = Instant.parse("2026-10-07T14:00:00Z")
        val transaction = Transaction("late", accountId = "campus", amountCents = 100, date = "2026-10-07", createdAt = "2026-10-07T14:01:00Z", modifiedAt = "2026-10-07T14:01:00Z")
        val meals = listOf(Meal("early", "2026-10-07", createdAt = "2026-10-07T13:00:00Z"), Meal("late", "2026-10-07", createdAt = "2026-10-07T14:01:00Z"))
        val snapshot = ReportSnapshot.at(state.copy(transactions = listOf(transaction), meals = meals), cutoff)
        assertTrue(snapshot.transactions.isEmpty())
        assertEquals(listOf("early"), snapshot.meals.map { it.id })
    }
    @Test fun reportRecoveryOnlyQueuesNonterminalReportsWithSnapshots() {
        fun report(id: String, status: String, attempts: Int) = DailyReport(id, "2026-10-07", "2026-10-07T14:00:00Z", 1, status = status, attempts = attempts)
        val reports = listOf(report("pending", "pending", 0), report("retry", "failed", 2), report("exhausted", "failed", 3), report("ready", "ready", 1), report("missing", "pending", 0))
        val plan = ReportRecovery.plan(reports, setOf("pending", "retry", "exhausted", "ready"))
        assertEquals(listOf("pending", "retry"), plan.enqueueIds)
        assertEquals(listOf("missing"), plan.missingIds)
        val terminalMissing = report("missing", "failed", 3).copy(stale = true, content = ReportRecovery.missingSourceMessage)
        assertTrue(ReportRecovery.plan(listOf(terminalMissing), emptySet()).missingIds.isEmpty())
    }
}
