package com.hubra.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hubra.app.ai.*
import com.hubra.app.media.AttachmentManager
import com.hubra.core.*
import coil.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate

@Composable fun TodayScreen(s:AppState,execute:Execute,edit:Edit,run:Run,navigate:(String)->Unit) {
    val context=LocalContext.current
    val date=today()
    val next=CoreFunctions.nextCourse(s,Instant.now())
    val tasks=CoreFunctions.tasksForDate(s,date).filter{!it.completed}
    Page {
        Text("${date.monthValue} 月 ${date.dayOfMonth} 日 · ${CoreFunctions.weekNumber(s.settings,date)?.let{"第 $it 周"}?:"未开学"}",style=MaterialTheme.typography.headlineSmall)
        if(!s.settings.onboardingComplete) Section("欢迎使用拾日","余额与 API 都可以稍后配置；手动功能支持离线使用。课程已按截图预填。") {
            Text("1 · 四账户初始余额    2 · 学期与作息\n3 · API 密钥    4 · 通知与桌面组件")
            ActionRow("开始设置" to {navigate("settings")},"暂时跳过" to {run{execute(operation("settings.update",values=args("onboardingComplete" to true)))}})
        }
        Section("下一节课",next?.date) {
            if(next==null)Empty("接下来没有已知课程")else {Text(next.name,style=MaterialTheme.typography.titleLarge);Text("${next.start}–${next.end} · ${next.room}");if(next.pending)Text("此课程完整周次待补全",color=MaterialTheme.colorScheme.error)}
            ActionRow("查看课表" to {navigate("timetable")},"统一对话" to {navigate("chat")})
        }
        Section("今日已记支出", "退款 ${money(CoreFunctions.refundTotal(s,date))} · 净支出 ${money(CoreFunctions.expenseTotal(s,date)-CoreFunctions.refundTotal(s,date))}"){
            Text(money(CoreFunctions.expenseTotal(s,date)),style=MaterialTheme.typography.headlineLarge)
            ActionRow("记一笔" to {edit(transactionEditor(s,execute))},"今晚核账" to {navigate("reconcile")})
            s.accounts.forEach{a->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(a.name);Text(money(CoreFunctions.balance(s,a.id)))}}
        }
        Section("今日待办 · ${tasks.size}") {
            if(tasks.isEmpty())Empty("今天的待办都已完成，或尚未添加")
            tasks.take(5).forEach{o->TaskLine(o,execute,run,{edit(taskEditor(s,execute,o.task))},taskCourseLabel(s,o.task))}
            ActionRow("新增" to {edit(taskEditor(s,execute))},"查看全部" to {navigate("tasks")})
        }
        Section("晚间日报","本地核账提醒每天 ${s.settings.nightlyTime}；AI 日报会在联网后生成。") {
            val report=s.reports.filter{it.date==date.toString()}.lastOrNull()
            if(report==null)Empty("今天还没有日报")else {Text("截止 ${report.cutoff} · ${report.status}${if(report.stale)" · 数据已更新" else ""}",style=MaterialTheme.typography.labelSmall);Text(report.summaryText.ifBlank { reportSummary(report.content.ifBlank{"等待生成"},3) })}
            ActionRow("生成 / 更新日报" to {run{ReportService(context).refresh(date.toString());"日报已刷新"}},"日报历史" to {navigate("reports")})
        }
    }
}


