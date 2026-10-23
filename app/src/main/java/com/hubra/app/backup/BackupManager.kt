package com.hubra.app.backup

import android.content.Context
import android.net.Uri
import com.hubra.app.data.BackupCipher
import com.hubra.app.hubra
import com.hubra.core.*
import com.hubra.app.media.MediaAccess
import com.hubra.app.ai.ChatRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.*

class BackupManager(private val context: Context) {
    private var inspectedAssets: Map<String, ByteArray> = emptyMap()
    private var inspectedState: AppState? = null
    private var inspectedAuxiliary: Map<String, ByteArray> = emptyMap()
    private var portableRestore = true
    private val maxBytes = 128 * 1024 * 1024
    private fun bounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        input.use { stream -> while (true) { val n = stream.read(buffer); if (n < 0) break; require(out.size() + n <= maxBytes) { "备份超过128MB，请先清理旧照片" }; out.write(buffer, 0, n) } }
        return out.toByteArray()
    }
    private fun imageFile(relative: String): File {
        require(relative.matches(Regex("attachments/[A-Za-z0-9_-]+\\.jpg"))) { "附件路径非法" }
        val root = context.filesDir.canonicalFile
        return File(root, relative).canonicalFile.also { require(it.path.startsWith(root.path + File.separator)) }
    }
    private fun archive(state: AppState): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("state.json")); zip.write(HubraJson.json.encodeToString(state).toByteArray()); zip.closeEntry()
            state.attachments.filter { it.deletedAt==null }.forEach { attachment ->
                val file = imageFile(attachment.relativePath)
                if(!file.exists() && attachment.deletionPending)return@forEach
                require(file.exists()) { "图片 ${attachment.id} 已丢失，备份未完成" }
                zip.putNextEntry(ZipEntry(attachment.relativePath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                require(out.size() <= maxBytes) { "备份超过128MB" }
            }
            val auxiliary = state.messages.filter { it.role=="user" }.map { "action-plans/${it.id}.json" } + state.reports.map { "reports/${it.id}.json" }
            auxiliary.distinct().forEach { path ->
                val file=auxiliaryFile(path)
                if(file.exists()) { zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); require(out.size()<=maxBytes) }
            }
        }
        return out.toByteArray()
    }
    suspend fun export(uri: Uri, password: String) = withContext(Dispatchers.IO) { MediaAccess.serialized {
        val state = context.hubra.repository.snapshot()
        val encrypted = BackupCipher.encrypt(archive(state), password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encrypted) } ?: error("无法写入备份文件")
        context.hubra.repository.update { it.copy(settings = it.settings.copy(lastExportAt = Instant.now().toString())) }
    } }
    suspend fun inspect(uri: Uri, password: String): AppState = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.let(::bounded) ?: error("无法读取备份")
        val plain = BackupCipher.decrypt(bytes, password)
        var state: AppState? = null; val assets = mutableMapOf<String, ByteArray>(); val auxiliary=mutableMapOf<String, ByteArray>(); var total = 0
        ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= maxBytes) { "展开后的备份过大" }; out.write(buffer, 0, n) }
                if (entry.name == "state.json") { require(state == null) { "备份包含重复状态" }; state = HubraJson.json.decodeFromString<AppState>(out.toString("UTF-8")) }
                else if(entry.name.startsWith("attachments/")) { imageFile(entry.name); require(!assets.containsKey(entry.name)); assets[entry.name] = out.toByteArray() }
                else { auxiliaryFile(entry.name); require(!auxiliary.containsKey(entry.name)); auxiliary[entry.name]=out.toByteArray() }
            }
        }
        val restored = StateMigration.toV3(CoreFunctions.normalizeSemesters(state ?: error("备份缺少数据")),Instant.now())
        validate(restored)
        restored.attachments.filter { it.deletedAt==null && !it.deletionPending }.forEach { require(assets.containsKey(it.relativePath)) { "备份缺少附件：${it.id}" } }
        require(assets.keys.all { path->restored.attachments.any { it.relativePath==path && it.deletedAt==null } }) { "备份包含无关联的图片" }
        val allowed=restored.messages.map { "action-plans/${it.id}.json" }.toSet()+restored.reports.map { "reports/${it.id}.json" }.toSet()
        require(auxiliary.keys.all { it in allowed }) { "备份包含无关联的辅助文件" }
        auxiliary.values.forEach { HubraJson.json.parseToJsonElement(it.toString(Charsets.UTF_8)) }
        inspectedAssets = assets; inspectedState = restored; inspectedAuxiliary=auxiliary
        portableRestore = true
        restored
    }
    suspend fun restore(state: AppState) = withContext(Dispatchers.IO) {
        require(inspectedState==state){"请先检查备份后再恢复"}
        // Never wait for cancelled leases while holding the lock their finally blocks need.
        ChatRequests.cancelAll().forEach{it.join()}
        MediaAccess.serialized { withContext(NonCancellable) {
        check(MediaAccess.activeIds.isEmpty()) { "有图片正在处理，请稍后恢复备份" }
        require(inspectedState == state) { "请先检查备份后再恢复" }
        validate(state); createSnapshotUnlocked()
        // New attachment names prevent a failed restore from overwriting current images.
        val mapping = mutableMapOf<String, String>()
        val previous = context.hubra.repository.snapshot()
        val oldAuxiliary=inspectedAuxiliary.keys.associateWith { auxiliaryFile(it).takeIf(File::exists)?.readBytes() }
        var next=state
        try {
            inspectedAssets.forEach { (path, bytes) ->
                val nextPath = "attachments/${UUID.randomUUID()}.jpg"
                mapping[path] = nextPath
                val file = imageFile(nextPath); file.parentFile?.mkdirs(); file.writeBytes(bytes)
            }
            next = state.copy(attachments = state.attachments.map { it.copy(relativePath = mapping[it.relativePath] ?: it.relativePath) })
            inspectedAuxiliary.forEach { (path,bytes)->auxiliaryFile(path).apply { parentFile?.mkdirs(); writeBytes(bytes) } }
            context.hubra.repository.restore(next)
        } catch(e:Exception) {
            oldAuxiliary.forEach { (path,bytes)->val file=auxiliaryFile(path); if(bytes==null)file.delete()else file.writeBytes(bytes) }
            mapping.values.forEach { imageFile(it).delete() }
            throw e
        }
        // The mandatory pre-restore snapshot retains old images; unused live files can now be reclaimed.
        val retained=next.attachments.filter{it.deletedAt==null}.map{it.relativePath}.toSet()
        previous.attachments.filter{it.relativePath !in retained}.forEach { imageFile(it.relativePath).delete() }
        if (portableRestore) (previous.profiles.map { it.id } + next.profiles.map { it.id }).distinct().forEach { context.hubra.secrets.put(it, "") }
        inspectedState = null; inspectedAssets = emptyMap(); inspectedAuxiliary=emptyMap()
    } } }
    fun listSnapshots(): List<File> = File(context.filesDir,"snapshots").listFiles()?.filter { it.extension=="hubra" }?.sortedByDescending { it.name }.orEmpty()
    suspend fun inspectSnapshot(file: File): AppState {
        val root=File(context.filesDir,"snapshots").canonicalFile
        require(file.canonicalFile.parentFile==root && file.extension=="hubra") { "快照路径无效" }
        val password=context.hubra.secrets.get("__snapshot") ?: error("本机快照密钥丢失")
        return inspect(Uri.fromFile(file),password).also { portableRestore=false }
    }
    suspend fun createSnapshot() = withContext(Dispatchers.IO) { MediaAccess.serialized { createSnapshotUnlocked() } }
    private suspend fun createSnapshotUnlocked() {
        val folder = File(context.filesDir, "snapshots").apply { mkdirs() }
        val password = context.hubra.secrets.get("__snapshot") ?: UUID.randomUUID().toString().also { context.hubra.secrets.put("__snapshot", it) }
        val file = File(folder, "${Instant.now().toEpochMilli()}.hubra")
        val temp = File(folder, "${file.name}.tmp")
        temp.writeBytes(BackupCipher.encrypt(archive(context.hubra.repository.snapshot()), password))
        check(temp.renameTo(file))
        folder.listFiles()?.filter { it.extension == "hubra" }?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
    }
    suspend fun exportCsv(uri: Uri) = withContext(Dispatchers.IO) {
        val state = context.hubra.repository.snapshot()
        fun csv(s: String) = "\"${s.replace("\"", "\"\"")}\""
        val rows = state.transactions.filter { !it.deleted }.joinToString("\n") { t ->
            listOf(t.id,t.date,t.type,state.accounts.firstOrNull { it.id==t.accountId }?.name ?: t.accountId,t.amountCents.toString(),t.category,t.note,t.toAccountId.orEmpty(),t.relatedId.orEmpty()).joinToString(",", transform=::csv)
        }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write("\uFEFFID,日期,类型,账户,金额分,分类,备注,转入账户,关联流水\n$rows") } ?: error("无法导出")
    }
    suspend fun exportCourses(uri: Uri, courseId:String?=null) = withContext(Dispatchers.IO) {
        val state = context.hubra.repository.snapshot()
        val courses=state.courses.filter { !it.deleted && it.semesterId==state.settings.activeSemesterId && (courseId==null || it.id==courseId) }
        require(courseId==null || courses.isNotEmpty()) { "课程不存在" }
        val rules=state.courseRules.filter { !it.deleted && courses.any { c->c.id==it.courseId } }
        val obj = buildJsonObject { put("schemaVersion",1); put("semester",HubraJson.json.encodeToJsonElement(state.semesters.first { it.id==state.settings.activeSemesterId })); put("settings",buildJsonObject { put("semesterStart",state.settings.semesterStart); put("semesterWeeks",state.settings.semesterWeeks); put("periods",HubraJson.json.encodeToJsonElement(state.settings.periods)) }); put("courses",HubraJson.json.encodeToJsonElement(courses)); put("rules",HubraJson.json.encodeToJsonElement(rules)); put("exceptions",HubraJson.json.encodeToJsonElement(state.courseExceptions.filter { !it.deleted && rules.any { r->r.id==it.ruleId } })) }
        context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use { it.write(obj.toString()) } ?: error("无法导出")
    }
    suspend fun importCourses(uri: Uri) = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.let(::bounded)?.toString(Charsets.UTF_8) ?: error("无法读取课表")
        val obj = HubraJson.json.parseToJsonElement(text).jsonObject
        require(obj["schemaVersion"]?.jsonPrimitive?.int==1) { "课表格式版本不支持" }
        val current = context.hubra.repository.snapshot()
        val settings = obj["settings"]?.let { HubraJson.json.decodeFromJsonElement<Settings>(it) }
        if (settings != null) require(settings.semesterStart==current.settings.semesterStart && settings.periods==current.settings.periods) { "该课表的学期或作息不同，请先切换或新建匹配的学期；课节时间在作息设置中匹配" }
        val result = context.hubra.repository.execute(Operation(UUID.randomUUID().toString(),"course.import",args=buildJsonObject { put("courses",obj["courses"] ?: JsonArray(emptyList())); put("rules",obj["rules"] ?: JsonArray(emptyList())); put("exceptions",obj["exceptions"] ?: JsonArray(emptyList())) }))
        check(result.status=="success") { result.message }
    }
    private fun validate(s: AppState) {
        require(s.schemaVersion==2) { "备份版本不支持" }
        require(s.accounts.size==4 && s.accounts.map { it.id }.toSet()==setOf("campus","transport","bank","wechat")) { "账户数据不完整" }
        require(LocalDate.parse(s.settings.semesterStart).dayOfWeek==java.time.DayOfWeek.MONDAY); require(s.settings.semesterWeeks in 1..100)
        require(s.settings.periods.isNotEmpty() && s.settings.periods.map { it.number }.distinct().size==s.settings.periods.size)
        s.settings.periods.forEach { require(java.time.LocalTime.parse(it.start)<java.time.LocalTime.parse(it.end)) }
        fun <T> unique(items: List<T>, id:(T)->String) { require(items.map(id).distinct().size==items.size) { "备份包含重复ID" } }
        unique(s.transactions){it.id}; unique(s.tasks){it.id}; unique(s.courses){it.id}; unique(s.courseRules){it.id}; unique(s.courseExceptions){it.id}; unique(s.attachments){it.id}; unique(s.meals){it.id}; unique(s.operations){it.id}; unique(s.messages){it.id}; unique(s.reports){it.id}; unique(s.profiles){it.id}; unique(s.semesters){it.id}
        require(s.semesters.any { it.id==s.settings.activeSemesterId }) { "当前学期不存在" }
        s.semesters.forEach { require(it.name.isNotBlank() && it.weekCount in 1..100 && LocalDate.parse(it.startDate).dayOfWeek==java.time.DayOfWeek.MONDAY) }
        s.courses.forEach { c->require(s.semesters.any { it.id==c.semesterId }) { "课程所属学期不存在" } }
        s.transactions.forEach { require(s.accounts.any { a->a.id==it.accountId }); require(it.type in listOf("expense","income","transfer","refund","adjustment")); require(it.type=="adjustment" || it.amountCents>0); LocalDate.parse(it.date); Instant.parse(it.createdAt); if(it.type=="transfer")require(it.toAccountId!=it.accountId && s.accounts.any { a->a.id==it.toAccountId }) }
        s.accounts.forEach { CoreFunctions.balance(s,it.id) }
        s.courseRules.forEach { r->val course=s.courses.firstOrNull { it.id==r.courseId };require(course!=null); require(r.dayOfWeek in 1..7 && r.weeks.all { it in 1..100 }); require(r.startPeriod<=r.endPeriod); if(!r.deleted && !course.deleted) { val term=s.semesters.first { it.id==course.semesterId };require(r.weeks.all { it<=term.weekCount }); require(s.settings.periods.any { it.number==r.startPeriod } && s.settings.periods.any { it.number==r.endPeriod }) } }
        s.courseExceptions.forEach { require(s.courseRules.any { r->r.id==it.ruleId }); LocalDate.parse(it.date); it.movedDate?.let(LocalDate::parse) }
        s.tasks.forEach { it.date?.let(LocalDate::parse); it.time?.let(java.time.LocalTime::parse); require(it.repeat in listOf("none","daily","weekdays","weekly")) }
        s.attachments.forEach { imageFile(it.relativePath); require(it.sizeBytes>=0); if(it.importedAt.isNotBlank())Instant.parse(it.importedAt); it.date?.let(LocalDate::parse);it.deletedAt?.let(Instant::parse) }
        require(s.settings.photoRetentionDays in 1..36500)
    }
    private fun auxiliaryFile(path:String):File {
        require(path.matches(Regex("(action-plans|reports)/[A-Za-z0-9_-]+\\.json"))) { "辅助文件路径非法" }
        return File(context.filesDir,path)
    }
}
