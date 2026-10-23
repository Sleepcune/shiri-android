package com.hubra.core

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class CreateIdentityTest {
    private val now=Instant.parse("2026-10-07T14:00:00Z")
    private val base=InitialData.create().copy(
        meals=listOf(Meal("meal","2026-10-07",foods="米饭")),
        tasks=listOf(Task("task","原始待办")),
        accountRules=listOf(AccountRule("account-rule","外卖","wechat")),
        courseExceptions=listOf(CourseException("exception","matrix-wed","2026-10-07",room="B301")),
        reconciliations=listOf(Reconciliation("reconciliation","wechat",100,100,0,now.toString())),
        transactions=listOf(Transaction("transaction","expense","campus",100,"2026-10-07",createdAt=now.toString(),modifiedAt=now.toString()))
    )
    @Test fun createCannotUpdateOrDuplicateExistingIdentity() {
        val cases=listOf(
            Triple("transaction.create","transaction",buildJsonObject{put("amountCents",200L)}),
            Triple("meal.create","meal",buildJsonObject{put("foods","炒饭")}),
            Triple("course.create","matrix",buildJsonObject{put("name","被覆盖课程")}),
            Triple("courseRule.create","matrix-wed",buildJsonObject{put("room","被覆盖教室")}),
            Triple("task.create","task",buildJsonObject{put("title","被覆盖待办")}),
            Triple("accountRule.create","account-rule",buildJsonObject{put("accountId","campus")}),
            Triple("course.exception","exception",buildJsonObject{put("room","被覆盖调整")}),
            Triple("reconciliation.create","reconciliation",buildJsonObject{put("accountId","wechat");put("actualCents",200L)})
        )
        cases.forEachIndexed { i,(kind,target,args) ->
            val t=DomainEngine.apply(base,Operation("duplicate-create-$i",kind,target,args=args),now)
            assertEquals("failed",t.result.status,"$kind must reject an existing identity")
            assertEquals(base,t.state.copy(operations=emptyList()),"$kind must not overwrite or duplicate original data")
        }
    }
    @Test fun explicitTargetIdentityCannotCollideWithDifferentCollection() {
        val t=DomainEngine.apply(base,Operation("cross-collection","task.create","matrix",args=buildJsonObject{put("title","新待办")}),now)
        assertEquals("failed",t.result.status);assertEquals(base.tasks,t.state.tasks)
    }
    @Test fun newExplicitIdentityStillCreatesAndRequestRetryStaysIdempotent() {
        val request=Operation("fresh-request","task.create","fresh-task",args=buildJsonObject{put("title","新待办")})
        val first=DomainEngine.apply(base,request,now)
        assertEquals("success",first.result.status);assertEquals("fresh-task",first.result.targetId)
        val retry=DomainEngine.apply(first.state,request,now.plusSeconds(1))
        assertEquals(first.state,retry.state);assertEquals(1,retry.state.tasks.count{it.id=="fresh-task"})
    }
}
