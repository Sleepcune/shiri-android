package com.hubra.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import com.hubra.app.hubra
import com.hubra.app.media.AttachmentManager
import com.hubra.app.backup.BackupManager
import com.hubra.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private fun bytesText(n:Long):String=if(n>=1024*1024)"%.1f MB".format(n/1024.0/1024)else "${n/1024} KB"
private fun photoDate(a:Attachment,s:AppState):String=a.date?:s.meals.firstOrNull{!it.deleted && a.id in it.attachmentIds}?.date?:runCatching{java.time.Instant.parse(a.importedAt).atZone(CoreFunctions.zone).toLocalDate().toString()}.getOrDefault(a.importedAt.take(10))
private fun purposeTitle(p:String)=when(p){"meal"->"餐食照片";"course"->"课表截图";"background"->"课表背景";else->"其他附图"}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun MediaScreen(s:AppState,run:Run,initialSection:String="photos") {
    val context=LocalContext.current
    val manager=remember(context){AttachmentManager(context)}
    var tab by rememberSaveable { mutableStateOf(if(initialSection=="storage")"storage" else "photos") }
    var category by rememberSaveable { mutableStateOf("all") }
    var search by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") };var to by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<Set<String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var exportIds by remember { mutableStateOf(emptySet<String>()) }
    var showSnapshots by remember { mutableStateOf(false) }
    var snapshotSelected by remember { mutableStateOf(emptySet<String>()) }
    var snapshotDelete by remember { mutableStateOf(false) }
    var storageReload by remember { mutableIntStateOf(0) }
    var retention by rememberSaveable(s.settings.photoRetentionDays) { mutableStateOf(s.settings.photoRetentionDays.toString()) }
    val storage by produceState(AttachmentManager.Storage(0,0,0),s.revision,storageReload) { value=withContext(Dispatchers.IO){manager.storage()} }
    val snapshots=remember(s.revision,showSnapshots,storageReload){BackupManager(context).listSnapshots()}
    val tree=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri->if(uri!=null)run { val count=manager.exportToTree(exportIds,uri);"已导出 $count 张图片" } }
    val validFrom=runCatching{LocalDate.parse(from);from}.getOrNull()
    val validTo=runCatching{LocalDate.parse(to);to}.getOrNull()
    val photos=s.attachments.filter { a->a.deletedAt==null && (category=="all"||category in AttachmentPolicy.classifications(s,a)) && (!favorites||a.favorite) &&
        (validFrom==null||photoDate(a,s)>=validFrom) && (validTo==null||photoDate(a,s)<=validTo) &&
        (search.isBlank() || (AttachmentPolicy.references(s,a.id).joinToString(" ")+s.meals.filter{a.id in it.attachmentIds}.joinToString(" "){it.foods+it.portion}+photoDate(a,s)).contains(search,true))
    }.sortedByDescending{photoDate(it,s)+it.importedAt}
    BackHandler(selected.isNotEmpty() && previewId==null && deletion==null){selected=emptySet()}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(tab=="photos",{tab="photos"},label={Text("分类图库")})
            FilterChip(tab=="storage",{tab="storage"},label={Text("存储与清理")})
        }
        if(tab=="photos") LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Choice("分类",category,listOf("all" to "全部")+listOf("meal","course","background","other").map{it to purposeTitle(it)},{category=it;selected=emptySet()})
                    OutlinedTextField(search,{search=it},label={Text("搜索餐食、日期或关联记录")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(from,{from=it},label={Text("起始日期")},placeholder={Text("2026-10-01")},singleLine=true,isError=from.isNotBlank()&&validFrom==null,modifier=Modifier.weight(1f))
                        OutlinedTextField(to,{to=it},label={Text("结束日期")},placeholder={Text("2026-10-31")},singleLine=true,isError=to.isNotBlank()&&validTo==null,modifier=Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        FilterChip(favorites,{favorites=!favorites},label={Text("仅收藏")})
                        Spacer(Modifier.weight(1f));Text("${photos.size} 张",style=MaterialTheme.typography.bodySmall)
                        Box { TextButton({menu=true}){Text(if(selected.isEmpty())"管理" else "已选 ${selected.size}")}
                            DropdownMenu(menu,{menu=false}) {
                                DropdownMenuItem(text={Text("选择筛选结果")},onClick={selected=photos.map{it.id}.toSet();menu=false})
                                DropdownMenuItem(text={Text("取消选择")},onClick={selected=emptySet();menu=false})
                                DropdownMenuItem(text={Text("导出到目录")},enabled=selected.isNotEmpty(),onClick={exportIds=selected;menu=false;tree.launch(null)})
                                if(Build.VERSION.SDK_INT>=29)DropdownMenuItem(text={Text("导出到相册")},enabled=selected.isNotEmpty(),onClick={val ids=selected;menu=false;run{"已导出 ${manager.exportToGallery(ids)} 张到相册"}})
                                DropdownMenuItem(text={Text("删除所选图片")},enabled=selected.isNotEmpty(),onClick={deletion=selected;menu=false})
                            }
                        }
                    }
                    Text("长按选择图片。图片保存在应用私有目录，导出后可在相册或所选目录查看。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(photos.isEmpty())item{Empty("暂无符合条件的图片")}
            photos.groupBy { photoDate(it,s).ifBlank{"未标日期"} }.forEach { (date,list)->
                item(key="date-$date"){Text(date,style=MaterialTheme.typography.titleSmall)}
                items(list.chunked(3),key={it.first().id}) { row->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        row.forEach { a->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if(a.id in selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer).combinedClickable(
                                onClick={if(selected.isNotEmpty())selected=if(a.id in selected)selected-a.id else selected+a.id else previewId=a.id},
                                onLongClick={selected=selected+a.id}).padding(5.dp)) {
                                AsyncImage(manager.file(a),contentDescription="${photoDate(a,s)} ${purposeTitle(AttachmentPolicy.classifications(s,a).firstOrNull()?:"other")}",modifier=Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop)
                                Text((if(a.favorite)"★ " else "")+(if(a.id in selected)"✓ " else "")+s.meals.firstOrNull{!it.deleted&&a.id in it.attachmentIds}?.name.orEmpty().ifBlank{purposeTitle(AttachmentPolicy.classifications(s,a).firstOrNull()?:"other")},style=MaterialTheme.typography.labelSmall,maxLines=1)
                                if(a.deletionPending)Text("删除待重试",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
                            }
                        }
                        repeat(3-row.size){Spacer(Modifier.weight(1f))}
                    }
                }
            }
        } else Page {
            Section("空间占用") {
                Text("图片 ${bytesText(storage.photos)} · 缓存 ${bytesText(storage.cache)} · 本机备份 ${bytesText(storage.snapshots)}")
                Text("图片位置：应用私有目录 files/attachments/。卸载会清除本地内容，请先导出备份。",style=MaterialTheme.typography.bodySmall)
                ActionRow("清理缓存" to {run{val n=manager.clearCache();storageReload++;"已清理 ${bytesText(n)} 缓存"}},"本机备份" to {showSnapshots=true})
            }
            Section("定期清理餐食照片","按导入时间保留；收藏、课表引用和正在处理的图片受到保护。") {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("自动清理",Modifier.weight(1f));Switch(s.settings.photoCleanupEnabled,{enabled->run{context.hubra.repository.update{it.copy(settings=it.settings.copy(photoCleanupEnabled=enabled))};"自动清理已${if(enabled)"开启" else "关闭"}"}})}
                Choice("保留期限",retention,listOf("30" to "30 天","90" to "90 天","180" to "180 天",retention to "自定义 $retention 天").distinctBy{it.first},{retention=it})
                OutlinedTextField(retention,{retention=it},label={Text("自定义保留天数（1–36500）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Button({val days=retention.toIntOrNull();run{require(days!=null&&days in 1..36500){"请输入 1–36500 天"};context.hubra.repository.update{it.copy(settings=it.settings.copy(photoRetentionDays=days))};"保留期限已保存"}}){Text("保存期限")}
                Text("最近检查：${s.settings.lastPhotoCleanupAt?.take(19)?.replace('T',' ')?:"暂无"}\n删除 ${s.settings.lastPhotoCleanupCount} 张，释放 ${bytesText(s.settings.lastPhotoCleanupBytes)}",style=MaterialTheme.typography.bodySmall)
                TextButton({run{manager.cleanup(true).message()}},enabled=s.settings.photoCleanupEnabled){Text("现在检查过期图片")}
            }
            Text("本机快照保留最近 7 份，可能含删除前的图片或消息。外部备份与已导出的相册副本需自行管理。",style=MaterialTheme.typography.bodySmall)
        }
    }
    deletion?.let { ids->
        val items=s.attachments.filter{it.id in ids && it.deletedAt==null};val refs=items.sumOf{AttachmentPolicy.references(s,it.id).size}
        AlertDialog(onDismissRequest={deletion=null},title={Text("删除 ${items.size} 张图片？")},text={Text("预计释放 ${bytesText(items.sumOf{manager.file(it).length()})}，关联 $refs 条记录。文字饮食、分析和账单保留，原图片位置显示已删除。已导出副本及旧备份不受影响。")},confirmButton={TextButton({deletion=null;selected=emptySet();run{manager.delete(ids).message()}}){Text("删除图片")}},dismissButton={TextButton({deletion=null}){Text("取消")}})
    }
    previewId?.let { id->s.attachments.firstOrNull{it.id==id}?.let { a->
        Dialog(onDismissRequest={previewId=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth()){Text("图片详情",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton({previewId=null}){Text("关闭")}}
                    if(a.deletedAt==null)AsyncImage(manager.file(a),"餐食或课表图片",modifier=Modifier.fillMaxWidth().heightIn(min=220.dp,max=480.dp),contentScale=ContentScale.Fit)else Empty("图片已删除")
                    Text("分类：${AttachmentPolicy.classifications(s,a).joinToString("、",transform=::purposeTitle)}\n记录日期：${photoDate(a,s)}\n导入：${runCatching{java.time.Instant.parse(a.importedAt).atZone(CoreFunctions.zone).toLocalDateTime().toString().replace('T',' ')}.getOrDefault(a.importedAt)}\n大小：${bytesText(if(a.deletedAt==null)manager.file(a).length()else 0)}",style=MaterialTheme.typography.bodyMedium)
                    Text("关联记录",style=MaterialTheme.typography.titleSmall)
                    AttachmentPolicy.references(s,id).ifEmpty{listOf("尚无关联记录")}.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                    a.deleteError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                    if(a.deletedAt==null)ActionRow((if(a.favorite)"取消收藏" else "收藏") to {run{manager.favorite(setOf(id),!a.favorite);"收藏已更新"}},"导出" to {exportIds=setOf(id);tree.launch(null)},"删除" to {previewId=null;deletion=setOf(id)})
                }
            }
        }
    } }
    if(showSnapshots)AlertDialog(onDismissRequest={showSnapshots=false;snapshotSelected=emptySet()},title={Text("本机备份")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
        if(snapshots.isEmpty())Empty("暂无本机备份")
        snapshots.forEach { f->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(f.name in snapshotSelected,{on->snapshotSelected=if(on)snapshotSelected+f.name else snapshotSelected-f.name});Text("${f.name}\n${bytesText(f.length())}",style=MaterialTheme.typography.bodySmall)} }
    }},confirmButton={TextButton({snapshotDelete=true},enabled=snapshotSelected.isNotEmpty()){Text("删除所选")}},dismissButton={TextButton({showSnapshots=false}){Text("关闭")}})
    if(snapshotDelete)AlertDialog(onDismissRequest={snapshotDelete=false},title={Text("删除 ${snapshotSelected.size} 份本机备份？")},text={Text("删除后无法从这些快照恢复。当前账本、课表、待办和图片保留。")},confirmButton={TextButton({val files=snapshots.filter{it.name in snapshotSelected};snapshotDelete=false;showSnapshots=false;snapshotSelected=emptySet();run{val n=manager.deleteSnapshots(files);storageReload++;"已释放 ${bytesText(n)}"}}){Text("删除")}},dismissButton={TextButton({snapshotDelete=false}){Text("取消")}})
}
