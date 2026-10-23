package com.hubra.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hubra.app.backup.BackupManager
import com.hubra.app.data.StateRepository
import com.hubra.app.media.AttachmentManager
import com.hubra.app.media.OcrService
import com.hubra.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import android.net.Uri
import java.io.File
import java.time.LocalDate
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubraDeviceTest {
    private val context: Context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun op(kind:String,args:JsonObject)=Operation(UUID.randomUUID().toString(),kind,args=args)
    @Test fun multipleSemestersPersistAndBackupKeepsTheirSeparateTimetables()=runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot();val manager=BackupManager(context)
        val file=File(context.cacheDir,"semesters-backup.hubra")
        try {
            val initial=InitialData.create();repo.restore(initial)
            val created=repo.execute(op("semester.create",buildJsonObject { put("name","2027 春季");put("startDate","2027-03-01");put("weekCount",20);put("select",true) }))
            assertEquals("success",created.status)
            val springId=created.targetId!!
            assertEquals(initial.settings.periods,repo.snapshot().settings.periods)
            assertTrue(CoreFunctions.occurrences(repo.snapshot(),LocalDate.parse("2026-10-08")).isEmpty())
            val course=initial.courses.first { it.id=="politics" };val rule=initial.courseRules.first { it.id=="politics-thu" }
            assertEquals("success",repo.execute(op("course.import",buildJsonObject { put("courses",HubraJson.json.encodeToJsonElement(listOf(course)));put("rules",HubraJson.json.encodeToJsonElement(listOf(rule))) })).status)
            assertEquals(1,repo.snapshot().courses.count { it.semesterId==springId })
            manager.export(Uri.fromFile(file),"test-passphrase")
            val exported=manager.inspect(Uri.fromFile(file),"test-passphrase")
            repo.restore(initial);manager.restore(exported)
            val reopened=StateRepository(context).snapshot()
            assertEquals(2,reopened.semesters.size);assertEquals(springId,reopened.settings.activeSemesterId)
            repo.execute(op("semester.switch",buildJsonObject { put("semesterId","default") }))
            assertEquals("中国式现代化的理论与实践",CoreFunctions.occurrences(repo.snapshot(),LocalDate.parse("2026-10-08")).single().name)
        } finally { repo.restore(before);file.delete() }
    }
    @Test fun backupIncludesPendingPlansAndReportSources()=runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot();val manager=BackupManager(context)
        val id="test-${UUID.randomUUID()}";val reportId="test-report-${UUID.randomUUID()}"
        val plan=File(context.filesDir,"action-plans/$id.json").apply { parentFile?.mkdirs();writeText("{\"actions\":[],\"reply\":\"ok\"}") }
        val source=File(context.filesDir,"reports/$reportId.json").apply { parentFile?.mkdirs();writeText(HubraJson.json.encodeToString(before)) }
        val file=File(context.cacheDir,"auxiliary-backup.hubra")
        try {
            repo.update { it.copy(messages=it.messages+Message(id,"user","测试","2026-10-08T01:00:00Z",pending=true),reports=it.reports+DailyReport(reportId,"2026-10-07","2026-10-07T14:00:00Z",it.revision)) }
            manager.export(Uri.fromFile(file),"test-passphrase")
            val restored=manager.inspect(Uri.fromFile(file),"test-passphrase")
            plan.delete();source.delete();repo.restore(before);manager.restore(restored)
            assertTrue(plan.exists());assertTrue(source.exists())
            assertTrue(repo.snapshot().messages.any { it.id==id && it.pending })
        } finally { repo.restore(before);plan.delete();source.delete();file.delete() }
    }
    @Test fun persistedTransferIsAtomicAndRetryIsIdempotent()=runBlocking {
        val repo=context.hubra.repository
        val before=repo.snapshot()
        try {
            repo.restore(InitialData.create())
            for(account in listOf("bank","campus"))assertEquals("success",repo.execute(op("account.initialize",buildJsonObject { put("accountId",account);put("amountCents",10_000);put("date",LocalDate.now(CoreFunctions.zone).toString()) })).status)
            val transfer=op("transaction.create",buildJsonObject { put("type","transfer");put("accountId","bank");put("toAccountId","campus");put("amountCents",2000);put("date",LocalDate.now(CoreFunctions.zone).toString()) })
            assertEquals("success",repo.execute(transfer).status)
            assertEquals("success",repo.execute(transfer).status)
            val reopened=StateRepository(context).snapshot()
            assertEquals(8000L,CoreFunctions.balance(reopened,"bank"));assertEquals(12000L,CoreFunctions.balance(reopened,"campus"))
            assertEquals(0L,CoreFunctions.expenseTotal(reopened,LocalDate.now(CoreFunctions.zone)))
            assertEquals(1,reopened.transactions.count { !it.deleted })
        } finally { repo.restore(before) }
    }
    @Test fun encryptedBackupRestoresRecordsAndBadPasswordDoesNotTouchData()=runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot();val manager=BackupManager(context)
        val file=File(context.cacheDir,"test-backup.hubra");val uri=Uri.fromFile(file)
        try {
            repo.restore(InitialData.create())
            repo.execute(op("task.create",buildJsonObject { put("title","设备备份验证");put("date",LocalDate.now(CoreFunctions.zone).toString()) }))
            manager.export(uri,"test-passphrase")
            val saved=repo.snapshot()
            try { manager.inspect(uri,"wrong-passphrase");fail("wrong password must fail") } catch(_:Exception) {}
            assertEquals(saved,repo.snapshot())
            val restored=manager.inspect(uri,"test-passphrase")
            repo.restore(InitialData.create());manager.restore(restored)
            assertEquals("设备备份验证",repo.snapshot().tasks.single().title)
            assertEquals(11,repo.snapshot().settings.periods.size)
        } finally { repo.restore(before);file.delete() }
    }
    @Test fun bundledChineseOcrReadsTheProvidedLongTimetable()=runBlocking {
        val before=context.hubra.repository.snapshot()
        val image=File(context.cacheDir,"timetable-source.jpg")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("timetable-source.jpg").use { input->image.outputStream().use { input.copyTo(it) } }
            val attachment=AttachmentManager(context).importImage(Uri.fromFile(image))
            val text=OcrService(context).recognize(attachment.id)
            assertTrue("OCR should read matrix course",text.contains("矩阵论基础"))
            assertTrue("OCR should read meal-independent course text",text.contains("现代控制理论"))
            assertTrue("Long image should retain segmented provenance",text.contains("截图片段 2"))
        } finally { context.hubra.repository.restore(before);image.delete() }
    }
    @Test fun courseJsonKeepsSingleOccurrenceExceptions()=runBlocking {
        val repository=context.hubra.repository;val before=repository.snapshot()
        val file=File(context.cacheDir,"courses.json");val manager=BackupManager(context)
        try {
            repository.restore(InitialData.create())
            val change=op("course.exception",buildJsonObject { put("ruleId","politics-thu");put("date","2026-10-08");put("room","B301") })
            assertEquals("success",repository.execute(change).status)
            manager.exportCourses(Uri.fromFile(file))
            repository.restore(InitialData.create())
            manager.importCourses(Uri.fromFile(file))
            assertEquals("B301",CoreFunctions.occurrences(repository.snapshot(),LocalDate.parse("2026-10-08")).first { it.ruleId=="politics-thu" }.room)
        } finally { repository.restore(before);file.delete() }
    }
}
