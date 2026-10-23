package com.hubra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hubra.app.ai.ChatHistory
import com.hubra.app.ai.ConversationMaintenance
import com.hubra.app.media.AttachmentManager
import com.hubra.core.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ChatScreen(s:AppState,attachmentId:String?,busy:Boolean,removeAttachment:()->Unit,pickImage:()->Unit,send:(String)->Unit,execute:Execute,run:Run,edit:Edit,retry:(Message)->Unit,
    focusInput:Boolean=false,onOpenOverview:()->Unit={},openMedia:()->Unit={},onOpenOperation:((String,String)->Unit)?=null) {
    var text by rememberSaveable { mutableStateOf("") }
    val scroll=rememberLazyListState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val focus=remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmIds by remember { mutableStateOf<Set<String>?>(null) }
    var dateDialog by remember { mutableStateOf(false) }
    var fromDate by rememberSaveable { mutableStateOf(today().toString()) }
    var toDate by rememberSaveable { mutableStateOf(today().toString()) }
    var deleting by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    // A retry keeps its original operation IDs. Show each result at its latest reply.
    val resultOwners=remember(s.messages){buildMap<String,String>{s.messages.forEach{message->message.operationIds.forEach{put(it,message.id)}}}}
    LaunchedEffect(focusInput) { if(focusInput)focus.requestFocus() }
    LaunchedEffect(s.messages.size) { if(s.messages.isNotEmpty() && selected.isEmpty())scroll.animateScrollToItem(s.messages.lastIndex) }
    BackHandler(selected.isNotEmpty()) { selected=emptySet() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            if(selected.isEmpty())TextButton(onClick=onOpenOverview){Icon(Icons.Default.WbSunny,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("今日总览")}
            else Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={selected=emptySet()}){Text("取消")};Text("已选 ${ChatHistory.selection(s,selected).size} 条")}
            Box {
                if(selected.isNotEmpty())IconButton(onClick={confirmIds=selected}){Icon(Icons.Default.DeleteOutline,"删除所选对话")}
                else IconButton(onClick={menu=true}){Icon(Icons.Default.MoreHoriz,"对话菜单")}
                DropdownMenu(menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("图片管理")},onClick={menu=false;openMedia()})
                    DropdownMenuItem(text={Text("按日期清除记录")},onClick={menu=false;dateDialog=true},enabled=s.messages.isNotEmpty())
                    DropdownMenuItem(text={Text("清空全部对话")},onClick={menu=false;confirmIds=s.messages.map { it.id }.toSet()},enabled=s.messages.isNotEmpty())
                }
            }
        }
        if(feedback.isNotBlank())Text(feedback,Modifier.padding(horizontal=18.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state=scroll,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(s.messages.isEmpty())item {
                Column(Modifier.fillMaxWidth().padding(top=40.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("把日常，交给一句话",style=MaterialTheme.typography.headlineSmall)
                    Text("记账、课表、待办和饮食，都在这里。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { SuggestionChip(onClick={text="午饭花了 "},label={Text("记一笔")});SuggestionChip(onClick={text="明天下午三点提醒我 "},label={Text("安排待办")}) }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { SuggestionChip(onClick={text="看看今天有什么课"},label={Text("查看课程")});SuggestionChip(onClick={text="今天午饭吃了 "},label={Text("记录饮食")}) }
                }
            }
            items(s.messages,key={it.id}) { m ->
                val chosen=m.id in ChatHistory.selection(s,selected)
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(m.role=="user")Alignment.End else Alignment.Start) {
                    Surface(Modifier.fillMaxWidth(if(m.role=="user").88f else 1f).combinedClickable(onClick={if(selected.isNotEmpty())selected=if(m.id in selected)selected-m.id else selected+m.id},onLongClick={selected=selected+m.id}),
                        shape=MaterialTheme.shapes.medium,color=if(chosen)MaterialTheme.colorScheme.secondaryContainer else if(m.role=="user")MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(if(m.role=="user")"你"else"拾日",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            m.attachmentIds.forEach { id ->
                                val a=s.attachments.find { it.id==id }
                                if(a==null || a.deletedAt!=null || a.deletionPending)Text(if(a?.deletionPending==true)"图片正在清理 · 对话文字保留" else "图片已清理 · 对话文字保留",style=MaterialTheme.typography.bodySmall)
                                else AsyncImage(model=AttachmentManager(context).file(a),contentDescription="对话附图",modifier=Modifier.fillMaxWidth().heightIn(max=220.dp))
                            }
                            if(m.text.isNotBlank())Text(m.text,style=MaterialTheme.typography.bodyMedium)
                            if(m.pending && m.role=="user") { if(busy)Text("正在处理…",style=MaterialTheme.typography.labelSmall)else TextButton(onClick={retry(m)}){Text("继续处理")} }
                            m.operationIds.distinct().filter{resultOwners[it]==m.id}.mapNotNull { id->s.operations.find { it.id==id } }.forEach { o->OperationResultCard(s,o,execute,run,onOpenOperation) }
                            Text(runCatching { java.time.Instant.parse(m.createdAt).atZone(CoreFunctions.zone).toLocalTime().toString().take(5) }.getOrDefault(""),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Surface(tonalElevation=2.dp) {
            Column(Modifier.fillMaxWidth().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                attachmentId?.let { id->s.attachments.find { it.id==id && it.deletedAt==null }?.let { a->Row(verticalAlignment=Alignment.CenterVertically){AsyncImage(model=AttachmentManager(context).file(a),contentDescription="待发送照片",modifier=Modifier.size(56.dp));TextButton(onClick=removeAttachment){Text("移除照片")}} } }
                Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick=pickImage,enabled=!busy){Icon(Icons.Default.AddPhotoAlternate,"添加图片")}
                    OutlinedTextField(text,{text=it},modifier=Modifier.weight(1f).focusRequester(focus).testTag("chat.input"),placeholder={Text("记一笔，或安排今天…")},maxLines=5,enabled=!busy,shape=MaterialTheme.shapes.large)
                    FilledIconButton(onClick={val input=text.trim();text="";send(input.ifBlank{"请识别这张图片，信息不明确时先问我。"})},enabled=!busy && (text.isNotBlank() || attachmentId!=null)){if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.AutoMirrored.Filled.Send,"发送")}
                }
            }
        }
    }
    if(dateDialog)AlertDialog(onDismissRequest={dateDialog=false},title={Text("按日期清除")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(fromDate,{fromDate=it},label={Text("开始日期 YYYY-MM-DD")});OutlinedTextField(toDate,{toDate=it},label={Text("结束日期 YYYY-MM-DD")});Text("同一次输入及其回复会一起清除。",style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(onClick={runCatching { val first=LocalDate.parse(fromDate);val last=LocalDate.parse(toDate);require(first<=last);confirmIds=s.messages.filter { m->runCatching { java.time.Instant.parse(m.createdAt).atZone(CoreFunctions.zone).toLocalDate() in first..last }.getOrDefault(false) }.map { it.id }.toSet();dateDialog=false }.onFailure { feedback="请填写有效的起止日期" }}){Text("预览范围")}},dismissButton={TextButton(onClick={dateDialog=false}){Text("取消")}})
    confirmIds?.let { ids->
        val count=ChatHistory.selection(s,ids).size
        AlertDialog(onDismissRequest={if(!deleting)confirmIds=null},title={Text("清除 $count 条对话？")},text={Text("已记下的账单、待办、课程、饮食和图片保留。正在处理的所选输入会停止。历史本机备份可能保留副本，可在存储管理中单独清理。")},confirmButton={TextButton(onClick={deleting=true;scope.launch { try { val deleted=ConversationMaintenance(context).clear(ids);feedback="已清除 $deleted 条对话";selected=emptySet();confirmIds=null }catch(e:Exception){feedback=e.message?:"清除失败，请重试"}finally{deleting=false} }},enabled=!deleting && count>0){Text(if(deleting)"清除中…"else"清除")}},dismissButton={TextButton(onClick={confirmIds=null},enabled=!deleting){Text("取消")}})
    }
}
