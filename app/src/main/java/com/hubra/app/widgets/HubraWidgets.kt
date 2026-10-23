package com.hubra.app.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.*
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.hubra.app.hubra
import com.hubra.core.*
import kotlinx.serialization.json.*
import java.time.*
import java.util.UUID

private val maskKey = booleanPreferencesKey("mask")
private val accountKey = stringPreferencesKey("account")
private val filterKey = stringPreferencesKey("filter")
private val themeKey = stringPreferencesKey("theme")
private val alphaKey = floatPreferencesKey("alpha")

enum class WidgetKind(val title: String, val page: String) {
    NEXT("下一节课", "timetable"), TODAY("今日课表", "timetable"), WEEK("本周课表", "timetable"),
    TASKS("待办清单", "tasks"), SPENDING("今日支出", "ledger"), OVERVIEW("拾日", "today")
}

object Widgets {
    private fun all(): List<HubraWidget> = listOf(NextCourseWidget(), TodayCoursesWidget(), WeekCoursesWidget(), TasksWidget(), SpendingWidget(), OverviewWidget())
    suspend fun refresh(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        all().forEach { widget ->
            manager.getGlanceIds(widget.javaClass).forEach { id ->
                val appId = manager.getAppWidgetId(id)
                updateAppWidgetState(context, id) { prefs ->
                    prefs[maskKey] = context.hubra.preferences.get("widget.$appId.mask") != "false"
                    prefs[accountKey] = context.hubra.preferences.get("widget.$appId.account") ?: "campus"
                    prefs[filterKey] = context.hubra.preferences.get("widget.$appId.filter") ?: "today"
                    prefs[themeKey] = context.hubra.preferences.get("widget.$appId.theme") ?: "light"
                    prefs[alphaKey] = context.hubra.preferences.get("widget.$appId.alpha")?.toFloatOrNull()?.coerceIn(.65f, 1f) ?: .96f
                    prefs[longPreferencesKey("refreshedAt")] = System.currentTimeMillis()
                }
                widget.update(context, id)
            }
        }
    }
}

abstract class HubraWidget(private val kind: WidgetKind) : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(140.dp, 70.dp), DpSize(140.dp, 100.dp), DpSize(280.dp, 70.dp), DpSize(280.dp, 160.dp), DpSize(280.dp, 320.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = context.hubra.repository.snapshot()
        val appId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val state = context.hubra.repository.state.collectAsState(initial).value
            val prefs = currentState<Preferences>()
            val dark = prefs[themeKey] == "dark"
            val foreground = if (dark) Color(0xFFF6F4FF) else Color(0xFF232336)
            val secondary = if (dark) Color(0xFFDBD7EF) else Color(0xFF535266)
            val accent = if (dark) Color(0xFFD5C5FF) else Color(0xFF6247B3)
            val background = (if (dark) Color(0xFF232333) else Color(0xFFF7F5FE)).copy(alpha = (prefs[alphaKey] ?: .96f).coerceIn(.65f, 1f))
            val today = LocalDate.now(CoreFunctions.zone)
            val size = LocalSize.current
            val plan = WidgetLayoutPolicy.forSize(kind.name, size.width.value, size.height.value)
            val config = actionStartActivity(Intent(context, WidgetConfigActivity::class.java).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appId))
            Column(GlanceModifier.fillMaxSize().background(background).padding(plan.padding.dp)) {
                if (plan.headerHeight > 0) {
                    Row(GlanceModifier.fillMaxWidth().height(plan.headerHeight.dp), verticalAlignment = Alignment.CenterVertically) {
                        val caption = when(kind) {
                            WidgetKind.WEEK -> "第${CoreFunctions.weekNumber(state.settings, today) ?: "—"}周 · ${semesterName(state)}"
                            WidgetKind.TODAY -> "今日课表 · ${semesterName(state)}"
                            WidgetKind.OVERVIEW -> "拾日 · ${today.monthValue}月${today.dayOfMonth}日"
                            else -> kind.title
                        }
                        label(caption, foreground, plan.titleSp, GlanceModifier.defaultWeight().clickable(open(context, kind.page)), bold = true)
                        label("⋯", foreground, 14, GlanceModifier.width(20.dp).clickable(config))
                    }
                }
                Column(GlanceModifier.fillMaxWidth().height(plan.bodyHeight.dp)) {
                    when (kind) {
                        WidgetKind.NEXT -> nextCourse(context, state, today, plan, foreground, secondary, config)
                        WidgetKind.TODAY -> todayCourses(context, state, today, plan, foreground, secondary, accent, config)
                        WidgetKind.WEEK -> weekCourses(context, state, today, plan, foreground, secondary, accent, config)
                        WidgetKind.TASKS -> taskList(context, taskItems(state, today, prefs[filterKey] ?: "today"), today, plan, foreground, secondary, accent, config)
                        WidgetKind.SPENDING -> spending(context, state, today, prefs[accountKey] ?: "campus", prefs[maskKey] != false, plan, foreground, secondary, config)
                        WidgetKind.OVERVIEW -> overview(context, state, today, prefs[maskKey] != false, plan, foreground, secondary, accent, config)
                    }
                }
                if (plan.footerHeight > 0) label("＋ 新增待办", accent, 11, GlanceModifier.height(plan.footerHeight.dp).clickable(open(context, "tasks", "add")))
            }
        }
    }
}

