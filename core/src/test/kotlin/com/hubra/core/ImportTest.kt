package com.hubra.core

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class ImportTest {
    private val now=Instant.parse("2026-10-07T14:01:00Z")
    @Test fun repeatedCourseImportIsIdempotentAcrossRequestIds() {
        val s=InitialData.create()
        val args=buildJsonObject { put("courses",HubraJson.json.encodeToJsonElement(s.courses)); put("rules",HubraJson.json.encodeToJsonElement(s.courseRules)) }
        val once=DomainEngine.apply(s,Operation("import-1","course.import",args=args),now)
        val twice=DomainEngine.apply(once.state,Operation("import-2","course.import",args=args),now)
        assertEquals("success",twice.result.status);assertEquals(s.courses.size,twice.state.courses.size);assertEquals(s.courseRules.size,twice.state.courseRules.size)
    }
    @Test fun invalidBatchRollsBackEveryCourse() {
        val incoming=Course("new","新课程")
        val rules=listOf(CourseRule("bad","new",8,3,4,listOf(7)))
        val args=buildJsonObject {put("courses",HubraJson.json.encodeToJsonElement(listOf(incoming)));put("rules",HubraJson.json.encodeToJsonElement(rules))}
        val t=DomainEngine.apply(InitialData.create(),Operation("batch","course.import",args=args),now)
        assertEquals("failed",t.result.status);assertFalse(t.state.courses.any{it.id=="new"})
    }
    @Test fun moneyOverflowIsRejectedAtomically() {
        val s=InitialData.create().copy(accounts=listOf(Account("wechat","微信",Long.MAX_VALUE,"2026-08-24")))
        val args=buildJsonObject{put("type","income");put("accountId","wechat");put("amountCents",1L)}
        val t=DomainEngine.apply(s,Operation("overflow","transaction.create",args=args),now)
        assertEquals("failed",t.result.status);assertEquals(Long.MAX_VALUE,CoreFunctions.balance(t.state,"wechat"));assertTrue(t.state.transactions.isEmpty())
    }
    @Test fun lateNewTransactionKeepsEarlierSnapshotValid() {
        val early=Instant.parse("2026-10-07T14:00:00Z")
        var s=InitialData.create().copy(accounts=InitialData.create().accounts.map{it.copy(initialCents=10000,initialDate="2026-08-24")})
        s=DomainEngine.apply(s,Operation("observe","reconciliation.create",args=buildJsonObject{put("accountId","wechat");put("actualCents",10000L)}),early).state
        s=DomainEngine.apply(s,Operation("late","transaction.create",args=buildJsonObject{put("accountId","wechat");put("amountCents",100L)}),now).state
        assertFalse(s.reconciliations.single().stale)
    }
    @Test fun changeInOtherAccountDoesNotInvalidateReconciliation() {
        var s=InitialData.create().copy(accounts=InitialData.create().accounts.map{it.copy(initialCents=10000,initialDate="2026-08-24")})
        s=DomainEngine.apply(s,Operation("observe","reconciliation.create",args=buildJsonObject{put("accountId","wechat");put("actualCents",10000L)}),now).state
        s=DomainEngine.apply(s,Operation("old-campus","transaction.create",args=buildJsonObject{put("accountId","campus");put("amountCents",100L);put("date","2026-10-06")}),now).state
        assertFalse(s.reconciliations.single().stale)
    }
    @Test fun sameEntityIdCannotCreateDuplicateRecord() {
        val args=buildJsonObject{put("amountCents",100L)}
        val s=DomainEngine.apply(InitialData.create(),Operation("one","transaction.create","fixed-id",args=args),now).state
        val t=DomainEngine.apply(s,Operation("two","transaction.create","fixed-id",args=args),now)
        assertEquals("failed",t.result.status);assertEquals(1,t.state.transactions.size)
    }
    @Test fun balanceBeforeInitialEffectiveDateIsUnknown() {
        val s=InitialData.create().copy(accounts=listOf(Account("wechat","微信",10000,"2026-10-07")))
        assertNull(CoreFunctions.balanceAt(s,"wechat",Instant.parse("2026-10-06T14:00:00Z")))
    }
    @Test fun futureExpenseIsNotRecordedAsActualMoney() {
        val args=buildJsonObject{put("amountCents",100L);put("date","2026-10-08")}
        val t=DomainEngine.apply(InitialData.create(),Operation("future","transaction.create",args=args),now)
        assertEquals("failed",t.result.status);assertTrue(t.state.transactions.isEmpty())
    }
    @Test fun importWithMissingTeacherDoesNotOverwriteExistingTeacher() {
        val s=InitialData.create()
        val existing=s.courses.first()
        val incomplete=existing.copy(id="other-id",teacher="")
        val args=buildJsonObject{put("courses",HubraJson.json.encodeToJsonElement(listOf(incomplete)));put("rules",JsonArray(emptyList()))}
        val t=DomainEngine.apply(s,Operation("missing-teacher","course.import",args=args),now)
        assertEquals("success",t.result.status);assertEquals(existing.teacher,t.state.courses.single{it.id==existing.id}.teacher)
    }
    @Test fun mealHasStableCreationAndModificationTimestamps() {
        val added=DomainEngine.apply(InitialData.create(),Operation("meal-add","meal.create",args=buildJsonObject{put("foods","米饭鸡蛋")}),now)
        val later=now.plusSeconds(60)
        val changed=DomainEngine.apply(added.state,Operation("meal-edit","meal.update",added.result.targetId,1,buildJsonObject{put("portion","一碗")}),later)
        assertEquals(now.toString(),changed.state.meals.single().createdAt);assertEquals(later.toString(),changed.state.meals.single().modifiedAt)
    }
    @Test fun undoKeepsVersionsMonotonicAndRejectsOldUpdateTokens() {
        val first=DomainEngine.apply(InitialData.create(),Operation("v-add","task.create",args=buildJsonObject{put("title","写作业")}),now)
        val edit=DomainEngine.apply(first.state,Operation("v-edit","task.update",first.result.targetId,1,buildJsonObject{put("title","写数学作业")}),now)
        val undo=DomainEngine.apply(edit.state,Operation("v-undo","undo",args=buildJsonObject{put("operationId","v-edit")}),now)
        assertEquals("写作业",undo.state.tasks.single().title)
        assertEquals(3L,undo.state.tasks.single().version)
        val stale=DomainEngine.apply(undo.state,Operation("v-stale","task.update",first.result.targetId,1,buildJsonObject{put("title","过期修改")}),now)
        assertEquals("conflict",stale.result.status)
    }
}
