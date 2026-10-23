package com.hubra.app

import com.hubra.app.widgets.WidgetLayoutPolicy
import kotlin.test.*

class WidgetLayoutPolicyTest {
    @Test fun compactCourseCardKeepsThreeFactsWithinItsBody() {
        val plan = WidgetLayoutPolicy.forSize("NEXT", 140f, 100f)
        assertTrue(plan.bodyHeight >= 56)
        assertTrue(plan.reservedHeight + 56 <= 100)
        assertTrue(plan.titleSp in 12..14)
        assertTrue(plan.secondarySp in 10..12)
    }
    @Test fun shortWideWidgetUsesOneStripInsteadOfTallHeaderAndFooter() {
        val plan = WidgetLayoutPolicy.forSize("NEXT", 280f, 70f)
        assertTrue(plan.strip)
        assertEquals(0, plan.headerHeight)
        assertEquals(0, plan.footerHeight)
        assertTrue(plan.bodyHeight >= 48)
    }
    @Test fun resizingChangesTaskCapacityAndPreservesOverflowBudget() {
        val small = WidgetLayoutPolicy.forSize("TASKS", 140f, 100f)
        val medium = WidgetLayoutPolicy.forSize("TASKS", 280f, 160f)
        val large = WidgetLayoutPolicy.forSize("TASKS", 280f, 320f)
        assertTrue(small.taskCapacity < medium.taskCapacity)
        assertTrue(medium.taskCapacity < large.taskCapacity)
        listOf(small, medium, large).forEach {
            assertTrue(it.taskCapacity * it.taskRowHeight + it.overflowHeight <= it.bodyHeight)
            assertTrue(it.reservedHeight + it.bodyHeight <= it.height)
        }
    }
    @Test fun rowsDoNotClipAtCapacityBoundaryOrWideSeventyDpHeight() {
        listOf(70f, 100f, 119f, 120f, 159f, 160f, 319f, 320f).forEach { height ->
            listOf("TODAY", "TASKS", "WEEK").forEach { kind ->
                val plan = WidgetLayoutPolicy.forSize(kind, 280f, height)
                assertTrue(plan.courseCapacity * plan.courseRowHeight + plan.overflowHeight <= plan.bodyHeight)
                assertTrue(plan.taskCapacity * plan.taskRowHeight + plan.overflowHeight <= plan.bodyHeight)
                assertTrue(plan.weekCapacity * plan.weekRowHeight + plan.overflowHeight <= plan.bodyHeight)
            }
        }
    }
    @Test fun overviewUsesSummaryOnSmallSizesAndBalancedSectionsOnTallSizes() {
        val small = WidgetLayoutPolicy.forSize("OVERVIEW", 140f, 100f)
        val large = WidgetLayoutPolicy.forSize("OVERVIEW", 280f, 320f)
        assertEquals(0, small.overviewTaskCapacity)
        assertTrue(large.overviewTaskCapacity >= 3)
        assertTrue(large.overviewCourseCapacity >= 1)
        assertTrue(large.overviewCourseCapacity * large.courseRowHeight + large.overviewTaskCapacity * large.taskRowHeight + 36 <= large.bodyHeight)
    }
}
