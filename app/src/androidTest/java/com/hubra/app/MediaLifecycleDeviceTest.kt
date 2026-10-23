package com.hubra.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.Uri
import com.hubra.app.media.*
import com.hubra.app.backup.BackupManager
import com.hubra.core.*
import com.hubra.app.ai.ReportSources
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLifecycleDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun reportSourcePublicationDeduplicatesNightlyButAllowsExplicitRefresh()=runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot();val suffix=UUID.randomUUID().toString()
        val first="night-$suffix";val duplicate="night-duplicate-$suffix";val manual1="manual-one-$suffix";val manual2="manual-two-$suffix"
        val cutoff="2026-10-08T14:00:00Z"
        try {
            repo.restore(InitialData.create())
            val snapshot=repo.snapshot();val epoch=repo.requestEpoch
            assertTrue(ReportSources.create(context,DailyReport(first,"2026-10-08",cutoff,snapshot.revision),snapshot,epoch))
            assertFalse(ReportSources.create(context,DailyReport(duplicate,"2026-10-08",cutoff,snapshot.revision),snapshot.copy(meals=listOf(Meal("extra","2026-10-08",foods="额外内容"))),epoch))
            assertEquals(1,repo.snapshot().reports.size)
            assertTrue(File(context.filesDir,"reports/$first.json").exists())
            assertFalse(File(context.filesDir,"reports/$first.json.tmp").exists())
            assertFalse(File(context.filesDir,"reports/$duplicate.json").exists())
            assertTrue(ReportSources.create(context,DailyReport(manual1,"2026-10-08",cutoff,snapshot.revision),snapshot,epoch))
            assertTrue(ReportSources.create(context,DailyReport(manual2,"2026-10-08",cutoff,snapshot.revision),snapshot,epoch))
            assertEquals(3,repo.snapshot().reports.size)
        }finally{repo.restore(before);listOf(first,duplicate,manual1,manual2).forEach{File(context.filesDir,"reports/$it.json").delete();File(context.filesDir,"reports/$it.json.tmp").delete()}}
    }
    @Test fun leasedImageCannotBeDeletedAndTombstoneBackupRestoresWithoutFile()=runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot();val id=UUID.randomUUID().toString()
        val image=Attachment(id,"attachments/$id.jpg",purposes=listOf("meal"),importedAt=Instant.now().toString(),sizeBytes=3)
        val manager=AttachmentManager(context);val f=manager.file(image).apply{parentFile?.mkdirs();writeBytes(byteArrayOf(1,2,3))}
        val export=File(context.cacheDir,"media-test-${UUID.randomUUID()}.hubra");val backup=BackupManager(context)
        try {
            repo.restore(InitialData.create().copy(attachments=listOf(image),meals=listOf(Meal("meal-$id","2026-10-08",foods="米饭",attachmentIds=listOf(id)))))
            MediaAccess.withLease(listOf(id)) {assertEquals(0,manager.delete(setOf(id)).count);assertTrue(f.exists())}
            assertEquals(3L,manager.delete(setOf(id)).bytes);assertFalse(f.exists())
            assertNotNull(repo.snapshot().attachments.single().deletedAt)
            assertEquals("米饭",repo.snapshot().meals.single().foods)
            backup.export(Uri.fromFile(export),"test-password")
            val state=backup.inspect(Uri.fromFile(export),"test-password")
            repo.restore(InitialData.create());backup.restore(state)
            assertNotNull(repo.snapshot().attachments.single().deletedAt)
            assertFalse(manager.file(repo.snapshot().attachments.single()).exists())
        } finally {repo.restore(before);f.delete();export.delete()}
    }
    @Test fun pendingInputProtectsImageDuringManualDelete()=runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot();val id=UUID.randomUUID().toString()
        val a=Attachment(id,"attachments/$id.jpg",importedAt=Instant.now().toString());val manager=AttachmentManager(context)
        val file=manager.file(a).apply{parentFile?.mkdirs();writeBytes(byteArrayOf(9))}
        try {
            repo.restore(InitialData.create().copy(attachments=listOf(a),messages=listOf(Message("input-$id","user","识别图片",Instant.now().toString(),attachmentIds=listOf(id),pending=true))))
            assertEquals(1,manager.delete(setOf(id)).protected);assertTrue(file.exists())
        } finally {repo.restore(before);file.delete()}
    }
}
