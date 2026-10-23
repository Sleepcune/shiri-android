package com.hubra.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class V03ContractTest {
    private val now = Instant.parse("2026-10-08T02:00:00Z")

    @Test fun schemaThreeAddsDeferralsAndReportSummary() {
        val fresh = InitialData.create()
        assertEquals(3, fresh.schemaVersion)
        assertEquals(5, fresh.settings.aiContextMessageCount)
        assertTrue(fresh.taskDeferrals.isEmpty())
        assertEquals("", DailyReport("r", "2026-10-08", now.toString(), 0).summary)
        val migrated = StateMigration.toV3(fresh.copy(schemaVersion = 2), now)
        assertEquals(3, migrated.schemaVersion)
    }

    @Test fun deferMovesSingleTaskAndOneRepeatOccurrence() {
        val base = InitialData.create().copy(tasks = listOf(
            Task("once", "一次", date = "2026-10-08"),
            Task("daily", "每天", date = "2026-10-08", repeat = "daily")
        ))
        val once = DomainEngine.apply(base, Operation("defer-once", "task.defer", "once", args = buildJsonObject {
            put("targetDate", "2026-10-10")
        }), now)
        assertEquals("success", once.result.status)
        assertEquals("2026-10-10", once.state.tasks.single { it.id == "once" }.date)
        val repeated = DomainEngine.apply(once.state, Operation("defer-repeat", "task.defer", "daily", args = buildJsonObject {
            put("date", "2026-10-08"); put("targetDate", "2026-10-12")
        }), now)
        assertEquals("success", repeated.result.status)
        assertEquals(1, repeated.state.taskDeferrals.size)
        assertEquals("2026-10-08", repeated.state.taskDeferrals.single().sourceDate)
        assertEquals(0, CoreFunctions.tasksForDate(repeated.state, LocalDate.parse("2026-10-08")).count { it.task.id == "daily" })
        assertEquals(1, CoreFunctions.tasksForDate(repeated.state, LocalDate.parse("2026-10-12")).count { it.task.id == "daily" })
    }

    @Test fun semesterUpdateAndHelpersUseValidatedSettings() {
        val base = InitialData.create()
        val updated = DomainEngine.apply(base, Operation("update-term", "semester.update", args = buildJsonObject {
            put("semesterId", "default"); put("name", "新学期"); put("weekCount", 18)
        }), now)
        assertEquals("success", updated.result.status)
        assertEquals("新学期", updated.state.semesters.single().name)
        assertEquals(18, updated.state.settings.semesterWeeks)
        val invalid = DomainEngine.apply(base, Operation("bad-ai", "settings.update", args = buildJsonObject { put("aiContextMessageCount", 21) }), now)
        assertEquals("failed", invalid.result.status)
        assertEquals(0, CoreFunctions.truncateAiContext(base.messages, 0).size)
    }
}
