package com.hubra.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*
import kotlinx.serialization.json.*

class CoreTest {
    private val now = Instant.parse("2026-10-07T10:00:00Z")
    private fun op(kind: String, target: String? = null, version: Long? = null, id: String = java.util.UUID.randomUUID().toString(), vararg values: Pair<String, Any>) =
        Operation(id, kind, target, version, buildJsonObject { values.forEach { (k,v) -> when(v) { is Long -> put(k,v); is Int -> put(k,v); is Boolean -> put(k,v); else -> put(k,v.toString()) } } })
    private fun apply(s: AppState, kind: String, vararg values: Pair<String, Any>) = DomainEngine.apply(s, op(kind, values = values), now).state

    @Test fun unknownBalanceIsNotZero() { assertNull(CoreFunctions.balance(InitialData.create(), "campus")) }
    @Test fun transferIsAtomicAndIsNotExpense() {
        var s = InitialData.create()
        s = apply(s,"account.initialize", "accountId" to "bank", "amountCents" to 100000L)
        s = apply(s,"account.initialize", "accountId" to "campus", "amountCents" to 1000L)
        s = apply(s,"transaction.create", "type" to "transfer", "accountId" to "bank", "toAccountId" to "campus", "amountCents" to 20000L)
        assertEquals(80000L, CoreFunctions.balance(s,"bank")); assertEquals(21000L,CoreFunctions.balance(s,"campus"))
        assertEquals(0L, CoreFunctions.expenseTotal(s,LocalDate.parse("2026-10-07")))
    }
    @Test fun retriesDoNotDuplicateRecords() {
        val request = op("transaction.create", values = arrayOf("amountCents" to 1300L,"note" to "午饭"))
        val once = DomainEngine.apply(InitialData.create(),request,now)
        val twice = DomainEngine.apply(once.state,request,now)
        assertEquals(once.state,twice.state); assertEquals(1,twice.state.transactions.size)
    }
    @Test fun explicitAccountOverridesCategoryAndShoppingOverridesFood() {
        val s = InitialData.create()
        assertEquals("wechat",CoreFunctions.defaultAccount(s,"微信午饭"))
        assertEquals("bank",CoreFunctions.defaultAccount(s,"网上买零食"))
        assertEquals("campus",CoreFunctions.defaultAccount(s,"外卖午饭"))
    }
    @Test fun accountCorrectionRecalculatesBothBalances() {
        var s = InitialData.create().copy(accounts=InitialData.create().accounts.map { it.copy(initialCents=10000, initialDate="2026-08-24") })
        s = apply(s,"transaction.create", "amountCents" to 1400L,"accountId" to "campus")
        val item=s.transactions.single()
        s=DomainEngine.apply(s,op("transaction.update",item.id,item.version,values=arrayOf("accountId" to "wechat")),now).state
        assertEquals(10000L,CoreFunctions.balance(s,"campus")); assertEquals(8600L,CoreFunctions.balance(s,"wechat"))
    }
    @Test fun undoRejectsChangesToSameRecordButAllowsUnrelatedChanges() {
        val a=DomainEngine.apply(InitialData.create(),op("transaction.create",values=arrayOf("amountCents" to 100L)),now)
        val other=DomainEngine.apply(a.state,op("task.create",values=arrayOf("title" to "作业")),now)
        val undone=DomainEngine.apply(other.state,op("undo",values=arrayOf("operationId" to a.result.operationId)),now)
        assertEquals("success",undone.result.status); assertEquals(0,undone.state.transactions.count{!it.deleted}); assertEquals(1,undone.state.tasks.size)
        val changed=DomainEngine.apply(a.state,op("transaction.update",a.result.targetId,1,values=arrayOf("amountCents" to 200L)),now)
        assertEquals("conflict",DomainEngine.apply(changed.state,op("undo",values=arrayOf("operationId" to a.result.operationId)),now).result.status)
    }
    @Test fun invalidTransferDoesNotPartiallyWrite() {
        val s=InitialData.create()
        val t=DomainEngine.apply(s,op("transaction.create",values=arrayOf("type" to "transfer","accountId" to "bank","toAccountId" to "missing","amountCents" to 200L)),now)
        assertEquals("failed",t.result.status); assertTrue(t.state.transactions.isEmpty())
    }
    @Test fun observationDoesNotAdjustBalanceAndPastChangesStaleSnapshot() {
        var s=InitialData.create().copy(accounts=InitialData.create().accounts.map{it.copy(initialCents=1000,initialDate="2026-08-24")})
        s=apply(s,"reconciliation.create","accountId" to "campus","actualCents" to 800L)
        assertEquals(1000L,CoreFunctions.balance(s,"campus")); assertEquals(-200L,s.reconciliations.single().differenceCents)
        s=apply(s,"transaction.create","accountId" to "campus","amountCents" to 100L,"date" to "2026-10-06")
        assertTrue(s.reconciliations.single().stale)
    }
    @Test fun weeksAndSparseCourseRulesMatchScreenshots() {
        val s=InitialData.create()
        assertEquals(7,CoreFunctions.weekNumber(s.settings,LocalDate.parse("2026-10-07")))
        assertNull(CoreFunctions.weekNumber(s.settings,LocalDate.parse("2026-08-23")))
        assertFalse(CoreFunctions.occurrences(s,LocalDate.parse("2026-09-18")).any{it.courseId=="matrix"})
        assertTrue(CoreFunctions.occurrences(s,LocalDate.parse("2026-09-22")).any{it.courseId=="matrix"})
        assertFalse(CoreFunctions.occurrences(s,LocalDate.parse("2026-10-14")).any{it.courseId=="modeling"})
        assertEquals(11,s.settings.periods.size)
    }
    @Test fun oneTimeCourseCancellationPreservesRule() {
        val s=InitialData.create()
        val t=DomainEngine.apply(s,op("course.exception",values=arrayOf("ruleId" to "matrix-wed","date" to "2026-10-07","cancelled" to true)),now).state
        assertFalse(CoreFunctions.occurrences(t,LocalDate.parse("2026-10-07")).any{it.courseId=="matrix"})
        assertTrue(CoreFunctions.occurrences(t,LocalDate.parse("2026-10-14")).any{it.courseId=="matrix"})
    }
    @Test fun repeatingCompletionOnlyCompletesOneOccurrence() {
        val t=DomainEngine.apply(InitialData.create(),op("task.create",values=arrayOf("title" to "整理","date" to "2026-10-07","repeat" to "daily")),now)
        val done=DomainEngine.apply(t.state,op("task.complete",t.result.targetId,1,values=arrayOf("date" to "2026-10-07")),now).state
        assertTrue(CoreFunctions.tasksForDate(done,LocalDate.parse("2026-10-07")).single().completed)
        assertFalse(CoreFunctions.tasksForDate(done,LocalDate.parse("2026-10-08")).single().completed)
    }
    @Test fun backupRoundTripPreservesState() { val s=InitialData.create(); assertEquals(s,HubraJson.json.decodeFromString<AppState>(HubraJson.json.encodeToString(AppState.serializer(),s))) }
    @Test fun refundedMoneyIsSeparateFromExpense() {
        var s=InitialData.create().copy(accounts=InitialData.create().accounts.map{it.copy(initialCents=10000,initialDate="2026-08-24")})
        s=apply(s,"transaction.create","type" to "expense","accountId" to "bank","amountCents" to 3900L)
        s=apply(s,"transaction.create","type" to "refund","accountId" to "bank","amountCents" to 3900L)
        assertEquals(10000L,CoreFunctions.balance(s,"bank")); assertEquals(3900L,CoreFunctions.expenseTotal(s,LocalDate.parse("2026-10-07"))); assertEquals(3900L,CoreFunctions.refundTotal(s,LocalDate.parse("2026-10-07")))
    }
    @Test fun ambiguousReminderDoesNotCreateTask() {
        val t=DomainEngine.apply(InitialData.create(),op("task.create",values=arrayOf("title" to "取快递","date" to "2026-10-08","reminder" to true)),now)
        assertEquals("needs_clarification",t.result.status); assertTrue(t.state.tasks.isEmpty())
    }
    @Test fun linkedReminderMovesWithCourseAndKeepsOffset() {
        var s=InitialData.create()
        s=apply(s,"task.create","title" to "问老师","courseRuleId" to "matrix-wed","courseDate" to "2026-10-07","offsetMinutes" to 30,"reminder" to true)
        s=apply(s,"course.exception","ruleId" to "matrix-wed","date" to "2026-10-07","movedDate" to "2026-10-08","startPeriod" to 9,"endPeriod" to 10)
        assertTrue(CoreFunctions.tasksForDate(s,LocalDate.parse("2026-10-07")).isEmpty())
        assertEquals("20:50",CoreFunctions.tasksForDate(s,LocalDate.parse("2026-10-08")).single().time)
    }
    @Test fun malformedPeriodAndSparseWeeksAreHandled() {
        assertEquals(listOf(3,5,6,7,8,9,10),parseWeeks("3,5-10周"))
        val t=DomainEngine.apply(InitialData.create(),op("courseRule.create",values=arrayOf("courseId" to "matrix","dayOfWeek" to 1,"startPeriod" to 12,"endPeriod" to 12,"weeks" to "3,5-10")),now)
        assertEquals("failed",t.result.status)
    }
}
