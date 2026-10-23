package com.hubra.core

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class ParameterValidationTest {
    private val now=Instant.parse("2026-10-07T14:00:00Z")
    @Test fun nonIntegerAndExplicitNullAmountAreRejectedInsteadOfKept() {
        val s=InitialData.create().copy(transactions=listOf(Transaction("tx","expense","campus",1300,"2026-10-07",createdAt=now.toString(),modifiedAt=now.toString())))
        listOf(JsonPrimitive(1300.5),JsonNull,JsonPrimitive("thirteen"),JsonPrimitive(true)).forEachIndexed { index,amount ->
            val t=DomainEngine.apply(s,Operation("bad-amount-$index","transaction.update","tx",1,buildJsonObject{put("amountCents",amount)}),now)
            assertEquals("failed",t.result.status);assertEquals(1300L,t.state.transactions.single().amountCents)
        }
    }
    @Test fun invalidIntegerAndBooleanTypesDoNotSilentlyKeepPreviousTaskFields() {
        val s=InitialData.create().copy(tasks=listOf(Task("task","待办",date="2026-10-08",time="15:00",priority=2)))
        val cases=listOf(buildJsonObject{put("priority",1.5)},buildJsonObject{put("priority",JsonNull)},buildJsonObject{put("reminder","yes")},buildJsonObject{put("reminder",JsonNull)})
        cases.forEachIndexed { index,args ->
            val t=DomainEngine.apply(s,Operation("bad-task-$index","task.update","task",1,args),now)
            assertEquals("failed",t.result.status);assertEquals(s.tasks,t.state.tasks)
        }
    }
    @Test fun nullableIntegerCanClearOverrideButInvalidIntegerCannotBeIgnored() {
        val s=InitialData.create().copy(courseExceptions=listOf(CourseException("exception","matrix-wed","2026-10-07",startPeriod=9,endPeriod=10)))
        val cleared=DomainEngine.apply(s,Operation("clear-period","courseException.update","exception",1,buildJsonObject{put("startPeriod",JsonNull);put("endPeriod",JsonNull)}),now)
        assertEquals("success",cleared.result.status);assertNull(cleared.state.courseExceptions.single().startPeriod)
        val invalid=DomainEngine.apply(s,Operation("bad-period","courseException.update","exception",1,buildJsonObject{put("startPeriod","late")}),now)
        assertEquals("failed",invalid.result.status)
    }
}
