package com.hubra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hubra.app.media.AttachmentManager
import com.hubra.core.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable fun TaskLine(o:TaskOccurrence,execute:Execute,run:Run,onEdit:()->Unit,courseLabel:String?=null) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        Checkbox(o.completed,{checked->run{execute(operation(if(checked)"task.complete" else "task.restore",o.task.id,o.task.version,args("date" to (o.date?:today().toString()))))}})
        Column(Modifier.weight(1f).clickable(onClick=onEdit).padding(vertical=8.dp)){
            Text(o.task.title,style=MaterialTheme.typography.titleSmall,color=if(o.completed)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(listOfNotNull(o.time,if(o.task.repeat!="none")repeatOptions.find{it.first==o.task.repeat}?.second else null,if(o.task.reminder)"提醒已开启" else if(o.time!=null)"未开启提醒"else null,if(o.task.priority>0)"重要" else null).joinToString(" · "),style=MaterialTheme.typography.bodySmall)
            courseLabel?.let{Text(it,style=MaterialTheme.typography.labelSmall)}
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TasksScreen(s:AppState,execute:Execute,edit:Edit,run:Run) {
    var filter by rememberSaveable{mutableStateOf("今天")}
    var dateText by rememberSaveable{mutableStateOf(today().toString())}
    var selectedId by rememberSaveable{mutableStateOf<String?>(null)}
    var selectedDate by rememberSaveable{mutableStateOf<String?>(null)}
    val date=LocalDate.parse(dateText)
    val occurrences=when(filter){
        "今天"->CoreFunctions.tasksForDate(s,date)
        "未来"->(1L..30L).flatMap{CoreFunctions.tasksForDate(s,today().plusDays(it)).filter{o->!o.completed && o.date!=null}}
        "已完成"->s.tasks.filter{!it.deleted && it.completed && it.repeat=="none"}.map{CoreFunctions.taskOccurrence(s,it)?:TaskOccurrence(it,it.date,it.time,true)}+s.taskCompletions.mapNotNull{c->s.tasks.find{!it.deleted && it.id==c.taskId}?.let{TaskOccurrence(it,c.date,it.time,true)}}
        "已过期"->s.tasks.filter{!it.deleted && isOverdueTask(it.date,it.completed,today().toString())}.map{displayTask(s,it)}
        else->s.tasks.filter{!it.deleted}.map{displayTask(s,it)}
    }
    Page {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("待办",style=MaterialTheme.typography.headlineMedium);FilledTonalButton(onClick={edit(taskEditor(s,execute))}){Text("＋ 新增")}}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("今天","未来","已过期","全部","已完成").forEach{label->FilterChip(filter==label,{filter=label},label={Text(label)})}}
        if(filter=="今天")ActionRow("前一天" to {dateText=date.minusDays(1).toString()},date.toString() to {dateText=today().toString()},"后一天" to {dateText=date.plusDays(1).toString()})
        if(occurrences.isEmpty())Empty("这里暂时没有待办")
        occurrences.groupBy{it.date?:"无截止日期"}.toSortedMap().forEach{(day,items)->Section(if(day==today().toString())"今天 · $day" else day){items.sortedWith(compareBy<TaskOccurrence>{it.completed}.thenBy{it.time?:"23:59"}.thenByDescending{it.task.priority}).forEach{o->TaskLine(o,execute,run,{selectedId=o.task.id;selectedDate=o.date},taskCourseLabel(s,o.task));val children=s.tasks.count{!it.deleted && it.parentId==o.task.id};if(children>0)Text("${s.tasks.count{!it.deleted && it.parentId==o.task.id && it.completed}}/$children 子任务完成",style=MaterialTheme.typography.labelSmall);HorizontalDivider()}}}
    }
    selectedId?.let{id->s.tasks.find{it.id==id && !it.deleted}?.let{task->TaskDetails(s,task,selectedDate,execute,edit,run){selectedId=null}}}
}
private fun displayTask(s:AppState,t:Task):TaskOccurrence {
    val base=CoreFunctions.taskOccurrence(s,t)?:return TaskOccurrence(t,t.date,null,t.completed)
    if(t.repeat=="none" || base.date==null)return base
    val due=LocalDate.parse(base.date);val start=maxOf(due,today())
    for(offset in 0L..7L)CoreFunctions.tasksForDate(s,start.plusDays(offset)).find{it.task.id==t.id}?.let{return it}
    return base
}
fun taskCourseLabel(s:AppState,t:Task):String? {
    val rule=s.courseRules.find{it.id==t.courseRuleId}?:return null
    val course=s.courses.find{it.id==rule.courseId}?:return "关联原课程"
    val semester=s.semesters.find{it.id==course.semesterId}
    return "关联 ${course.name}${if(course.semesterId!=s.settings.activeSemesterId)" · ${semester?.name?:"历史学期"}"else ""}"
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskDetails(s:AppState,t:Task,date:String?,execute:Execute,edit:Edit,run:Run,onClose:()->Unit) {
    val occurrence=date?.let{CoreFunctions.tasksForDate(s,LocalDate.parse(it)).find{o->o.task.id==t.id}}?:CoreFunctions.taskOccurrence(s,t)
    ModalBottomSheet(onDismissRequest=onClose){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(t.title,style=MaterialTheme.typography.headlineSmall);Text(listOfNotNull(occurrence?.date,occurrence?.time,repeatOptions.find{it.first==t.repeat}?.second).joinToString(" · "))
        if(t.note.isNotBlank())Text(t.note)
        if(t.tags.isNotEmpty())Text(t.tags.joinToString(" · "),style=MaterialTheme.typography.labelMedium)
        taskCourseLabel(s,t)?.let{Text(it)}
        ActionRow("编辑" to {onClose();edit(taskEditor(s,execute,t))},(if(occurrence?.completed==true)"恢复" else "完成") to {run{execute(operation(if(occurrence?.completed==true)"task.restore" else "task.complete",t.id,t.version,args("date" to (date?:occurrence?.date?:today().toString()))))}},"延期" to {onClose();edit(taskDeferEditor(t,execute))},"子任务" to {onClose();edit(taskEditor(s,execute,parent=t.id))})
        val children=s.tasks.filter{!it.deleted && it.parentId==t.id}
        if(children.isNotEmpty())Text("子任务",style=MaterialTheme.typography.titleMedium)
        children.forEach{child->TaskLine(CoreFunctions.taskOccurrence(s,child)?:TaskOccurrence(child,child.date,child.time,child.completed),execute,run,{onClose();edit(taskEditor(s,execute,child))})}
        Text("操作历史",style=MaterialTheme.typography.titleMedium)
        s.operations.asReversed().filter{it.result.affectedIds.contains(t.id)||it.changes.any{change->change.id==t.id}}.take(10).forEach{OperationResultCard(s,it,execute,run)}
        Spacer(Modifier.height(28.dp))
    }}
}

