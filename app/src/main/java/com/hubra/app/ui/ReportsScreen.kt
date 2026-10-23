package com.hubra.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hubra.core.AppState
import androidx.compose.ui.platform.LocalContext
import com.hubra.app.hubra
import com.hubra.app.ai.ReportService

/** Historical reports stay local; selecting one opens the full report detail. */
@Composable
fun ReportScreen(s: AppState, run: Run, onClose: () -> Unit) {
    val context = LocalContext.current
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("日报历史", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onClose) { Text("返回今日") }
        }
        if (s.reports.isEmpty()) Empty("还没有日报记录")
        s.reports.sortedByDescending { it.date }.forEach { report ->
            val expanded = expandedId == report.id
            Section(report.date, "${report.status}${if (report.stale) " · 数据已更新" else ""}") {
                Text(report.summary.ifBlank { reportSummary(report.content.ifBlank { "等待生成" }, 3) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { expandedId = if (expanded) null else report.id }) { Text(if (expanded) "收起摘要" else "展开摘要") }
                    TextButton(onClick = { selectedId = report.id }) { Text("查看详情") }
                    if (report.status == "failed" || report.stale) TextButton(onClick = {
                        run {
                            context.hubra.repository.update { state -> state.copy(reports = state.reports.map { if (it.id == report.id) it.copy(status = "pending", stale = false, attempts = it.attempts + 1) else it }) }
                            ReportService(context).generate(report.id)
                            "日报已重新生成"
                        }
                    }) { Text("重新生成") }
                    TextButton(onClick = { run { context.hubra.repository.update { state -> state.copy(reports = state.reports.filterNot { it.id == report.id }) }; "已删除本地日报" } }) { Text("删除") }
                }
                if (expanded) Text(report.content.ifBlank { "等待生成" }, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    val selected = s.reports.firstOrNull { it.id == selectedId }
    if (selected != null) {
        AlertDialog(
            onDismissRequest = { selectedId = null },
            title = { Text("${selected.date} 日报详情") },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { Text("截止 ${selected.cutoff} · ${selected.status}"); Spacer(Modifier.height(10.dp)); Text(selected.content.ifBlank { "等待生成" }) } },
            confirmButton = { TextButton(onClick = { selectedId = null }) { Text("关闭") } }
        )
    }
}
