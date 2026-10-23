package com.hubra.core

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class NullAndReportTest {
    private val now=Instant.parse("2026-10-07T14:00:00Z")
    @Test fun explicitNullClearsTaskDateWhileMissingKeepsIt() {
        val s=InitialData.create().copy(tasks=listOf(Task("task","待办",date="2026-10-08",time="15:00")))
        val unchanged=DomainEngine.apply(s,Operation("keep","task.update","task",1,buildJsonObject{put("title","改标题")}),now)
        assertEquals("2026-10-08",unchanged.state.tasks.single().date)
        val clear=DomainEngine.apply(s,Operation("clear","task.update","task",1,buildJsonObject{put("date",JsonNull);put("time",JsonNull)}),now)
        assertEquals("success",clear.result.status);assertNull(clear.state.tasks.single().date);assertNull(clear.state.tasks.single().time)
    }
    @Test fun explicitNullDetachesCourseAndParentAndRemovesReminderTime() {
        val s=InitialData.create().copy(tasks=listOf(Task("parent","父任务"),Task("child","子任务",parentId="parent",courseRuleId="matrix-wed",courseDate="2026-10-07",reminder=true)))
        val args=buildJsonObject{put("parentId",JsonNull);put("courseRuleId",JsonNull);put("courseDate",JsonNull);put("reminder",false)}
        val t=DomainEngine.apply(s,Operation("detach","task.update","child",1,args),now)
        assertEquals("success",t.result.status)
        val child=t.state.tasks.single{it.id=="child"};assertNull(child.parentId);assertNull(child.courseRuleId);assertNull(child.courseDate)
    }
    @Test fun explicitNullClearsRefundLink() {
        val original=Transaction("spent","expense","bank",100,"2026-10-07",createdAt=now.toString(),modifiedAt=now.toString())
        val refunded=original.copy(id="refund",type="refund",relatedId="spent")
        val s=InitialData.create().copy(transactions=listOf(original,refunded))
        val t=DomainEngine.apply(s,Operation("unlink","transaction.update","refund",1,buildJsonObject{put("relatedId",JsonNull)}),now)
        assertNull(t.state.transactions.single{it.id=="refund"}.relatedId)
    }
    @Test fun explicitNullResetsRoomOverrideAndMovedDate() {
        val s=InitialData.create().copy(courseExceptions=listOf(CourseException("moved","matrix-wed","2026-10-07",movedDate="2026-10-08",room="B301")))
        val t=DomainEngine.apply(s,Operation("reset","courseException.update","moved",1,buildJsonObject{put("movedDate",JsonNull);put("room",JsonNull)}),now)
        assertEquals("success",t.result.status);assertNull(t.state.courseExceptions.single().movedDate);assertNull(t.state.courseExceptions.single().room)
        assertEquals("南雍-西353",CoreFunctions.occurrences(t.state,java.time.LocalDate.parse("2026-10-07")).first{it.courseId=="matrix"}.room)
    }
    @Test fun pureObservationAndApiSettingDoNotStaleDailyReport() {
        var s=InitialData.create().copy(reports=listOf(DailyReport("report","2026-10-07",now.toString(),0,"ready","已生成")))
        s=DomainEngine.apply(s,Operation("observe","reconciliation.create",args=buildJsonObject{put("accountId","wechat");put("actualCents",100L)}),now).state
        assertFalse(s.reports.single().stale)
        s=DomainEngine.apply(s,Operation("profile","settings.update",args=buildJsonObject{put("activeProfileId","another")}),now).state
        assertFalse(s.reports.single().stale)
    }
    @Test fun onlyTomorrowTaskChangesStaleReportAndNoOpsStayFresh() {
        val s=InitialData.create().copy(tasks=listOf(Task("today","今日",date="2026-10-07"),Task("tomorrow","明日",date="2026-10-08")),reports=listOf(DailyReport("report","2026-10-07",now.toString(),0,"ready","已生成")))
        val today=DomainEngine.apply(s,Operation("today-done","task.complete","today",1),now)
        assertFalse(today.state.reports.single().stale)
        val tomorrow=DomainEngine.apply(s,Operation("tomorrow-edit","task.update","tomorrow",1,buildJsonObject{put("title","更改后的明日")}),now)
        assertTrue(tomorrow.state.reports.single().stale)
    }
    @Test fun transferDestinationCannotBeSilentlyKeptWhenExplicitlyCleared() {
        val tx=Transaction("transfer","transfer","bank",100,"2026-10-07",toAccountId="campus",createdAt=now.toString(),modifiedAt=now.toString())
        val s=InitialData.create().copy(transactions=listOf(tx))
        val t=DomainEngine.apply(s,Operation("clear-transfer","transaction.update","transfer",1,buildJsonObject{put("toAccountId",JsonNull)}),now)
        assertEquals("failed",t.result.status);assertEquals("campus",t.state.transactions.single().toAccountId)
    }
    @Test fun mealCanDetachFromTransactionWithExplicitNull() {
        val tx=Transaction("tx","expense","campus",100,"2026-10-07",createdAt=now.toString(),modifiedAt=now.toString())
        val s=InitialData.create().copy(transactions=listOf(tx),meals=listOf(Meal("meal","2026-10-07",foods="米饭",transactionId="tx")))
        val t=DomainEngine.apply(s,Operation("detach-meal","meal.update","meal",1,buildJsonObject{put("transactionId",JsonNull)}),now)
        assertNull(t.state.meals.single().transactionId)
    }
    @Test fun undoInitializationRejectsSubsequentDependentTransactions() {
        val initialized=DomainEngine.apply(InitialData.create(),Operation("init","account.initialize",args=buildJsonObject{put("accountId","campus");put("amountCents",1000L)}),now)
        val spent=DomainEngine.apply(initialized.state,Operation("spent","transaction.create",args=buildJsonObject{put("accountId","campus");put("amountCents",100L)}),now.plusSeconds(1))
        val undo=DomainEngine.apply(spent.state,Operation("undo-init","undo",args=buildJsonObject{put("operationId","init")}),now.plusSeconds(2))
        assertEquals("conflict",undo.result.status);assertEquals(900L,CoreFunctions.balance(undo.state,"campus"))
    }
    @Test fun undoCourseCreationRejectsSubsequentlyAddedRule() {
        val created=DomainEngine.apply(InitialData.create(),Operation("new-course","course.create",args=buildJsonObject{put("name","额外课")}),now)
        val rule=DomainEngine.apply(created.state,Operation("new-rule","courseRule.create",args=buildJsonObject{put("courseId",created.result.targetId!!);put("dayOfWeek",1);put("startPeriod",1);put("endPeriod",2);put("weeks",JsonArray(listOf(JsonPrimitive(7))))}),now.plusSeconds(1))
        val undo=DomainEngine.apply(rule.state,Operation("undo-course","undo",args=buildJsonObject{put("operationId","new-course")}),now.plusSeconds(2))
        assertEquals("conflict",undo.result.status)
    }
}
