package com.hubra.app

import com.hubra.app.ui.NavigationState
import com.hubra.app.ui.ScreenKey
import com.hubra.app.ui.key
import com.hubra.app.ui.isOverdueTask
import com.hubra.app.ui.mergeMealAttachmentIds
import com.hubra.app.ui.reportSummary
import org.junit.Test
import kotlin.test.*

class NavigationStateTest {
    @Test fun launchStartsWithConversation() { assertEquals("chat", NavigationState().route) }
    @Test fun settingsReturnsToOriginalTabAndNestedPagesPopOneAtATime() {
        val state = NavigationState().tab("ledger").push("settings").push("settings:backup")
        assertEquals("settings", state.back()!!.route)
        assertEquals("ledger", state.back()!!.back()!!.route)
    }
    @Test fun mainTabReturnsToChatBeforeExiting() {
        assertEquals("chat", NavigationState().tab("timetable").back()!!.route)
        assertNull(NavigationState().back())
    }
    @Test fun changingTabsDoesNotLeaveSettingsInBackStack() {
        assertEquals("tasks", NavigationState().push("settings").tab("tasks").route)
        assertEquals("chat", NavigationState().push("settings").tab("tasks").back()!!.route)
    }
    @Test fun repeatedRouteDoesNotDuplicateStack() {
        assertEquals(1, NavigationState().push("settings").push("settings").stack.size)
    }

    @Test fun screenKeyKeepsNestedPageIdentity() {
        assertEquals(ScreenKey("settings:backup"), key("settings:backup"))
        assertEquals("settings:backup", key("settings:backup").value)
    }

    @Test fun overdueTaskRequiresPastDateAndIncompleteState() {
        assertTrue(isOverdueTask("2026-10-10", false, "2026-10-11"))
        assertFalse(isOverdueTask("2026-10-10", true, "2026-10-11"))
        assertFalse(isOverdueTask(null, false, "2026-10-11"))
    }

    @Test fun mealAttachmentEditAddsAndRemovesWithoutDuplicates() {
        assertEquals(listOf("a", "c"), mergeMealAttachmentIds(listOf("a", "b"), listOf("c", "a"), listOf("b")))
    }

    @Test fun reportSummaryCollapsesLongContent() {
        assertEquals("第一行\n第二行\n…", reportSummary("第一行\n第二行\n第三行", 2))
    }
}
