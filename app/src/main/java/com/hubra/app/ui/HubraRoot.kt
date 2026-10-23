package com.hubra.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hubra.app.hubra
import com.hubra.app.ai.*
import com.hubra.app.backup.BackupManager
import com.hubra.app.media.*
import com.hubra.app.system.SystemCoordinator
import com.hubra.core.*
import kotlinx.coroutines.launch
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HubraRoot(launchPage:String,launchAction:String,launchKey:Long=0L) {
    val context=LocalContext.current
    val repository=context.hubra.repository
    val backupManager=remember(context){BackupManager(context)}
    val state by repository.state.collectAsState()
    val scope=rememberCoroutineScope()
    val snackbar=remember { SnackbarHostState() }
    val navigationSaver=listSaver<NavigationState,String>(save={listOf(it.selectedTab)+it.stack},restore={NavigationState(it.first(),it.drop(1))})
    var navigation by rememberSaveable(stateSaver=navigationSaver) { mutableStateOf(NavigationState()) }
    val page=navigation.route
    val screenKey=navigation.screenKey
    val pageState=rememberSaveableStateHolder()
    val keyboard=LocalSoftwareKeyboardController.current
    val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    fun navigate(destination:String) { navigation=if(destination in listOf("chat","ledger","timetable","tasks"))navigation.tab(destination)else navigation.push(destination) }
    fun back() { if(keyboardVisible)keyboard?.hide()else navigation.back()?.let { navigation=it } }
    var focusChat by remember { mutableStateOf(false) }
    var consumedLaunchKey by rememberSaveable { mutableLongStateOf(-1L) }
    var ledgerSection by rememberSaveable { mutableStateOf("概览") }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var busy by remember { mutableStateOf(false) }
    var restoreCandidate by remember { mutableStateOf<AppState?>(null) }
    var internalRestore by remember { mutableStateOf(false) }
    var documentPurpose by rememberSaveable { mutableStateOf("") }
    var courseExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var imagePurpose by remember { mutableStateOf("chat") }
    var attached by remember { mutableStateOf<String?>(null) }
    var launchCourse by remember { mutableStateOf<CourseOccurrence?>(null) }
    var operationTarget by remember { mutableStateOf<Pair<String,String>?>(null) }
    val execute:Execute={op-> val result=repository.execute(op);check(result.status=="success"){result.message};result.message }
    val run:Run={job->if(!busy)scope.launch{busy=true;try{val result=job();busy=false;snackbar.showSnackbar(result)}catch(e:CancellationException){throw e}catch(e:Exception){busy=false;snackbar.showSnackbar(e.message?:"操作失败，请重试")}finally{busy=false}}}
    val edit:Edit={editor=it}
    fun saveEditor(values:Map<String,String>) { val selected=editor?:return;scope.launch{busy=true;try{val result=selected.save(values);editor=null;busy=false;snackbar.showSnackbar(result)}catch(e:Exception){busy=false;snackbar.showSnackbar(e.message?:"请检查填写内容")}finally{busy=false}} }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed->run{SystemCoordinator(context).refresh();if(allowed)"通知已开启" else "通知权限未开启，可在系统设置中调整"}}
    val documentOut=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)run{
        val backup=backupManager
        when(documentPurpose){"csv"->backup.exportCsv(uri);"courses"->backup.exportCourses(uri,courseExportId);else->backup.export(uri,backupPassword)}
        backupPassword="";"导出成功"
    }}
    val documentIn=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)run{
        val backup=backupManager
        if(documentPurpose=="courses"){backup.importCourses(uri);"课表导入完成"}else{restoreCandidate=backup.inspect(uri,backupPassword);internalRestore=false;backupPassword="";"备份已校验，请确认恢复内容"}
    }}
    fun backupAction(kind:String) {
        if(kind=="snapshots") {
            val snapshots=backupManager.listSnapshots()
            if(snapshots.isEmpty())run{"尚无本机快照，将在每日自动保存或完整恢复前创建"}
            else edit(Editor("本机快照",listOf(Input("file","选择快照",snapshots.first().name,options=snapshots.map{file->file.name to runCatching{Instant.ofEpochMilli(file.name.substringBefore('.').toLong()).atZone(CoreFunctions.zone).toLocalDateTime().toString()}.getOrDefault(file.name)})),help="可找回误操作前的数据。选定后先校验与预览数量，确认后完整恢复。",save={v->restoreCandidate=backupManager.inspectSnapshot(snapshots.first{it.name==v.text("file")});internalRestore=true;"快照已校验，请确认恢复内容"}))
        }
        else if(kind.startsWith("course-out:")){courseExportId=kind.substringAfter(':');documentPurpose="courses";documentOut.launch("hubra-course-${courseExportId}.json")}
        else if(kind=="csv" || kind=="courses-out") {courseExportId=null;documentPurpose=if(kind=="csv")"csv" else "courses";documentOut.launch(if(kind=="csv")"hubra-ledger.csv" else "hubra-courses.json")}
        else if(kind=="courses-in"){documentPurpose="courses";documentIn.launch(arrayOf("application/json","text/*","application/octet-stream"))}
        else edit(Editor(if(kind=="export")"导出加密备份" else "读取加密备份",listOf(Input("password","备份口令",type="secret",required=true)),help="口令用于加密完整备份；API 密钥不会导出。请保存口令。",save={v->require(v.text("password").length>=8){"口令至少 8 个字符"};backupPassword=v.text("password");documentPurpose="backup";if(kind=="export")documentOut.launch("hubra-${today()}.hubra") else documentIn.launch(arrayOf("application/octet-stream","*/*"));"请选择备份文件位置"}))
    }
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)run{
        val attachment=AttachmentManager(context).importImage(uri,when {
            imagePurpose == "meal-manual" || imagePurpose == "meal" || imagePurpose.startsWith("meal-edit:") -> "meal"
            imagePurpose == "course" -> "course"
            imagePurpose == "background" -> "background"
            else -> "other"
        })
        when {
            imagePurpose == "meal-manual"->{edit(mealEditor(repository.snapshot(),execute,attachmentId=attachment.id));"照片已保存，请填写餐食"}
            imagePurpose.startsWith("meal-edit:")->{val mealId=imagePurpose.substringAfter(':');val meal=repository.snapshot().meals.find{it.id==mealId && !it.deleted}?:error("餐食不存在");execute(operation("meal.update",meal.id,meal.version,args("attachmentIds" to JsonArray(mergeMealAttachmentIds(meal.attachmentIds,listOf(attachment.id)).map(::JsonPrimitive)))));"照片已添加"}
            imagePurpose == "course"->{navigate("chat");ChatService(context).send("请识别这张课表截图，按明确的课程、周次、节次和教室导入。信息缺失或冲突请保留待补全，不推测。",attachment.id,"course")}
            imagePurpose == "meal"->{navigate("chat");ChatService(context).send("请记录照片中的餐食，说明不确定之处。不要推断价格或付款账户。",attachment.id,"meal")}
            imagePurpose == "background"->{val current=repository.snapshot();val appearance=(current.settings.timetableAppearances[current.settings.activeSemesterId]?:TimetableAppearance()).copy(backgroundAttachmentId=attachment.id);execute(operation("settings.update",values=args("timetableAppearances" to HubraJson.json.encodeToJsonElement(current.settings.timetableAppearances+(current.settings.activeSemesterId to appearance)))));"已设置当前学期背景"}
            else->{attached=attachment.id;focusChat=true;navigate("chat");"图片已附到对话"}
        }
    }}
    val multipleImages=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())run{
        require(uris.size<=12){"一次最多导入 12 张课表截图，请分批选择"}
        navigate("chat")
        val results=uris.mapIndexed{index,uri->
            try{val attachment=AttachmentManager(context).importImage(uri,"course");ChatService(context).send("请识别第 ${index+1} 张课表截图，合并明确的课程、周次、节次和教室。去重已有课程；缺失或冲突请保留待补全，不推测。",attachment.id,"course")}
            catch(e:Exception){"第 ${index+1} 张：${e.message?:"导入失败"}"}
        }
        results.joinToString("\n").take(600)
    }}
    fun pickImage(mode:String){imagePurpose=mode;if(mode=="course")multipleImages.launch(arrayOf("image/*"))else imagePicker.launch(arrayOf("image/*"))}
    LaunchedEffect(launchPage,launchAction,launchKey){
        if(consumedLaunchKey==launchKey)return@LaunchedEffect
        consumedLaunchKey=launchKey
        val loaded=repository.snapshot()
        navigation=NavigationState()
        val targetPage=launchPage.takeIf{it in listOf("today","ledger","chat","timetable","tasks","settings")}?:"chat"
        navigate(targetPage)
        focusChat=launchAction in listOf("add","focus","add_transaction") && launchPage=="chat"
        when(launchAction){
            "reconcile"->{navigate("ledger");ledgerSection="核账"}
            "add","add_transaction"->{if(targetPage=="tasks")editor=taskEditor(loaded,execute)else if(targetPage=="ledger")editor=transactionEditor(loaded,execute)}
            else->{if(targetPage=="tasks")loaded.tasks.find{it.id==launchAction && !it.deleted}?.let{operationTarget="tasks" to it.id}
                if(targetPage=="timetable" && launchAction.contains(':')) {
                    val date=runCatching{java.time.LocalDate.parse(launchAction.substringAfter(':'))}.getOrNull()
                    if(date!=null){val exception=loaded.courseExceptions.find{!it.deleted && "${it.ruleId}:${it.date}"==launchAction};val actual=exception?.movedDate?.let(java.time.LocalDate::parse)?:date;launchCourse=CoreFunctions.occurrences(loaded,actual).find{it.id==launchAction}}
                }
            }
        }
    }
    ShiriTheme(state.settings.themeMode) {
        BackHandler(enabled=editor==null && restoreCandidate==null && launchCourse==null && operationTarget==null && (keyboardVisible || navigation.back()!=null)) { back() }
        val rootTitles=mapOf("chat" to "拾日","ledger" to "账本","tasks" to "待办","today" to "今日总览","reports" to "日报历史","settings" to "设置","media" to "图片与存储")
        val settingsTitles=mapOf("ai" to "AI 与记账规则","appearance" to "外观","timetable" to "学期与课表","reminders" to "通知与提醒","health" to "健康资料","widgets" to "桌面组件","backup" to "备份与导出","about" to "关于拾日")
        Scaffold(topBar={if(page!="timetable")TopAppBar(title={Text(if(page.startsWith("settings:"))settingsTitles[page.substringAfter(':')]?:"设置"else rootTitles[page]?:"拾日",style=MaterialTheme.typography.titleLarge)},navigationIcon={if(navigation.stack.isNotEmpty())IconButton(onClick=::back){Icon(Icons.AutoMirrored.Filled.ArrowBack,contentDescription="返回")}},actions={if(busy)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp);if(!page.startsWith("settings"))IconButton(onClick={navigate("settings")}){Icon(Icons.Default.Settings,contentDescription="设置")}})},snackbarHost={SnackbarHost(snackbar)},bottomBar={
            if(navigation.stack.isEmpty())NavigationBar{listOf("chat" to "对话","ledger" to "账本","timetable" to "课表","tasks" to "待办").forEachIndexed{index,(id,label)->NavigationBarItem(modifier=Modifier.testTag("nav.$id"),selected=page==id,onClick={keyboard?.hide();navigate(id)},icon={Icon(when(index){0->Icons.AutoMirrored.Filled.Chat;1->Icons.Default.AccountBalanceWallet;2->Icons.Default.CalendarMonth;else->Icons.Default.Checklist},contentDescription=label)},label={Text(label)})}}
        }){padding->Box(Modifier.padding(padding)) { androidx.compose.runtime.key(screenKey.value) { pageState.SaveableStateProvider(screenKey.value) {
            when(screenKey.root){
                "today"->TodayScreen(state,execute,edit,run,{if(it=="reconcile"){navigate("ledger");ledgerSection="核账"}else navigate(it)})
                "ledger"->LedgerScreen(state,execute,edit,run,{pickImage(it)},{navigate("chat")},ledgerSection,openMedia={navigate("media")},addMealImage={id->pickImage("meal-edit:$id")},removeMealImage={mealId,attachmentId->run{val meal=repository.snapshot().meals.find{it.id==mealId && !it.deleted}?:error("餐食不存在");execute(operation("meal.update",meal.id,meal.version,args("attachmentIds" to JsonArray(mergeMealAttachmentIds(meal.attachmentIds,removed=listOf(attachmentId)).map(::JsonPrimitive)))));"照片已移除"}})
                "chat"->ChatScreen(state,attached,busy,{attached=null},{pickImage("chat")},{text->val attachment=attached;attached=null;focusChat=false;run{ChatService(context).send(text,attachment)}},execute,run,edit,{message->run{ChatService(context).send(message.text,message.attachmentIds.firstOrNull(),message.mode,message.id)}},focusInput=focusChat,onOpenOverview={navigate("today")},openMedia={navigate("media")},onOpenOperation={collection,id->operationTarget=collection to id})
                "timetable"->TimetableScreen(state,execute,edit,run,{pickImage("course")},{backupAction(it)},pickBackground={pickImage("background")},openSettings={navigate("settings:timetable")})
                "tasks"->TasksScreen(state,execute,edit,run)
                "media"->MediaScreen(state,run)
                "reports"->ReportScreen(state,run,{navigate("today")})
                else->if(screenKey.root=="settings")SettingsScreen(state,execute,edit,run,{backupAction(it)},requestNotifications={if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else run{"当前系统无需运行时通知授权"}},requestExact={if(Build.VERSION.SDK_INT>=31)context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}")))},requestBattery={context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))},subsection=page.substringAfter(':',"index"),onNavigate={section->when(section){"index"->navigation.back()?.let{navigation=it};"media"->navigate("media");else->navigate("settings:$section")}},pickBackground={pickImage("background")})
            }
        }}}}
        operationTarget?.let { (collection,id) ->
            val task=state.tasks.find { it.id==id && !it.deleted && collection=="tasks" }
            val transaction=state.transactions.find { it.id==id && !it.deleted && collection=="transactions" }
            when {
                task!=null->TaskDetails(state,task,task.date,execute,edit,run){operationTarget=null}
                transaction!=null->TransactionDetails(state,transaction,execute,edit,run){operationTarget=null}
                else->AlertDialog(onDismissRequest={operationTarget=null},title={Text("记录详情与修改")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    val history=state.operations.filter { o->o.changes.any { it.collection==collection && it.id==id } }.asReversed().take(12)
                    if(history.isEmpty())Text("暂时没有可显示的修改记录")
                    history.forEach { o->OperationResultCard(state,o,execute,run) }
                }},confirmButton={targetEditor(state,id,execute)?.let { target->TextButton(onClick={operationTarget=null;edit(target)}){Text("编辑记录")} }},dismissButton={TextButton(onClick={operationTarget=null}){Text("关闭")}})
            }
        }
        editor?.let{selected->EditorDialog(selected,busy,{editor=null},::saveEditor,{selected.remove?.let{remove->scope.launch{busy=true;try{val result=remove();editor=null;snackbar.showSnackbar(result)}catch(e:Exception){snackbar.showSnackbar(e.message?:"删除失败")}finally{busy=false}}}})}
        restoreCandidate?.let{candidate->AlertDialog(onDismissRequest={restoreCandidate=null},title={Text("确认完整恢复")},text={Text("将替换当前数据，恢复前会保留当前快照。\n学期 ${candidate.semesters.size} 个\n账户 ${candidate.accounts.size} 个\n流水 ${candidate.transactions.count{!it.deleted}} 笔\n课程 ${candidate.courses.count{!it.deleted}} 门\n待办 ${candidate.tasks.count{!it.deleted}} 个\n照片 ${candidate.attachments.size} 张\n\n${if(internalRestore)"现有 API 密钥将保留。" else "恢复后请重新输入 API 密钥。"}")},confirmButton={Button(onClick={run{backupManager.restore(candidate);restoreCandidate=null;"恢复完成"}},enabled=!busy){Text("恢复")}},dismissButton={TextButton(onClick={restoreCandidate=null}){Text("取消")}})}
        launchCourse?.let{o->AlertDialog(onDismissRequest={launchCourse=null},title={Text(o.name)},text={Text("${o.date} ${o.start}–${o.end}\n${o.room}\n${o.teacher}")},confirmButton={TextButton(onClick={launchCourse=null;edit(exceptionEditor(state,execute,o,state.courseExceptions.find{!it.deleted && "${it.ruleId}:${it.date}"==o.id}))}){Text("调整这次课")}},dismissButton={TextButton(onClick={launchCourse=null}){Text("关闭")}})}
    }
}

