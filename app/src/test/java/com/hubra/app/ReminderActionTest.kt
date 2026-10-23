package com.hubra.app

import com.hubra.app.system.TaskCompletionRequest
import com.hubra.app.system.TaskReminderCompletion
import com.hubra.core.*
import kotlin.test.*

class ReminderActionTest {
    private val state = InitialData.create()

    @Test fun ordinaryTaskActionCompletesEntityRequest() {
        val task = Task("todo", "取快递", date = "2026-10-11", time = "20:00", reminder = true)
        val request = TaskReminderCompletion.request(state.copy(tasks = listOf(task)), "task:todo:2026-10-11", task.version)
        assertEquals(TaskCompletionRequest("todo", "2026-10-11", task.version), request)
    }

    @Test fun repeatingTaskActionTargetsTheClickedDate() {
        val task = Task("daily", "喝水", date = "2026-10-10", time = "20:00", reminder = true, repeat = "daily")
        val request = TaskReminderCompletion.request(state.copy(tasks = listOf(task)), "task:daily:2026-10-11", task.version)
        assertEquals(TaskCompletionRequest("daily", "2026-10-11", task.version), request)
    }

    @Test fun linkedTaskActionUsesTheResolvedCourseOccurrenceTime() {
        val task = Task("linked", "问老师", date = "2026-10-09", reminder = true, courseRuleId = "control-fri", courseDate = "2026-10-09", offsetMinutes = 30)
        val request = TaskReminderCompletion.request(state.copy(tasks = listOf(task)), "task:linked:2026-10-09", task.version)
        assertEquals(TaskCompletionRequest("linked", "2026-10-09", task.version), request)
    }

    @Test fun linkedTaskActionIsRejectedWhenItsCourseBecomesPending() {
        val task = Task("linked", "问老师", date = "2026-10-09", time = "19:00", reminder = true, courseRuleId = "control-fri", courseDate = "2026-10-09", offsetMinutes = 30)
        val pendingState = state.copy(
            tasks = listOf(task),
            courses = state.courses.map { if (it.id == "control") it.copy(pending = true) else it }
        )
        assertNull(TaskReminderCompletion.request(pendingState, "task:linked:2026-10-09", task.version))
    }

    @Test fun staleVersionAndAlreadyCompletedActionAreRejected() {
        val task = Task("todo", "取快递", date = "2026-10-11", time = "20:00", reminder = true, version = 3)
        val current = state.copy(tasks = listOf(task))
        assertNull(TaskReminderCompletion.request(current, "task:todo:2026-10-11", expectedVersion = 2))
        assertNull(TaskReminderCompletion.request(current.copy(tasks = listOf(task.copy(completed = true))), "task:todo:2026-10-11", task.version))
    }

    @Test fun malformedOrMissingOccurrenceActionIsRejected() {
        val task = Task("daily", "喝水", date = "2026-10-10", time = "20:00", reminder = true, repeat = "daily")
        val current = state.copy(tasks = listOf(task))
        assertNull(TaskReminderCompletion.request(current, "task:daily:not-a-date", task.version))
        assertNull(TaskReminderCompletion.request(current, "task:daily:2026-10-09", task.version))
    }
}
