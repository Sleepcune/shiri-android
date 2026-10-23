package com.hubra.app

import com.hubra.app.system.*
import com.hubra.core.*
import java.time.Instant
import kotlin.test.*

class ReminderRescheduleTest {
    private val state=InitialData.create()
    @Test fun dataEditAfterDeadlineKeepsUndeliveredCourseAndTask() {
        val s=state.copy(tasks=listOf(Task("lunch","午间待办",date="2026-10-09",time="13:45",reminder=true)))
        val previous=ReminderPlan.build(s,Instant.parse("2026-10-09T05:44:00Z"))
        val at=Instant.parse("2026-10-09T05:45:04Z")
        val rebuilt=ReminderReschedule.build(s,at,previous,emptySet())
        assertTrue(rebuilt.any{it.key=="course:control-fri:2026-10-09"})
        assertTrue(rebuilt.any{it.key=="task:lunch:2026-10-09"})
    }
    @Test fun consumedAlarmNeverReschedulesButChangedReminderTimeHasNewToken() {
        val previous=ReminderPlan.build(state,Instant.parse("2026-10-09T05:44:00Z"))
        val course=previous.single{it.key=="course:control-fri:2026-10-09"}
        assertFalse(ReminderReschedule.build(state,Instant.parse("2026-10-09T05:45:04Z"),previous,setOf(ReminderReschedule.token(course))).any{it.key==course.key})
        val modified=state.copy(settings=state.settings.copy(courseReminderMinutes=10))
        val newly=ReminderReschedule.build(modified,Instant.parse("2026-10-09T05:46:00Z"),previous,setOf(ReminderReschedule.token(course)))
        assertEquals(Instant.parse("2026-10-09T05:50:00Z"),newly.single{it.key==course.key}.at)
    }
    @Test fun completedTaskAndCanceledCourseAreNotRecovered() {
        val task=Task("lunch","午间待办",date="2026-10-09",time="13:45",reminder=true)
        val s=state.copy(tasks=listOf(task));val previous=ReminderPlan.build(s,Instant.parse("2026-10-09T05:44:00Z"))
        val current=s.copy(tasks=listOf(task.copy(completed=true)),courseExceptions=listOf(CourseException("cancel","control-fri","2026-10-09",cancelled=true)))
        val rebuilt=ReminderReschedule.build(current,Instant.parse("2026-10-09T05:45:04Z"),previous,emptySet())
        assertFalse(rebuilt.any{it.key=="task:lunch:2026-10-09" || it.key=="course:control-fri:2026-10-09"})
    }
    @Test fun noHistoricalSpamAfterDayChangesOrClassEnds() {
        val previous=ReminderPlan.build(state,Instant.parse("2026-10-09T05:44:00Z"))
        assertFalse(ReminderReschedule.build(state,Instant.parse("2026-10-09T09:30:00Z"),previous,emptySet()).any{it.key=="course:control-fri:2026-10-09"})
        assertFalse(ReminderReschedule.build(state,Instant.parse("2026-10-10T01:00:00Z"),previous,emptySet()).any{it.kind!="refresh"&&it.at<Instant.parse("2026-10-09T16:00:00Z")})
    }
    @Test fun movedHolidayCourseValidatesUsingActualDateNotOriginalOccurrenceIdDate() {
        val s=state.copy(courseExceptions=listOf(CourseException("holiday-move","ai-wed","2026-10-07",movedDate="2026-10-10")))
        val reminder=ReminderPlan.build(s,Instant.parse("2026-10-09T01:00:00Z")).single{it.key=="course:ai-wed:2026-10-07"}
        assertEquals(Instant.parse("2026-10-10T05:45:00Z"),reminder.at)
        assertTrue(ReminderPlan.isCurrent(s,reminder))
        assertEquals("2026-10-10",ReminderPlan.courseOccurrence(s,reminder)?.date)
    }
    @Test fun atExactDeadlineCanBeRecoveredAfterReboot() {
        val previous=ReminderPlan.build(state,Instant.parse("2026-10-09T05:44:00Z"))
        val rebuilt=ReminderReschedule.build(state,Instant.parse("2026-10-09T05:45:00Z"),previous,emptySet())
        assertTrue(rebuilt.any{it.key=="course:control-fri:2026-10-09"})
    }
    @Test fun editedTaskTimeInvalidatesOldDelayedAlarm() {
        val task=Task("lunch","午间待办",date="2026-10-09",time="13:45",reminder=true)
        val s=state.copy(tasks=listOf(task))
        val previous=ReminderPlan.build(s,Instant.parse("2026-10-09T05:44:00Z"))
        val updated=s.copy(tasks=listOf(task.copy(time="14:00")))
        val current=ReminderReschedule.build(updated,Instant.parse("2026-10-09T05:45:04Z"),previous,emptySet())
        assertEquals(Instant.parse("2026-10-09T06:00:00Z"),current.single{it.key=="task:lunch:2026-10-09"}.at)
    }
    @Test fun delayedNightlyIsRetainedAlongsideTomorrow() {
        val previous=ReminderPlan.build(state,Instant.parse("2026-10-09T13:59:00Z"))
        val current=ReminderReschedule.build(state,Instant.parse("2026-10-09T14:00:04Z"),previous,emptySet())
        assertEquals(listOf(Instant.parse("2026-10-09T14:00:00Z"),Instant.parse("2026-10-10T14:00:00Z")),current.filter{it.kind=="nightly"}.map{it.at})
    }
}
