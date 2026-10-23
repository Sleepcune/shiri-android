package com.hubra.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hubra.app.ai.ConversationMaintenance
import com.hubra.app.ai.ChatService
import com.hubra.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatLifecycleDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun clearingInputRetainsSavedBusinessAndRejectsLateWrites() = runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot();val id="test-${UUID.randomUUID()}"
        val plan=File(context.filesDir,"action-plans/$id.json")
        try {
            repo.restore(InitialData.create());val epoch=repo.requestEpoch
            repo.appendRequest(Message(id,"user","创建待办",Instant.now().toString(),pending=true),epoch)
            val op=Operation("$id:0","task.create",args=buildJsonObject { put("title","已保存的待办") })
            assertEquals("success",repo.executeForRequest(op,id,epoch).status)
            plan.parentFile!!.mkdirs();plan.writeText("{}")
            assertEquals(1,ConversationMaintenance(context).clear(setOf(id)))
            assertFalse(plan.exists());assertEquals(1,repo.snapshot().tasks.size)
            try { repo.executeForRequest(op.copy(id="$id:1"),id,epoch);fail("Late action must stop") } catch(_:CancellationException) {}
            assertFalse(repo.updateForRequest(id,epoch) { it.copy(messages=it.messages+Message("late","assistant","迟到",Instant.now().toString())) })
            assertTrue(repo.snapshot().messages.isEmpty());assertEquals(1,repo.snapshot().operations.size)
        } finally { repo.restore(before);plan.delete() }
    }
    @Test fun restoredPendingInputCannotAdmitOldRequestGeneration() = runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot();val id="test-${UUID.randomUUID()}"
        try {
            repo.restore(InitialData.create());val epoch=repo.requestEpoch
            repo.appendRequest(Message(id,"user","待处理",Instant.now().toString(),pending=true),epoch)
            val backup=repo.snapshot();repo.restore(backup)
            val op=Operation("$id:late","task.create",args=buildJsonObject { put("title","不应写入") })
            try { repo.executeForRequest(op,id,epoch);fail("Pre-restore request must stop") } catch(_:CancellationException) {}
            assertTrue(repo.snapshot().tasks.isEmpty())
        } finally { repo.restore(before) }
    }
    @Test fun explicitHolidayNoticeExecutesLocallyAndDisplaysSaturdayMakeups() = runBlocking {
        val repo=context.hubra.repository;val before=repo.snapshot()
        try {
            repo.restore(InitialData.create())
            val reply=ChatService(context).send("2026年9月25日（周五）至27日（周日）放假。10月1日（周四）至7日（周三）放假调休。9月20日（周日）、10月10日（周六）分别上10月6日（周二）、10月7日（周三）的课。根据以上修改")
            assertTrue(reply.contains("已完成"))
            assertEquals(3,CoreFunctions.occurrences(repo.snapshot(),LocalDate.parse("2026-10-10")).size)
            assertTrue(CoreFunctions.occurrences(repo.snapshot(),LocalDate.parse("2026-10-07")).isEmpty())
        } finally { repo.restore(before) }
    }
}
