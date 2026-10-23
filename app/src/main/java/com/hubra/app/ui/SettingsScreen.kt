package com.hubra.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hubra.app.hubra
import com.hubra.app.ai.ApiClient
import com.hubra.app.ai.ApiProtocol
import com.hubra.app.system.SystemCoordinator
import com.hubra.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Composable fun SettingsScreen(s:AppState,execute:Execute,edit:Edit,run:Run,backup:(String)->Unit,requestNotifications:()->Unit,requestExact:()->Unit,requestBattery:()->Unit,subsection:String="index",onNavigate:(String)->Unit={},pickBackground:()->Unit={}) {
    val context=LocalContext.current
    val repository=context.hubra.repository
    var status by rememberSaveable{mutableStateOf("点击检查查看权限与下次提醒")}
    var appearanceOpen by remember{mutableStateOf(false)}
    val version=remember(context){runCatching{context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull()?:"0.2.0"}
    val titles=mapOf("ai" to "AI 与记账规则","appearance" to "外观","timetable" to "学期与课表","reminders" to "通知与提醒","media" to "照片与清理","health" to "健康资料","widgets" to "桌面组件","backup" to "备份与导出","about" to "关于拾日")
    Page {
        if(subsection=="index") {
            Text("设置",style=MaterialTheme.typography.headlineMedium)
            if(!s.settings.onboardingComplete)Section("首次设置"){
                Text("余额和 API 都可稍后配置。手动记账、课表、待办支持离线使用，未初始化的账户不会显示为 0。")
                ActionRow("录入初始余额" to {s.accounts.firstOrNull{it.initialCents==null}?.let{edit(accountEditor(it,execute,"initialize"))}},"完成设置" to {run{execute(operation("settings.update",values=args("onboardingComplete" to true)))}})
            }
            Section("使用与显示") {SettingsLink("AI 与记账规则","多个 API、模型检测、付款默认规则"){onNavigate("ai")};SettingsLink("外观","跟随系统、浅色与深色"){onNavigate("appearance")};SettingsLink("学期与课表",activeSemesterName(s)){onNavigate("timetable")};SettingsLink("桌面组件","下一节课、待办与今日总览"){onNavigate("widgets")}}
            Section("提醒与个人资料") {SettingsLink("通知与提醒","核账、上课与待办提醒"){onNavigate("reminders")};SettingsLink("照片与清理","照片库、存储用量与保留期限"){onNavigate("media")};SettingsLink("健康资料","尿酸、体重、饮食限制与医生建议"){onNavigate("health")}}
            Section("数据") {SettingsLink("备份与导出","加密备份、流水与课表"){onNavigate("backup")};SettingsLink("关于拾日",version){onNavigate("about")}}
        } else {
            when(subsection){
                "ai" -> {
                    Section("AI 服务","密钥加密保存在此手机；默认不自动切换服务商。") {
                        s.profiles.forEach{p->Text(p.name,style=MaterialTheme.typography.titleSmall);Text("${p.baseUrl}\n文本 ${p.textModel} · 图片 ${p.imageModel}",style=MaterialTheme.typography.bodySmall);Text(if(p.testedAt==null)"尚未检测能力" else "图片 ${if(p.supportsImages)"支持" else "未通过"} · 工具 ${if(p.supportsTools)"支持" else "未通过"} · JSON ${if(p.supportsJson)"支持" else "未通过"}",style=MaterialTheme.typography.labelSmall);ActionRow("编辑" to {edit(profileEditor(context,p))},"检测连接" to {run{val tested=ApiClient(context).probe(p);repository.update{it.copy(profiles=it.profiles.map{old->if(old.id==tested.id)tested else old})};"能力检测完成"}});HorizontalDivider()}
                        TextButton(onClick={edit(profileEditor(context))}){Text("＋ API 配置")}
                        TextButton(onClick={edit(Editor("默认 AI 服务",listOf(Input("chat","统一对话",s.settings.activeProfileId,options=s.profiles.map{it.id to it.name}),Input("image","图片 / 课表",s.settings.imageProfileId,options=s.profiles.map{it.id to it.name}),Input("report","饮食 / 日报",s.settings.reportProfileId,options=s.profiles.map{it.id to it.name})),save={v->execute(operation("settings.update",values=args("activeProfileId" to v.text("chat"),"imageProfileId" to v.text("image"),"reportProfileId" to v.text("report"))))}))}){Text("选择默认服务")}
                        TextButton(onClick = {
                            edit(Editor(
                                "AI 上下文条数",
                                listOf(Input("count", "保留最近消息条数", s.settings.aiContextMessageCount.toString(), required = true)),
                                help = "条数越多越完整，但请求会更长。范围 1–20，默认 5 条。",
                                save = { v ->
                                    val count = v.text("count").toInt()
                                    require(count in 1..20) { "请输入 1–20 的条数" }
                                    execute(operation("settings.update", values = args("aiContextMessageCount" to count)))
                                }
                            ))
                        }) { Text("设置 AI 上下文条数") }
                    }
                    Section("默认付款规则","明确付款方式优先，然后自定义规则、网购、餐饮、公共交通、微信。") {Text("餐饮 → 校园卡\n公交 / 地铁 → 交通卡\n网购 → 银行卡\n其余 → 微信零钱");s.accountRules.filter{!it.deleted}.forEach{r->TextButton(onClick={edit(accountRuleEditor(s,execute,r))}){Text("${r.keyword} → ${s.accounts.find{it.id==r.accountId}?.name}")}};TextButton(onClick={edit(accountRuleEditor(s,execute))}){Text("＋ 永久规则")}}
                }
                "appearance" -> Section("应用主题") {Choice("主题",s.settings.themeMode,listOf("system" to "跟随系统","light" to "浅色","dark" to "深色"),{mode->run{execute(operation("settings.update",values=args("themeMode" to mode)))}});Text("主题会立即应用；课表背景与课程颜色可单独设置。");TextButton(onClick={appearanceOpen=true}){Text("课表显示设置")}}
                "timetable" -> Section("学期与作息","${activeSemesterName(s)} · ${s.settings.semesterStart} · ${s.settings.semesterWeeks} 周") {Choice("当前学期",s.settings.activeSemesterId,semesterOptions(s),{id->if(id!=s.settings.activeSemesterId)run{execute(operation("semester.switch",values=args("semesterId" to id)))}});ActionRow("学期 / 作息" to {edit(semesterEditor(s,execute))},"重命名学期" to {edit(semesterRenameEditor(s,execute))},"新建学期" to {edit(newSemesterEditor(s,execute))});TextButton(onClick={appearanceOpen=true}){Text("当前学期显示设置")};Text("新建学期会保留旧课程并继承显示设置；账本和待办继续共用。")}
                "reminders" -> Section("提醒状态",status) {
                    ActionRow("检查状态" to {run{status=SystemCoordinator(context).status();"提醒状态已更新"}},"测试通知" to {run{SystemCoordinator(context).testNotification();status=SystemCoordinator(context).status();"请查看系统通知和投递状态"}})
                    ActionRow("允许通知" to requestNotifications,"精确提醒" to requestExact,"电池设置" to requestBattery)
                    TextButton(onClick={edit(Editor("核账与课前提醒",listOf(Input("nightly","晚间核账",s.settings.nightlyReminder.toString(),type="bool"),Input("time","核账时间 HH:mm",s.settings.nightlyTime,required=true),Input("minutes","课前分钟（-1关闭）",s.settings.courseReminderMinutes.toString(),required=true)),save={v->val minutes=v.text("minutes").toInt();require(minutes in -1..1440);execute(operation("settings.update",values=args("nightlyReminder" to v.text("nightly").toBoolean(),"nightlyTime" to v.text("time"),"courseReminderMinutes" to minutes)))}))}){Text("编辑提醒时间")}
                    Text("本地提醒不等待 AI。精确权限关闭后可能延迟；关机或强制停止后请重新打开检查。",style=MaterialTheme.typography.bodySmall)
                }
                "media" -> Section("照片与自动清理") {Text("自动清理${if(s.settings.photoCleanupEnabled)"已开启" else "默认关闭"} · 保留 ${s.settings.photoRetentionDays} 天");Text("最近清理 ${s.settings.lastPhotoCleanupAt?:"尚无记录"}");TextButton(onClick={onNavigate("media")}){Text("打开照片库与清理预览")}}
                "health" -> Section("用于饮食分析的资料","可选填写，不影响其他功能。") {Text("身高 ${s.settings.health.heightCm.ifBlank{"未填写"}} cm · 体重 ${s.settings.health.weightKg.ifBlank{"未填写"}} kg");TextButton(onClick={edit(healthEditor(s,execute))}){Text("编辑健康资料")}}
                "widgets" -> Section("桌面组件") {Text("长按桌面 → 添加组件 → 拾日。可以添加下一节课、今日 / 本周课表、待办、今日支出及总览。");Text("每个组件可以分别设置账户、筛选、深浅主题、透明度和金额显示；金额默认遮盖。");TextButton(onClick={run{SystemCoordinator(context).refresh();"组件刷新已安排"}}){Text("刷新所有组件")}}
                "backup" -> Section("备份与导出","最近导出：${s.settings.lastExportAt?:"尚未导出"}") {ActionRow("加密备份" to {backup("export")},"完整恢复" to {backup("restore")});TextButton(onClick={backup("snapshots")}){Text("本机快照与恢复")};ActionRow("账单 CSV" to {backup("csv")},"课表 JSON" to {backup("courses-out")},"导入课表" to {backup("courses-in")});Text("每日自动快照保留最近 7 份。内部快照不能防止卸载或手机损坏，请定期导出到其他设备。",style=MaterialTheme.typography.bodySmall)}
                "about" -> Section("拾日 $version") {Text("记录一天，安排明天。\n记账 · 课表 · 待办 · 对话");Text("数据保存在此手机，API 只收到处理当前请求所需的信息。");Text("人民币 · Asia/Shanghai",style=MaterialTheme.typography.labelMedium)}
            }
        }
    }
    if(appearanceOpen)TimetableAppearanceSheet(s,execute,run,pickBackground){appearanceOpen=false}
}
@Composable private fun SettingsLink(title:String,detail:String,onClick:()->Unit) {TextButton(onClick=onClick,modifier=Modifier.fillMaxWidth()){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleSmall);Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("›")}}
private fun profileEditor(context:android.content.Context,p:ApiProfile?=null):Editor=Editor(if(p==null)"新增 API 配置" else "编辑 API 配置",listOf(Input("name","配置名称",p?.name?:"",required=true),Input("url","Base URL",p?.baseUrl?:"",required=true),Input("key","API Key（留空保留现有）",type="secret"),Input("text","文本模型",p?.textModel?:"",required=true),Input("image","图片模型",p?.imageModel?:"",required=true),Input("timeout","超时秒数",(p?.timeoutSeconds?:60).toString(),required=true),Input("streaming","流式输出",(p?.streaming?:false).toString(),type="bool")),help="新配置不会预填服务商地址；能力检测需先保存密钥。修改地址或模型后将重新检测能力。",save={v->val url=v.text("url").trimEnd('/');ApiProtocol.endpoint(url);val timeout=v.text("timeout").toInt();require(timeout in 5..300){"超时范围为 5–300 秒"};val keyChanged=v.text("key").isNotBlank();val capabilities=ApiProtocol.capabilitiesFor(p,url,v.text("text"),v.text("image"),keyChanged);val profile=ApiProfile(id=p?.id?:uid(),name=v.text("name"),baseUrl=url,textModel=v.text("text"),imageModel=v.text("image"),timeoutSeconds=timeout,streaming=v.text("streaming").toBoolean(),supportsImages=capabilities?.supportsImages?:false,supportsTools=capabilities?.supportsTools?:false,supportsJson=capabilities?.supportsJson?:false,testedAt=capabilities?.testedAt);if(v.text("key").isNotBlank())context.hubra.secrets.put(profile.id,v.text("key"));context.hubra.repository.update{s->s.copy(profiles=if(p==null)s.profiles+profile else s.profiles.map{if(it.id==p.id)profile else it})};"API 配置已保存"},remove=p?.let{{val s=context.hubra.repository.snapshot();require(s.profiles.size>1){"请保留至少一个 API 配置"};val fallback=s.profiles.first{it.id!=p.id}.id;context.hubra.repository.update{current->current.copy(profiles=current.profiles.filter{it.id!=p.id},settings=current.settings.copy(activeProfileId=if(current.settings.activeProfileId==p.id)fallback else current.settings.activeProfileId,imageProfileId=if(current.settings.imageProfileId==p.id)fallback else current.settings.imageProfileId,reportProfileId=if(current.settings.reportProfileId==p.id)fallback else current.settings.reportProfileId))};context.hubra.secrets.put(p.id,"");"配置已删除"}})
private fun accountRuleEditor(s:AppState,execute:Execute,r:AccountRule?=null)=Editor("永久默认账户规则",listOf(Input("keyword","关键词",r?.keyword?:"",required=true),Input("accountId","默认账户",r?.accountId?:"wechat",options=accountOptions(s))),save={v->execute(operation(if(r==null)"accountRule.create" else "accountRule.update",r?.id,r?.version,args("keyword" to v.text("keyword"),"accountId" to v.text("accountId"))))},remove=r?.let{{execute(operation("accountRule.delete",it.id,it.version))}})
private fun healthEditor(s:AppState,execute:Execute):Editor {
    val h=s.settings.health
    return Editor("健康资料",listOf(Input("high","关注尿酸偏高",h.highUricAcid.toString(),type="bool"),Input("low","关注 BMI 偏低",h.lowBmi.toString(),type="bool"),Input("height","身高 cm",h.heightCm),Input("weight","体重 kg",h.weightKg),Input("uric","尿酸 μmol/L",h.uricAcid),Input("date","检查日期 YYYY-MM-DD",h.testDate),Input("restrictions","过敏、忌口、饮食限制",h.restrictions,type="multiline"),Input("doctor","医生建议",h.doctorAdvice,type="multiline")),help="不填写则只依据你已说明的情况分析，不推测病情或药物。",save={v->listOf("height","weight","uric").forEach{key->v.optional(key)?.let{require(it.toBigDecimal().signum()>0){"数值必须大于 0"}}};v.optional("date")?.let{java.time.LocalDate.parse(it)};val profile=HealthProfile(v.text("high").toBoolean(),v.text("low").toBoolean(),v.text("height"),v.text("weight"),v.text("uric"),v.text("date"),v.text("restrictions"),v.text("doctor"));execute(operation("settings.update",values=args("health" to HubraJson.json.parseToJsonElement(HubraJson.json.encodeToString(profile)))))} )
}

