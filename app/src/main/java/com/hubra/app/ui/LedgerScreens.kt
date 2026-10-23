package com.hubra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hubra.app.ai.ChatService
import com.hubra.app.media.AttachmentManager
import com.hubra.core.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LedgerScreen(s:AppState,execute:Execute,edit:Edit,run:Run,pickImage:(String)->Unit,openChat:()->Unit,initialSection:String="概览",openMedia:()->Unit={},addMealImage:(String)->Unit={},removeMealImage:(String,String)->Unit={_,_->}) {
    val context=LocalContext.current
    var section by rememberSaveable(initialSection){mutableStateOf(initialSection)}
    var account by rememberSaveable{mutableStateOf("")}
    var range by rememberSaveable{mutableStateOf("本月")}
    var start by rememberSaveable{mutableStateOf(today().withDayOfMonth(1).toString())}
    var end by rememberSaveable{mutableStateOf(today().toString())}
    var category by rememberSaveable{mutableStateOf("")}
    var search by rememberSaveable{mutableStateOf("")}
    var more by remember{mutableStateOf(false)}
    var moreReturn by rememberSaveable{mutableStateOf("概览")}
    var visibleCount by rememberSaveable(account,start,end,category,search){mutableIntStateOf(100)}
    var selectedTransaction by rememberSaveable{mutableStateOf<String?>(null)}
    var selectedAccount by rememberSaveable{mutableStateOf<String?>(null)}
    val accountDetailStates=rememberSaveableStateHolder()
    val filter=runCatching { LedgerFilter(account.takeIf{it.isNotBlank()},LocalDate.parse(start),LocalDate.parse(end),category.takeIf{it.isNotBlank()},search) }.getOrNull()
    val valid=filter!=null && filter.startDate<=filter.endDate
    val summary=if(valid)LedgerQueries.summary(s,filter!!)else null
    fun chooseRange(label:String){range=label;val date=today();start=when(label){"今天"->date;"本周"->date.minusDays(date.dayOfWeek.value-1L);else->date.withDayOfMonth(1)}.toString();end=date.toString()}
    LaunchedEffect(today(),range){if(range!="自定义")chooseRange(range)}
    BackHandler(enabled=section in listOf("核账","操作") || (section=="流水" && (category.isNotBlank() || account.isNotBlank()))){
        if(section in listOf("核账","操作"))section=moreReturn
        else if(category.isNotBlank())category=""
        else {selectedAccount=account;account="";section="账户"}
    }
    Page {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("账本",style=MaterialTheme.typography.headlineMedium);TextButton(onClick={more=true}){Text("更多 ···")}}
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("概览","流水","账户","饮食").forEach{label->FilterChip(section==label,{section=label},label={Text(label)})}}
        if(section in listOf("概览","流水")) {
            Section("统计范围") {
                Choice("账户",account,listOf("" to "全部账户")+accountOptions(s),{account=it})
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("今天","本周","本月","自定义").forEach{label->FilterChip(range==label,{if(label=="自定义")range=label else chooseRange(label)},label={Text(label)})}}
                if(range=="自定义")Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(start,{start=it},label={Text("开始 YYYY-MM-DD")},singleLine=true,modifier=Modifier.weight(1f));OutlinedTextField(end,{end=it},label={Text("结束 YYYY-MM-DD")},singleLine=true,modifier=Modifier.weight(1f))}
                else Text("$start 至 $end",style=MaterialTheme.typography.labelMedium)
                if(!valid)Text("请填写有效日期，开始日期不能晚于结束日期。",color=MaterialTheme.colorScheme.error)
                if(category.isNotBlank())InputChip(true,{category=""},label={Text("$category · 清除分类")})
                if(section=="流水")OutlinedTextField(search,{search=it},label={Text("搜索备注、分类或账户")},singleLine=true,modifier=Modifier.fillMaxWidth())
            }
        }
        when(section) {
            "概览" -> {
                Section("收支概览", "充值与转账单独记录，不重复计入消费。") {
                    Text(money(summary?.netCents),style=MaterialTheme.typography.headlineLarge)
                    Text("消费 ${money(summary?.expenseCents)} · 退款 ${money(summary?.refundCents)}")
                    ActionRow("记一笔" to {edit(transactionEditor(s,execute))},"查看流水" to {section="流水"})
                }
                Section("消费分类","点击分类查看对应流水") {
                    if(summary?.categories.isNullOrEmpty())Empty("这个时间范围还没有消费记录")
                    summary?.categories?.forEach{item->
                        Column(Modifier.fillMaxWidth().clickable{category=item.category;section="流水"}.padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(item.category);Text("${money(item.expenseCents)} · ${(item.share.toDouble()*100).toInt()}%")}
                            LinearProgressIndicator(progress={item.share.toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                            if(item.refundCents!=0L)Text("退款 ${money(item.refundCents)} · 净支出 ${money(item.netCents)}",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Section("账户余额","根据已录入流水推算") {s.accounts.forEach{a->Row(Modifier.fillMaxWidth().clickable{selectedAccount=a.id}.padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(a.name);Text(money(CoreFunctions.balance(s,a.id)))}}}
            }
            "流水" -> {
                ActionRow("支出" to {edit(transactionEditor(s,execute))},"转账 / 充值" to {edit(transactionEditor(s,execute,type="transfer"))},"退款" to {edit(transactionEditor(s,execute,type="refund"))})
                Text("消费 ${money(summary?.expenseCents)} · 退款 ${money(summary?.refundCents)} · 净支出 ${money(summary?.netCents)}",style=MaterialTheme.typography.bodySmall)
                if(summary?.records.isNullOrEmpty())Empty("没有匹配流水，试试调整日期、分类或搜索词。")
                summary?.records?.take(visibleCount)?.groupBy{it.date}?.forEach{(date,records)->Section(date){records.forEach{t->TransactionLine(s,t){selectedTransaction=t.id}}}}
                if((summary?.records?.size?:0)>visibleCount)TextButton(onClick={visibleCount+=100}){Text("显示更多 · 已显示 $visibleCount / ${summary?.records?.size}")}
            }
            "账户" -> {
                Section("账户总计", "初始化状态一目了然") {
                    val initialized=s.accounts.filter { it.initialCents!=null }
                    Text("已初始化账户总计：${money(initialized.mapNotNull{CoreFunctions.balance(s,it.id)}.sum())}")
                    val unknown=s.accounts.size-initialized.size
                    Text(if(unknown>0)"另有 $unknown 个账户未初始化" else "所有账户均已初始化", style=MaterialTheme.typography.bodySmall)
                }
                s.accounts.forEach{a->Card(Modifier.fillMaxWidth().clickable{selectedAccount=a.id}){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(a.name,style=MaterialTheme.typography.titleMedium);Text(money(CoreFunctions.balance(s,a.id)),style=MaterialTheme.typography.headlineMedium);Text("点击查看今天、本周、本月与自定义统计",style=MaterialTheme.typography.bodySmall);if(a.initialCents==null)TextButton(onClick={edit(accountEditor(a,execute,"initialize"))}){Text("设置初始余额")}}}}
            }
            "饮食" -> {
                Section("每天吃了什么","基于记录分析尿酸相关注意点与营养是否充足。") {
                    ActionRow("记录" to {edit(mealEditor(s,execute))},"餐食照片" to {pickImage("meal-manual")},"AI 识别" to {pickImage("meal")})
                    ActionRow("照片库" to openMedia,"分析今天" to {run{ChatService(context).send("分析今天已记录的餐食，标注缺失信息，给明天1至2条建议。",mode="nutrition").also{openChat()}}})
                }
                s.meals.filter{!it.deleted}.sortedByDescending{it.date}.forEach{m->Section("${m.date} · ${m.name}"){
                    var showFullAnalysis by rememberSaveable(m.id) { mutableStateOf(false) }
                    Text(m.foods.ifBlank{"餐食照片已保存，可以补充菜品"});if(m.portion.isNotBlank())Text("份量：${m.portion}")
                    m.attachmentIds.forEach{id->Column { AttachmentThumbnail(s,id,"${m.name}照片"); TextButton(onClick={removeMealImage(m.id,id)}){Text("移除照片") }}}
                    if(m.analysis.isNotBlank()) { Text(if(showFullAnalysis) m.analysis else reportSummary(m.analysis,3)); if(m.analysis.lines().size>3) TextButton({showFullAnalysis=!showFullAnalysis}){Text(if(showFullAnalysis)"收起完整分析" else "查看完整分析")} }
                    ActionRow("修改" to {edit(mealEditor(s,execute,m))},"添加照片" to {addMealImage(m.id)},"AI 分析" to {run{val photo=m.attachmentIds.firstOrNull{id->s.attachments.any{it.id==id && it.deletedAt==null}};ChatService(context).send("分析餐食记录 ${m.id}：${m.foods}，份量：${m.portion}。不推测缺失信息。",photo,"nutrition").also{openChat()}}})
                }}
                if(s.meals.none{!it.deleted})Empty("尚未记录餐食，可以只记饮食而不记金额。")
            }
            "核账" -> ReconciliationContent(s,execute,edit)
            "操作" -> {Text("操作记录",style=MaterialTheme.typography.titleLarge);s.operations.asReversed().take(80).forEach{OperationResultCard(s,it,execute,run){collection,id->if(collection=="transactions")selectedTransaction=id else targetEditor(s,id,execute)?.let(edit)}};if(s.operations.isEmpty())Empty("尚无操作记录")}
        }
    }
    if(more)ModalBottomSheet(onDismissRequest={more=false}){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("账本工具",style=MaterialTheme.typography.titleLarge);listOf("核账","操作").forEach{label->TextButton(onClick={moreReturn=section.takeIf{it !in listOf("核账","操作")}?:moreReturn;section=label;more=false},modifier=Modifier.fillMaxWidth()){Text(if(label=="操作")"操作记录与撤销" else "核对实际余额")}};TextButton(onClick={more=false;openMedia()},modifier=Modifier.fillMaxWidth()){Text("照片库与清理")};Spacer(Modifier.height(20.dp))}}
    selectedTransaction?.let{id->s.transactions.find{it.id==id}?.let{t->TransactionDetails(s,t,execute,edit,run){selectedTransaction=null}}}
    if(selectedTransaction==null)selectedAccount?.let{id->s.accounts.find{it.id==id}?.let{a->accountDetailStates.SaveableStateProvider("account:$id"){AccountDetails(s,a,execute,edit,run,onClose={selectedAccount=null},onTransaction={selectedTransaction=it},onDrill={from,to,cat->selectedAccount=null;account=a.id;start=from;end=to;range="自定义";category=cat;section="流水"})}}}
}

@Composable fun TransactionLine(s:AppState,t:Transaction,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)){Text(t.note.ifBlank{typeOptions.find{it.first==t.type}?.second?:"余额调整"},style=MaterialTheme.typography.titleSmall);Text("${s.accounts.find{it.id==t.accountId}?.name} · ${t.category}${t.toAccountId?.let{id->" → ${s.accounts.find{it.id==id}?.name}"}?:""}",style=MaterialTheme.typography.bodySmall)}
        Text("${if(t.type in listOf("expense","transfer"))"−" else "+"}${money(t.amountCents)}",color=if(t.type=="expense")MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
    }
}
@Composable fun AttachmentThumbnail(s:AppState,id:String,description:String) {
    val context=LocalContext.current
    val attachment=s.attachments.find{it.id==id}
    if(attachment==null || attachment.deletedAt!=null || attachment.deletionPending)Box(Modifier.fillMaxWidth().height(80.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(12.dp)).padding(16.dp)){Text(when{attachment?.deletedAt!=null || attachment==null->"照片已清理 · 文字记录仍然保留";!attachment.deleteError.isNullOrBlank()->"照片清理未完成 · 文字记录保留";else->"照片正在清理 · 文字记录保留"},style=MaterialTheme.typography.bodySmall)}
    else AsyncImage(model=AttachmentManager(context).file(attachment),contentDescription=description,modifier=Modifier.fillMaxWidth().heightIn(max=220.dp))
}
@Composable private fun ReconciliationContent(s:AppState,execute:Execute,edit:Edit) {
    Section("核对实际余额","先补漏或改错；明确校准才登记余额调整。") {
        s.accounts.forEach{a->val last=s.reconciliations.lastOrNull{it.accountId==a.id && !it.deleted};Text(a.name,style=MaterialTheme.typography.titleSmall);Text("推算 ${money(CoreFunctions.balance(s,a.id))}${last?.predictedCents?.let{" · 上次核账后变动 ${money(CoreFunctions.balance(s,a.id)?.minus(it))}"}?:""}");ActionRow("输入实际余额" to {edit(accountEditor(a,execute,"reconcile"))},"补漏记" to {edit(transactionEditor(s,execute))},"校准" to {edit(accountEditor(a,execute,"calibrate"))});HorizontalDivider()}
    }
    s.reconciliations.filter{!it.deleted}.asReversed().forEach{r->Section(s.accounts.find{it.id==r.accountId}?.name?:"账户",r.cutoff){Text("实际 ${money(r.actualCents)} · 推算 ${money(r.predictedCents)}\n差额 ${money(r.differenceCents)}");if(r.stale)Text("历史流水已变化，此快照可能不再对应现状",color=MaterialTheme.colorScheme.error)}}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TransactionDetails(s:AppState,t:Transaction,execute:Execute,edit:Edit,run:Run,onClose:()->Unit) {
    ModalBottomSheet(onDismissRequest=onClose){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(t.note.ifBlank{"流水详情"},style=MaterialTheme.typography.headlineSmall);Text(money(t.amountCents),style=MaterialTheme.typography.headlineLarge)
        Text("${t.date} · ${typeOptions.find{it.first==t.type}?.second?:"余额调整"}\n${s.accounts.find{it.id==t.accountId}?.name} · ${t.category}")
        if(t.relatedId!=null)Text("关联流水：${s.transactions.find{it.id==t.relatedId}?.note?:"原记录"}")
        ActionRow("修改" to {onClose();edit(transactionEditor(s,execute,t))},"关联餐食" to {onClose();val form=mealEditor(s,execute);edit(form.copy(inputs=form.inputs.map{if(it.key=="transactionId")it.copy(initial=t.id)else it}))})
        s.meals.filter{!it.deleted && it.transactionId==t.id}.forEach{m->Section(m.name){Text(m.foods);m.attachmentIds.forEach{AttachmentThumbnail(s,it,m.name)}}}
        Text("操作历史",style=MaterialTheme.typography.titleMedium)
        s.operations.asReversed().filter{it.result.affectedIds.contains(t.id) || it.changes.any{change->change.id==t.id}}.take(10).forEach{OperationResultCard(s,it,execute,run)}
        Spacer(Modifier.height(24.dp))
    }}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AccountDetails(s:AppState,a:Account,execute:Execute,edit:Edit,run:Run,onClose:()->Unit,onTransaction:(String)->Unit,onDrill:(String,String,String)->Unit) {
    var range by rememberSaveable(a.id){mutableStateOf("本月")}
    var start by rememberSaveable(a.id){mutableStateOf(today().withDayOfMonth(1).toString())}
    var end by rememberSaveable(a.id){mutableStateOf(today().toString())}
    val summary=runCatching{LedgerQueries.summary(s,LedgerFilter(a.id,LocalDate.parse(start),LocalDate.parse(end)))}.getOrNull()
    ModalBottomSheet(onDismissRequest=onClose){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(a.name,style=MaterialTheme.typography.headlineSmall);Text(money(CoreFunctions.balance(s,a.id)),style=MaterialTheme.typography.headlineLarge);Text("推算余额 · ${a.initialDate?:"尚未设置初值"}")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("今天","本周","本月","自定义").forEach{label->FilterChip(range==label,{range=label;if(label!="自定义"){val d=today();start=when(label){"今天"->d;"本周"->d.minusDays(d.dayOfWeek.value-1L);else->d.withDayOfMonth(1)}.toString();end=d.toString()}},label={Text(label)})}}
        if(range=="自定义"){OutlinedTextField(start,{start=it},label={Text("开始 YYYY-MM-DD")},modifier=Modifier.fillMaxWidth());OutlinedTextField(end,{end=it},label={Text("结束 YYYY-MM-DD")},modifier=Modifier.fillMaxWidth())}
        Text("消费 ${money(summary?.expenseCents)} · 退款 ${money(summary?.refundCents)}\n净支出 ${money(summary?.netCents)}")
        summary?.categories?.forEach{cat->TextButton(onClick={onDrill(start,end,cat.category)},modifier=Modifier.fillMaxWidth()){Text("${cat.category} · ${money(cat.netCents)}")}}
        TextButton(onClick={onDrill(start,end,"")}){Text("查看此范围全部流水")}
        if(a.initialCents==null)Button(onClick={onClose();edit(accountEditor(a,execute,"initialize"))}){Text("设置初始余额")}
        else ActionRow("核账" to {onClose();edit(accountEditor(a,execute,"reconcile"))},"校准" to {onClose();edit(accountEditor(a,execute,"calibrate"))})
        Text("近期流水",style=MaterialTheme.typography.titleMedium);summary?.records?.take(8)?.forEach{TransactionLine(s,it){onTransaction(it.id)}}
        Spacer(Modifier.height(24.dp))
    }}
}
