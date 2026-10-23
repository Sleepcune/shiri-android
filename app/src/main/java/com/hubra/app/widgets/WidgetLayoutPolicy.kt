package com.hubra.app.widgets

import kotlin.math.floor

/** A fixed vertical budget shared by the RemoteViews rendering and sizing tests. */
data class WidgetLayoutPlan(
    val height: Int, val padding: Int, val strip: Boolean,
    val headerHeight: Int, val footerHeight: Int, val bodyHeight: Int,
    val titleSp: Int = 13, val secondarySp: Int = 11,
    val courseRowHeight: Int = 34, val taskRowHeight: Int = 28,
    val overflowHeight: Int = 16,
    val courseCapacity: Int, val taskCapacity: Int, val weekRowHeight: Int, val weekCapacity: Int,
    val overviewCourseCapacity: Int, val overviewTaskCapacity: Int
) {
    val reservedHeight: Int get() = padding * 2 + headerHeight + footerHeight
}

object WidgetLayoutPolicy {
    fun forSize(kind: String, widthDp: Float, heightDp: Float): WidgetLayoutPlan {
        val height = heightDp.toInt().coerceAtLeast(1)
        val strip = widthDp >= 240 && height < 100
        val padding = if (height < 80) 6 else 8
        val header = if (height < 100) 0 else 20
        val footer = if (kind == "TASKS" && height >= 100) 16 else 0
        val body = (height - padding * 2 - header - footer).coerceAtLeast(0)
        fun capacity(row: Int, max: Int) = floor((body - 16).coerceAtLeast(0) / row.toDouble()).toInt().coerceAtMost(max)
        val summaryOnly = height < 150
        val overviewCourses = if (summaryOnly) 0 else if (height >= 250) 2 else 1
        val overviewTasks = if (summaryOnly) 0 else ((body - overviewCourses * 34 - 36).coerceAtLeast(0) / 28).coerceAtMost(6)
        val weekRow = if (height >= 150) 34 else 18
        return WidgetLayoutPlan(height, padding, strip, header, footer, body,
            courseCapacity = capacity(34, 8), taskCapacity = capacity(28, 9), weekRowHeight = weekRow, weekCapacity = capacity(weekRow, 7),
            overviewCourseCapacity = overviewCourses, overviewTaskCapacity = overviewTasks)
    }
}