@Composable private fun label(text: String, color: Color, font: Int, modifier: GlanceModifier = GlanceModifier, bold: Boolean = false, maxLines: Int = 1) {
    Text(text, modifier, style = TextStyle(color = ColorProvider(color), fontSize = font.sp, fontWeight = if(bold) FontWeight.Bold else FontWeight.Normal), maxLines = maxLines)
}
@Composable private fun courseRow(context: Context, course: CourseOccurrence, primary: Color, secondary: Color) {
    Column(GlanceModifier.fillMaxWidth().height(34.dp).clickable(open(context, "timetable", course.id))) {
        label(course.name + if(course.pending) " · 待补周次" else "", primary, 13, GlanceModifier.height(18.dp), bold = true)
        label("${course.start}–${course.end} · ${course.room.ifBlank { "教室待补" }}", secondary, 11, GlanceModifier.height(16.dp))
    }
}
@Composable private fun stripHeading(text: String, primary: Color, config: Action) {
    Row(GlanceModifier.fillMaxWidth().height(18.dp)) {
        label(text, primary, 12, GlanceModifier.defaultWeight(), bold = true)
        label("⋯", primary, 14, GlanceModifier.width(20.dp).clickable(config))
    }
}
@Composable private fun nextCourse(context: Context, state: AppState, today: LocalDate, plan: WidgetLayoutPlan, primary: Color, secondary: Color, config: Action) {
    val course = CoreFunctions.nextCourse(state, Instant.now())
    if (course == null) {
        if(plan.strip) stripHeading("下一节课", primary, config)
        label("近期没有课程", secondary, 12, GlanceModifier.height(20.dp).clickable(open(context,"timetable")))
        return
    }
    if(plan.strip) {
        Row(GlanceModifier.fillMaxWidth().height(plan.bodyHeight.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(open(context,"timetable",course.id))) {
                label("下一节 · ${dayCaption(course.date,today)}", secondary, 11, GlanceModifier.height(16.dp))
                label(course.name, primary, 13, GlanceModifier.height(36.dp), bold = true, maxLines = 2)
            }
            Column(GlanceModifier.defaultWeight().padding(start=8.dp).clickable(open(context,"timetable",course.id))) {
                Row(GlanceModifier.fillMaxWidth().height(18.dp)) {
                    label("${course.start}–${course.end}", primary, 12, GlanceModifier.defaultWeight())
                    label("⋯", primary, 14, GlanceModifier.width(16.dp).clickable(config))
                }
                label(course.room.ifBlank { "教室待补" }, secondary, 11, GlanceModifier.height(16.dp))
                if(course.pending) label("周次待补", secondary, 10, GlanceModifier.height(14.dp))
            }
        }
    } else {
        Column(GlanceModifier.clickable(open(context,"timetable",course.id))) {
            label(course.name, primary, 13, GlanceModifier.height(20.dp), bold=true)
            label("${dayCaption(course.date,today)} ${course.start}–${course.end}", secondary, 11, GlanceModifier.height(18.dp))
            label(course.room.ifBlank { "教室待补" } + if(course.pending) " · 待补周次" else "", secondary, 11, GlanceModifier.height(18.dp))
        }
    }
}
@Composable private fun todayCourses(context: Context, state: AppState, today: LocalDate, plan: WidgetLayoutPlan, primary: Color, secondary: Color, accent: Color, config: Action) {
    val now = LocalTime.now(CoreFunctions.zone)
    val all = CoreFunctions.occurrences(state,today)
    val courses = all.filter { LocalTime.parse(it.end) > now }
    if(plan.strip) {
        val first = courses.firstOrNull()
        stripHeading(first?.name ?: "今日课已结束", primary, config)
        label(first?.let { "${it.start}–${it.end} · ${it.room}" } ?: if(all.isEmpty()) "今天没有课程" else "共 ${all.size} 节课", secondary, 11, GlanceModifier.height(16.dp).clickable(open(context,"timetable",first?.id ?: "")))
        overflow(context, if(courses.size > 1) "+${courses.size-1} 节 · 查看今日" else "查看今日课表", "timetable", accent)
        return
    }
    if(courses.isEmpty()) label(if(all.isEmpty()) "今天没有课程" else "今日课程已结束",secondary,12,GlanceModifier.height(22.dp))
    courses.take(plan.courseCapacity).forEach { courseRow(context,it,primary,secondary) }
    overflow(context, if(courses.size > plan.courseCapacity) "+${courses.size-plan.courseCapacity} 节 · 查看今日" else "查看今日课表", "timetable", accent)
}
@Composable private fun weekCourses(context: Context, state: AppState, today: LocalDate, plan: WidgetLayoutPlan, primary: Color, secondary: Color, accent: Color, config: Action) {
    val monday = today.minusDays(today.dayOfWeek.value-1L)
    val days = (0L..6L).map { offset -> monday.plusDays(offset) to CoreFunctions.occurrences(state,monday.plusDays(offset)) }
    if(plan.strip) {
        stripHeading("本周 ${days.sumOf{it.second.size}} 节课",primary,config)
        val todayCourses=days.first{it.first==today}.second
        label("今日 ${todayCourses.size} 节 · ${todayCourses.firstOrNull()?.name ?: "无课"}",secondary,11,GlanceModifier.height(16.dp))
        overflow(context,"查看本周课表","timetable",accent)
        return
    }
    val display=if(plan.weekCapacity>=7)days else days.filter{it.first>=today && it.second.isNotEmpty()}.ifEmpty{days.filter{it.second.isNotEmpty()}}
    display.take(plan.weekCapacity).forEach { (date,courses) ->
        Column(GlanceModifier.fillMaxWidth().height(plan.weekRowHeight.dp).clickable(open(context,"timetable"))) {
            label("周${"一二三四五六日"[date.dayOfWeek.value-1]}  ${courses.joinToString(" · "){"${it.startPeriod}节 ${it.name}"}.ifBlank{"无课"}}",if(date==today)primary else secondary,12,GlanceModifier.height(18.dp),bold=date==today)
            if(plan.weekRowHeight>18)label(courses.joinToString(" · "){"${it.start} ${it.room}"}.ifBlank{"自由安排"},secondary,10,GlanceModifier.height(16.dp))
        }
    }
    overflow(context,if(display.size>plan.weekCapacity) "+${display.size-plan.weekCapacity} 天 · 查看整周" else "查看整周课表","timetable",accent)
}
private fun taskItems(state: AppState,today: LocalDate,filter:String):List<TaskOccurrence> {
    val todayItems=CoreFunctions.tasksForDate(state,today).filter{!it.completed}
    val items=if(filter=="all") state.tasks.filter{!it.deleted && !it.completed && it.repeat=="none"}.mapNotNull{CoreFunctions.taskOccurrence(state,it)} + todayItems.filter{it.task.repeat!="none"}
        else todayItems.filter{filter=="today" || filter in it.task.tags}
    return items.sortedWith(compareBy<TaskOccurrence>{it.date ?: "9999"}.thenBy{it.time ?: "99:99"}.thenByDescending{it.task.priority})
}
@Composable private fun taskRow(context:Context,item:TaskOccurrence,today:LocalDate,primary:Color,secondary:Color) {
    Row(GlanceModifier.fillMaxWidth().height(28.dp),verticalAlignment=Alignment.CenterVertically) {
        label("☐",primary,17,GlanceModifier.width(26.dp).height(28.dp).clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(
            ActionParameters.Key<String>("taskId") to item.task.id,ActionParameters.Key<Long>("version") to item.task.version,
            ActionParameters.Key<String>("date") to (item.date ?: today.toString())))))
        label(item.task.title,primary,12,GlanceModifier.defaultWeight().clickable(open(context,"tasks",item.task.id)))
        val date=item.date
        val due=when { date!=null && date!=today.toString()->dayCaption(date,today);else->item.time ?: "" }
        if(due.isNotEmpty())label(due,secondary,10,GlanceModifier.padding(start=4.dp).clickable(open(context,"tasks",item.task.id)))
    }
}
@Composable private fun taskList(context:Context,items:List<TaskOccurrence>,today:LocalDate,plan:WidgetLayoutPlan,primary:Color,secondary:Color,accent:Color,config:Action) {
    if(plan.strip) {
        stripHeading("待办 ${items.size} 项",primary,config)
        items.firstOrNull()?.let{taskRow(context,it,today,primary,secondary)} ?: label("今天的待办已清空",secondary,12,GlanceModifier.height(28.dp))
        return
    }
    if(items.isEmpty())label("待办已清空",secondary,12,GlanceModifier.height(22.dp))
    items.take(plan.taskCapacity).forEach{taskRow(context,it,today,primary,secondary)}
    overflow(context,if(items.size>plan.taskCapacity) "+${items.size-plan.taskCapacity} 项 · 查看全部" else "查看全部待办","tasks",accent)
}
@Composable private fun spending(context:Context,state:AppState,today:LocalDate,accountId:String,masked:Boolean,plan:WidgetLayoutPlan,primary:Color,secondary:Color,config:Action) {
    fun value(cents:Long?)=if(masked)"••••" else cents?.let(::money) ?: "未初始化"
    val account=state.accounts.firstOrNull{it.id==accountId}
    if(plan.strip) {
        Row(GlanceModifier.fillMaxWidth().height(plan.bodyHeight.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(open(context,"ledger"))) {
                label("今日支出",secondary,11,GlanceModifier.height(16.dp))
                label(value(CoreFunctions.expenseTotal(state,today)),primary,20,GlanceModifier.height(28.dp),bold=true)
            }
            Column(GlanceModifier.defaultWeight()) {
                stripHeading(account?.name ?: "账户",secondary,config)
                label(value(CoreFunctions.balance(state,accountId)),primary,13,GlanceModifier.height(20.dp).clickable(open(context,"ledger")))
            }
        }
    } else {
        label(value(CoreFunctions.expenseTotal(state,today)),primary,22,GlanceModifier.height(30.dp).clickable(open(context,"ledger")),bold=true)
        label("${account?.name ?: "账户"} ${value(CoreFunctions.balance(state,accountId))}",secondary,11,GlanceModifier.height(18.dp).clickable(open(context,"ledger")))
        if(plan.bodyHeight>=64)label("＋ 快速记账",secondary,11,GlanceModifier.height(16.dp).clickable(open(context,"chat","add")))
    }
}
@Composable private fun overview(context:Context,state:AppState,today:LocalDate,masked:Boolean,plan:WidgetLayoutPlan,primary:Color,secondary:Color,accent:Color,config:Action) {
    val items=taskItems(state,today,"today")
    val next=CoreFunctions.nextCourse(state,Instant.now())
    val spend=if(masked)"••••" else money(CoreFunctions.expenseTotal(state,today))
    if(plan.overviewCourseCapacity==0) {
        if(plan.strip) {
            stripHeading(next?.name ?: "拾日 · 近期无课",primary,config)
            label(next?.let{"${it.start}–${it.end} · ${it.room}"} ?: "今天 ${items.size} 项待办",secondary,11,GlanceModifier.height(16.dp).clickable(open(context,"timetable",next?.id ?: "")))
            label("待办 ${items.size} · 支出 $spend",secondary,11,GlanceModifier.height(18.dp).clickable(open(context,"today")))
            return
        }
        next?.let { courseRow(context,it,primary,secondary) } ?: label("近期没有课程",secondary,12,GlanceModifier.height(34.dp))
        label("待办 ${items.size} · 支出 $spend",secondary,11,GlanceModifier.height(18.dp).clickable(open(context,"today")))
        return
    }
    val courses=CoreFunctions.occurrences(state,today).filter{LocalTime.parse(it.end)>LocalTime.now(CoreFunctions.zone)}
        .ifEmpty { listOfNotNull(next) }
    courses.take(plan.overviewCourseCapacity).forEach{courseRow(context,it,primary,secondary)}
    if(courses.isEmpty())label("今天没有课程",secondary,12,GlanceModifier.height(34.dp))
    label("待办 ${items.size} 项 · 今日支出 $spend",secondary,11,GlanceModifier.height(20.dp).clickable(open(context,"today")))
    items.take(plan.overviewTaskCapacity).forEach{taskRow(context,it,today,primary,secondary)}
    overflow(context,if(items.size>plan.overviewTaskCapacity) "+${items.size-plan.overviewTaskCapacity} 项 · 快速输入" else "＋ 快速输入","chat",accent,"add")
}
@Composable private fun overflow(context:Context,text:String,page:String,color:Color,action:String="") {
    label(text,color,10,GlanceModifier.fillMaxWidth().height(16.dp).clickable(open(context,page,action)))
}
private fun semesterName(state:AppState)=state.semesters.firstOrNull{it.id==state.settings.activeSemesterId}?.name ?: "当前学期"
private fun dayCaption(date:String,today:LocalDate):String = when(date) {
    today.toString()->"今天";today.plusDays(1).toString()->"明天"
    else->runCatching{LocalDate.parse(date).let{"${it.monthValue}/${it.dayOfMonth}"}}.getOrDefault(date)
}
private fun money(cents:Long):String = "¥${java.math.BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString()}"
private fun open(context:Context,page:String,action:String=""):Action = actionStartActivity(Intent().setClassName(context,"com.hubra.app.MainActivity").putExtra("page",page).putExtra("action",action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ActionParameters.Key<String>("taskId")] ?: return
        val result = context.hubra.repository.execute(Operation(UUID.randomUUID().toString(), "task.complete", id, parameters[ActionParameters.Key<Long>("version")],
            buildJsonObject { put("date", parameters[ActionParameters.Key<String>("date")] ?: LocalDate.now(CoreFunctions.zone).toString()) }))
        if (result.status != "success") {
            context.hubra.preferences.set("widget.lastError", result.message)
            android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_LONG).show() }
        }
        Widgets.refresh(context)
    }
}
class NextCourseWidget : HubraWidget(WidgetKind.NEXT)
class TodayCoursesWidget : HubraWidget(WidgetKind.TODAY)
class WeekCoursesWidget : HubraWidget(WidgetKind.WEEK)
class TasksWidget : HubraWidget(WidgetKind.TASKS)
class SpendingWidget : HubraWidget(WidgetKind.SPENDING)
class OverviewWidget : HubraWidget(WidgetKind.OVERVIEW)
class NextCourseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = NextCourseWidget() }
class TodayCoursesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = TodayCoursesWidget() }
class WeekCoursesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = WeekCoursesWidget() }
class TasksWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = TasksWidget() }
class SpendingWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = SpendingWidget() }
class OverviewWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = OverviewWidget() }