@Composable fun TimetableScreen(s:AppState,execute:Execute,edit:Edit,run:Run,pickImage:()->Unit,backup:(String)->Unit,pickBackground:()->Unit={},openSettings:()->Unit={}) {
    key(s.settings.activeSemesterId){TimetablePager(s,execute,edit,run,pickImage,backup,pickBackground,openSettings)}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TimetablePager(s:AppState,execute:Execute,edit:Edit,run:Run,pickImage:()->Unit,backup:(String)->Unit,pickBackground:()->Unit,openSettings:()->Unit) {
    val initial=CoreFunctions.weekNumber(s.settings,today())?.coerceIn(1,s.settings.semesterWeeks)?:1
    val pager=rememberPagerState(initialPage=initial-1,pageCount={s.settings.semesterWeeks})
    val scope=rememberCoroutineScope()
    var more by remember{mutableStateOf(false)}
    var weeks by remember{mutableStateOf(false)}
    var management by rememberSaveable{mutableStateOf("")}
    BackHandler(management.isNotBlank()){management=""}
    var selectedIds by remember{mutableStateOf<List<String>>(emptyList())}
    var selectedDate by remember{mutableStateOf<LocalDate?>(null)}
    var appearanceOpen by remember{mutableStateOf(false)}
    val appearance=s.settings.timetableAppearances[s.settings.activeSemesterId]?:TimetableAppearance()
    val days=if(appearance.showWeekends)7 else 5
    val monday=LocalDate.parse(s.settings.semesterStart).plusWeeks(pager.currentPage.toLong())
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f).clickable{more=true}){Text(activeSemesterName(s),style=MaterialTheme.typography.titleMedium);Text("第 ${pager.currentPage+1} 周 · ${monday.monthValue}/${monday.dayOfMonth}",style=MaterialTheme.typography.bodySmall)}
            IconButton(onClick={edit(courseEditor(execute,semesterId=s.settings.activeSemesterId))}){Icon(Icons.Default.Add,"新增课程")}
            IconButton(onClick={more=true}){Icon(Icons.Default.MoreVert,"课表菜单")}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TextButton(onClick={scope.launch{pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0))}}){Text("‹")};TextButton(onClick={weeks=true}){Text("第 ${pager.currentPage+1} 周 ▾")};TextButton(onClick={scope.launch{pager.animateScrollToPage(initial-1)}}){Text("回到本周")};TextButton(onClick={scope.launch{pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(s.settings.semesterWeeks-1))}}){Text("›")}}
        if(management.isBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=6.dp)){Spacer(Modifier.width(if(appearance.showTimes)44.dp else 26.dp));repeat(days){day->val d=monday.plusDays(day.toLong());Column(Modifier.weight(1f).background(if(d==today())MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("一二三四五六日"[day].toString(),style=MaterialTheme.typography.labelMedium);Text("${d.monthValue}/${d.dayOfMonth}",style=MaterialTheme.typography.labelSmall)}}}
            HorizontalPager(pager,Modifier.weight(1f),key={"${s.settings.activeSemesterId}:$it"}){page->TimetableBody(s,LocalDate.parse(s.settings.semesterStart).plusWeeks(page.toLong()),appearance){date,ids->selectedDate=date;selectedIds=ids}}
        } else {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(management,Modifier.padding(12.dp),style=MaterialTheme.typography.titleLarge);TextButton(onClick={management=""}){Text("返回课表")}}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(management=="课程管理"){
                    ActionRow("新增课程" to {edit(courseEditor(execute,semesterId=s.settings.activeSemesterId))},"JSON 导入" to {backup("courses-in")},"JSON 导出" to {backup("courses-out")})
                    if(activeCourses(s).isEmpty())Empty("当前学期没有课程，可以导入截图或手动新增。")
                    activeCourses(s).forEach{c->Section(c.name,"${c.teacher}${if(c.pending)" · 周次待补全" else ""}"){
                        ActionRow("课程信息" to {edit(courseEditor(execute,c))},"添加规则" to {edit(ruleEditor(s,execute,courseId=c.id))},"导出" to {backup("course-out:${c.id}")})
                        s.courseRules.filter{!it.deleted && it.courseId==c.id}.forEach{r->TextButton(onClick={edit(ruleEditor(s,execute,r))}){Text("周${r.dayOfWeek} ${r.startPeriod}–${r.endPeriod}节 · ${r.room}\n第 ${r.weeks.joinToString(",")} 周")}}
                    }}
                } else {
                    TextButton(onClick={edit(exceptionEditor(s,execute))},enabled=activeRules(s).isNotEmpty()){Text("新增单次调整")}
                    activeExceptions(s).forEach{e->val r=s.courseRules.find{it.id==e.ruleId};val c=s.courses.find{it.id==r?.courseId};TextButton(onClick={edit(exceptionEditor(s,execute,e=e))}){Text("${c?.name} · ${e.date}\n${if(e.cancelled)"停课" else "${e.movedDate?:"原日期"} ${e.room?:"原教室"}"}")}}
                    if(activeExceptions(s).isEmpty())Empty("尚无单次调整")
                }
            }
        }
    }
    if(more)ModalBottomSheet(onDismissRequest={more=false}){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Choice("学期",s.settings.activeSemesterId,semesterOptions(s),{id->more=false;if(id!=s.settings.activeSemesterId)run{execute(operation("semester.switch",values=args("semesterId" to id)))}})
        ActionRow("新建学期" to {more=false;edit(newSemesterEditor(s,execute))},"截图导入" to {more=false;pickImage()})
        ActionRow("课程管理" to {management="课程管理";more=false},"单次调整" to {management="单次调整";more=false})
        ActionRow("显示设置" to {appearanceOpen=true;more=false},"学期与作息" to {more=false;edit(semesterEditor(s,execute))},"重命名学期" to {more=false;edit(semesterRenameEditor(s,execute))})
        TextButton(onClick={more=false;openSettings()}){Text("课表设置与提醒")};Spacer(Modifier.height(24.dp))
    }}
    if(weeks)ModalBottomSheet(onDismissRequest={weeks=false}){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())){Text("选择周次",style=MaterialTheme.typography.titleLarge);repeat(s.settings.semesterWeeks){i->TextButton(onClick={weeks=false;scope.launch{pager.animateScrollToPage(i)}},modifier=Modifier.fillMaxWidth()){Text("第 ${i+1} 周 · ${LocalDate.parse(s.settings.semesterStart).plusWeeks(i.toLong())}")}}}}
    if(appearanceOpen)TimetableAppearanceSheet(s,execute,run,pickBackground){appearanceOpen=false}
    if(selectedIds.isNotEmpty())ModalBottomSheet(onDismissRequest={selectedIds=emptyList()}){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        val selected=selectedDate?.let{CoreFunctions.occurrences(s,it).filter{o->o.id in selectedIds}}.orEmpty()
        if(selected.size>1)Text("这些课程时间重叠，请核对安排",color=MaterialTheme.colorScheme.error)
        selected.forEach{o->Section(o.name){Text("${o.date} · ${o.start}–${o.end}\n${o.startPeriod}–${o.endPeriod} 节 · ${o.room}\n${o.teacher}");if(o.pending)Text("完整周次待补全");ActionRow("调整这次课" to {selectedIds=emptyList();val ex=s.courseExceptions.find{!it.deleted && it.ruleId==o.ruleId && it.date==o.id.substringAfter(':')};edit(exceptionEditor(s,execute,o,ex))},"编辑规则" to {selectedIds=emptyList();s.courseRules.find{it.id==o.ruleId}?.let{edit(ruleEditor(s,execute,it))}})}}
        Spacer(Modifier.height(24.dp))
    }}
}
@Composable private fun TimetableBody(s:AppState,monday:LocalDate,a:TimetableAppearance,select:(LocalDate,List<String>)->Unit) {
    val context=LocalContext.current
    val rowHeight=a.rowHeightDp.toFloat().dp
    val days=if(a.showWeekends)7 else 5
    val background=runCatching{Color(android.graphics.Color.parseColor(a.backgroundColor))}.getOrDefault(MaterialTheme.colorScheme.surface)
    val gridForeground=if(background.luminance()>.4f)Color(0xFF172035)else Color(0xFFF9FAFF)
    val attachment=a.backgroundAttachmentId?.let{id->s.attachments.find{it.id==id && it.deletedAt==null && !it.deletionPending}}
    Box(Modifier.fillMaxSize().background(background)) {
        if(attachment!=null)AsyncImage(model=AttachmentManager(context).file(attachment),contentDescription="课表背景",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop,alpha=.35f)
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=6.dp)) {
            Column(Modifier.width(if(a.showTimes)44.dp else 26.dp)){s.settings.periods.forEach{p->Column(Modifier.height(rowHeight).padding(vertical=4.dp)){Text(p.number.toString(),fontSize=12.sp,color=gridForeground);if(a.showTimes)Text("${p.start}\n${p.end}",fontSize=9.sp,color=gridForeground)}}}
            repeat(days){day->val date=monday.plusDays(day.toLong());val items=CoreFunctions.occurrences(s,date)
                Box(Modifier.weight(1f).height(rowHeight*s.settings.periods.size)) {
                    Column{repeat(s.settings.periods.size){Box(Modifier.fillMaxWidth().height(rowHeight)){HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))}}}
                    conflictGroups(items).forEach{group->val o=group.first();val start=group.minOf{it.startPeriod};val end=group.maxOf{it.endPeriod};val index=s.settings.periods.indexOfFirst{it.number==start};val endIndex=s.settings.periods.indexOfFirst{it.number==end};if(index>=0 && endIndex>=index){
                        val tint=runCatching{Color(android.graphics.Color.parseColor(o.color))}.getOrDefault(MaterialTheme.colorScheme.primaryContainer)
                        val textColor=if(tint.copy(alpha=a.opacity).compositeOver(background).luminance()>.4f)Color(0xFF172035)else Color(0xFFF9FAFF)
                        Column(Modifier.offset(y=rowHeight*index).fillMaxWidth().height(rowHeight*(endIndex-index+1)).padding(1.dp).background(tint.copy(alpha=a.opacity.toFloat()),RoundedCornerShape(a.cornerRadiusDp.toFloat().dp)).clickable{select(date,group.map{it.id})}.padding(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            if(group.size>1)Text("重叠 ${group.size}",fontSize=9.sp,color=MaterialTheme.colorScheme.error)
                            Text(courseShortName(o.name),fontSize=a.fontSizeSp.toFloat().sp,lineHeight=(a.fontSizeSp+2).sp,color=textColor,maxLines=4)
                            Text(o.room.replace("南雍-西","西").replace("苏教",""),fontSize=(a.fontSizeSp.toFloat()-2).coerceAtLeast(9f).sp,lineHeight=a.fontSizeSp.sp,color=textColor,maxLines=3)
                            if(a.showTeacher)Text(o.teacher,fontSize=9.sp,lineHeight=11.sp,color=textColor,maxLines=2)
                            if(o.pending)Text("待补",fontSize=9.sp,color=textColor)
                        }
                    }}
                }
            }
        }
    }
}
private fun courseShortName(name:String):String=when(name){"中国式现代化的理论与实践"->"中国式现代化";"生成式人工智能高级应用"->"生成式AI";"矩阵论基础"->"矩阵论";"现代控制理论"->"现代控制";"控制系统建模"->"系统建模";else->name.take(8)}

/** A transitive overlap remains one full-width card; its details expose every conflicting course. */
internal fun conflictGroups(items:List<CourseOccurrence>):List<List<CourseOccurrence>> {
    val groups=mutableListOf<MutableList<CourseOccurrence>>()
    items.sortedBy{it.startPeriod}.forEach{course->val last=groups.lastOrNull();if(last!=null && last.maxOf{it.endPeriod}>=course.startPeriod)last.add(course)else groups.add(mutableListOf(course))}
    return groups
}
