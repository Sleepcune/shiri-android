package com.hubra.core

import java.time.*
import kotlin.test.*
import kotlinx.serialization.json.*

class V2QueriesTest {
    private val now=Instant.parse("2026-10-08T02:00:00Z")
    private fun transaction(id:String, type:String, amount:Long, date:String="2026-10-08", category:String="餐饮", related:String?=null, account:String="campus", to:String?=null)=
        Transaction(id,type,account,amount,date,"消费$id",category,to,related,now.toString(),now.toString())
    @Test fun refundsUseOriginalCategoryButOwnDateAndAccount() {
        val s=InitialData.create().copy(transactions=listOf(transaction("old","expense",1000,"2026-09-30","购物"),
            transaction("refund","refund",300,category="其他",related="old",account="bank"), transaction("meal","expense",500,account="bank")))
        val summary=LedgerQueries.summary(s,LedgerFilter("bank",LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31")))
        assertEquals(500L,summary.expenseCents);assertEquals(300L,summary.refundCents);assertEquals(200L,summary.netCents)
        assertEquals(300L,summary.categories.first{it.category=="购物"}.refundCents)
        assertEquals(0f,summary.categories.first{it.category=="购物"}.share)
        val filtered=LedgerQueries.summary(s,LedgerFilter("bank",LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31"),"购物"))
        assertEquals(listOf("refund"),filtered.records.map{it.id});assertEquals(-300L,filtered.netCents)
    }
    @Test fun transferDestinationIsVisibleAndExcludedFromSpend() {
        val s=InitialData.create().copy(transactions=listOf(transaction("move","transfer",5000,account="bank",to="campus"),
            transaction("expense","expense",900),transaction("deleted","expense",500).copy(deleted=true)))
        val result=LedgerQueries.summary(s,LedgerFilter("campus",LocalDate.parse("2026-10-08"),LocalDate.parse("2026-10-08")))
        assertEquals(2,result.records.size);assertEquals(900L,result.expenseCents);assertEquals(1,result.categories.size)
    }
    @Test fun rangeAndSearchProduceMatchingTotalsAndOverflowFails() {
        val s=InitialData.create().copy(transactions=listOf(transaction("a","expense",1,category=""),transaction("b","expense",2,category="餐饮")))
        val q=LedgerQueries.summary(s,LedgerFilter(null,LocalDate.parse("2026-10-08"),LocalDate.parse("2026-10-08"),search="消费a"))
        assertEquals(1L,q.netCents);assertEquals("其他",q.categories.single().category)
        assertFailsWith<ArithmeticException>{LedgerQueries.summary(s.copy(transactions=listOf(transaction("x","expense",Long.MAX_VALUE),transaction("y","expense",1))),LedgerFilter(null,LocalDate.parse("2026-10-08"),LocalDate.parse("2026-10-08")))}
    }
    @Test fun migrationPreservesLegacyAndInfersImportDateFromMessageNotMealDate() {
        val s=InitialData.create().copy(schemaVersion=1,attachments=listOf(Attachment("photo","attachments/photo.jpg")),
            meals=listOf(Meal("m","2025-01-01",foods="米饭",attachmentIds=listOf("photo"))),
            messages=listOf(Message("u","user","照片","2026-10-07T10:00:00Z",listOf("photo"),mode="meal"),Message("a","assistant","好","2026-10-07T10:01:00Z")))
        val migrated=StateMigration.toV2(s,now)
        assertEquals(2,migrated.schemaVersion);assertEquals(s.transactions,migrated.transactions);assertEquals(s.settings.periods,migrated.settings.periods)
        assertEquals("2026-10-07T10:00:00Z",migrated.attachments.single().importedAt)
        assertTrue("meal" in migrated.attachments.single().purposes);assertEquals("u",migrated.messages.last().requestId)
        assertEquals(migrated,StateMigration.toV2(migrated,now.plusSeconds(300)))
    }
    @Test fun unknownLegacyImportDateStartsAtMigrationAndDeletedAttachmentsStayDeleted() {
        val s=InitialData.create().copy(schemaVersion=1,attachments=listOf(Attachment("x","attachments/x.jpg",deletedAt=now.minusSeconds(1).toString())))
        val migrated=StateMigration.toV2(s,now)
        assertEquals(now.toString(),migrated.attachments.single().importedAt);assertNotNull(migrated.attachments.single().deletedAt)
    }
    @Test fun cleanupExcludesFavoriteBackgroundCoursePendingAndTombstone() {
        val old=now.minus(Duration.ofDays(91)).toString()
        fun a(id:String,vararg purposes:String)=Attachment(id,"attachments/$id.jpg",purposes=purposes.toList(),importedAt=old)
        val s=InitialData.create().copy(settings=InitialData.create().settings.copy(photoCleanupEnabled=true,photoRetentionDays=90),
            attachments=listOf(a("yes","meal"),a("favorite","meal").copy(favorite=true),a("course","meal","course"),a("background","meal","background"),
                a("pending","meal"),a("deleted","meal").copy(deletedAt=old),a("new","meal").copy(importedAt=now.toString())),
            messages=listOf(Message("processing","user","photo",old,listOf("pending"),pending=true)))
        assertEquals(listOf("yes"),AttachmentPolicy.expiredCandidates(s,now).map{it.id})
        assertTrue(AttachmentPolicy.expiredCandidates(s.copy(settings=s.settings.copy(photoCleanupEnabled=false)),now).isEmpty())
    }
    @Test fun cleanupBoundaryRequiresStrictlyOlderThanRetentionAndKeepsUnknownDate() {
        val settings=InitialData.create().settings.copy(photoCleanupEnabled=true)
        val s=InitialData.create().copy(settings=settings,attachments=listOf(Attachment("edge","edge",purposes=listOf("meal"),importedAt=now.minus(Duration.ofDays(90)).toString()),Attachment("unknown","unknown",purposes=listOf("meal"))))
        assertTrue(AttachmentPolicy.expiredCandidates(s,now).isEmpty())
    }
    @Test fun operationSummaryUsesHistoricalSnapshotsAndShowsOnlyChangedFields() {
        val old=Task("t","取快递",date="2026-10-09",time="15:00")
        val initial=InitialData.create().copy(tasks=listOf(old))
        val edit=DomainEngine.apply(initial,Operation("edit","task.update","t",args=buildJsonObject{put("time","17:00");put("reminder",true)}),now)
        val record=edit.state.operations.last()
        val changedLater=edit.state.copy(tasks=listOf(old.copy(title="现在的新标题",time="18:00")))
        val summary=OperationSummaries.describe(changedLater,record)
        assertTrue(summary.title.contains("取快递"));assertFalse(summary.title.contains("现在"))
        assertEquals(setOf("时间","提醒"),summary.fields.map{it.label}.toSet())
        assertEquals("15:00",summary.fields.first{it.label=="时间"}.before);assertEquals("17:00",summary.fields.first{it.label=="时间"}.after)
        assertEquals("tasks",summary.targetCollection);assertEquals("t",summary.targetId)
    }
    @Test fun repeatingCompletionHasDateAndStatusWithoutInternalTokens() {
        val task=Task("t","整理",date="2026-10-08",repeat="daily")
        val transition=DomainEngine.apply(InitialData.create().copy(tasks=listOf(task)),Operation("complete","task.complete","t"),now)
        val summary=OperationSummaries.describe(transition.state,transition.state.operations.last())
        assertTrue(summary.fields.any{it.label=="完成日期" && it.after=="2026-10-08"})
        assertFalse(summary.fields.any{it.label in listOf("id","version","createdAt")})
    }
}
