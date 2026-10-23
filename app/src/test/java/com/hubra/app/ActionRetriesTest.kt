package com.hubra.app

import com.hubra.app.ai.ActionRetries
import com.hubra.core.*
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.*

class ActionRetriesTest {
    @Test fun failedMealCanContinueWithoutCreatingAnotherBill() {
        val now=Instant.parse("2026-10-08T04:00:00Z")
        val bill=Operation("request:0","transaction.create",args=buildJsonObject { put("amountCents",1500);put("note","午餐烤鸭饭");put("date","2026-10-08") })
        var state=DomainEngine.apply(InitialData.create(),bill,now).state
        val bad=Operation("request:1","meal.create",args=buildJsonObject { put("foods",buildJsonObject{put("dish","烤鸭饭")}) })
        state=DomainEngine.apply(state,bad,now).state
        assertEquals("needs_clarification",state.operations.last().result.status)
        val corrected=bad.copy(args=buildJsonObject { put("foods",buildJsonArray { add("烤鸭");add("米饭") }) })
        for(op in listOf(bill,corrected))state=DomainEngine.apply(state,ActionRetries.resolve(op,state.operations).operation,now).state
        assertEquals(1,state.transactions.size);assertEquals(1,state.meals.size)
        assertEquals(1500L,CoreFunctions.expenseTotal(state,java.time.LocalDate.parse("2026-10-08")))
        assertTrue(state.operations.any { it.id==bad.id && it.result.status=="needs_clarification" })
        for(op in listOf(bill,corrected))state=DomainEngine.apply(state,ActionRetries.resolve(op,state.operations).operation,now).state
        assertEquals(1,state.transactions.size);assertEquals(1,state.meals.size)
    }
    @Test fun continuingAfterUndoDoesNotRecreateTheDeletedWrite() {
        val now=Instant.parse("2026-10-08T04:00:00Z")
        val task=Operation("request:0","task.create",args=buildJsonObject { put("title","测试待办") })
        val first=DomainEngine.apply(InitialData.create(),task,now)
        val undone=DomainEngine.apply(first.state,Operation("undo","undo",args=buildJsonObject { put("operationId",task.id) }),now).state
        val retry=ActionRetries.resolve(task,undone.operations)
        assertTrue(retry.prior!!.undone)
        assertEquals(undone,DomainEngine.apply(undone,retry.operation,now).state)
    }
}
