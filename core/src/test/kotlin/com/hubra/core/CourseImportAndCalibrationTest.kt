package com.hubra.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*
import kotlinx.serialization.json.*

class CourseImportAndCalibrationTest {
    private val now=Instant.parse("2026-10-07T14:00:00Z")
    @Test fun courseImportRemapsDuplicateRuleIdsAndPreservesCancellationAndMove() {
        val s=InitialData.create()
        val matrix=s.courses.single{it.id=="matrix"}.copy(id="exported-matrix")
        val rule=s.courseRules.single{it.id=="matrix-wed"}.copy(id="exported-rule",courseId="exported-matrix")
        val exceptions=listOf(CourseException("cancel","exported-rule","2026-10-07",cancelled=true),CourseException("move","exported-rule","2026-10-14",movedDate="2026-10-15",room="B301"))
        val args=buildJsonObject{put("courses",HubraJson.json.encodeToJsonElement(listOf(matrix)));put("rules",HubraJson.json.encodeToJsonElement(listOf(rule)));put("exceptions",HubraJson.json.encodeToJsonElement(exceptions))}
        val imported=DomainEngine.apply(s,Operation("import-exceptions","course.import",args=args),now)
        assertEquals("success",imported.result.status);assertEquals(2,imported.state.courseExceptions.size)
        assertTrue(imported.state.courseExceptions.all{it.ruleId=="matrix-wed"})
        assertFalse(CoreFunctions.occurrences(imported.state,LocalDate.parse("2026-10-07")).any{it.courseId=="matrix"})
        assertEquals("B301",CoreFunctions.occurrences(imported.state,LocalDate.parse("2026-10-15")).single{it.courseId=="matrix"}.room)
        val repeated=DomainEngine.apply(imported.state,Operation("repeat-exceptions","course.import",args=args),now)
        assertEquals("success",repeated.result.status);assertEquals(2,repeated.state.courseExceptions.size)
        val undone=DomainEngine.apply(imported.state,Operation("undo-import","undo",args=buildJsonObject{put("operationId","import-exceptions")}),now)
        assertEquals("success",undone.result.status)
        assertEquals(CoreFunctions.occurrences(s,LocalDate.parse("2026-10-07")),CoreFunctions.occurrences(undone.state,LocalDate.parse("2026-10-07")))
    }
    @Test fun invalidImportedExceptionRollsBackWholeBatch() {
        val course=Course("new","新课程")
        val rule=CourseRule("new-rule","new",1,1,2,listOf(7))
        val exception=CourseException("invalid","new-rule","2026-10-07",cancelled=true)
        val args=buildJsonObject{put("courses",HubraJson.json.encodeToJsonElement(listOf(course)));put("rules",HubraJson.json.encodeToJsonElement(listOf(rule)));put("exceptions",HubraJson.json.encodeToJsonElement(listOf(exception)))}
        val t=DomainEngine.apply(InitialData.create(),Operation("bad-exception","course.import",args=args),now)
        assertEquals("failed",t.result.status);assertFalse(t.state.courses.any{it.id=="new"})
    }
    @Test fun historicalCalibrationUsesEndOfSpecifiedDateRatherThanCurrentBalance() {
        val base=InitialData.create()
        val txs=listOf(Transaction("yesterday","expense","wechat",100,"2026-10-06",createdAt=now.minusSeconds(86400).toString(),modifiedAt=now.minusSeconds(86400).toString()),Transaction("today","expense","wechat",200,"2026-10-07",createdAt=now.toString(),modifiedAt=now.toString()))
        val s=base.copy(accounts=base.accounts.map{it.copy(initialCents=1000,initialDate="2026-10-01")},transactions=txs)
        val t=DomainEngine.apply(s,Operation("calibrate-history","account.calibrate",args=buildJsonObject{put("accountId","wechat");put("actualCents",800L);put("date","2026-10-06")}),now)
        assertEquals("success",t.result.status);assertEquals(-100L,t.state.transactions.single{it.type=="adjustment"}.amountCents)
        assertEquals(600L,CoreFunctions.balance(t.state,"wechat"))
    }
    @Test fun calibrationBeforeInitializationOrInFutureIsRejected() {
        val base=InitialData.create()
        val s=base.copy(accounts=base.accounts.map{it.copy(initialCents=1000,initialDate="2026-10-07")})
        listOf("2026-10-06","2026-10-08").forEachIndexed { i,date ->
            val t=DomainEngine.apply(s,Operation("bad-calibration-$i","account.calibrate",args=buildJsonObject{put("accountId","wechat");put("actualCents",800L);put("date",date)}),now)
            assertTrue(t.result.status in listOf("failed","needs_clarification"));assertTrue(t.state.transactions.isEmpty())
        }
    }
    @Test fun repeatingHistoricalCalibrationIncludesBackdatedAdjustmentAndDoesNotSubtractAgain() {
        val base=InitialData.create()
        val tx=Transaction("old-spend","expense","wechat",100,"2026-10-06",createdAt=now.minusSeconds(86400).toString(),modifiedAt=now.minusSeconds(86400).toString())
        val s=base.copy(accounts=base.accounts.map{it.copy(initialCents=1000,initialDate="2026-10-01")},transactions=listOf(tx))
        val args=buildJsonObject{put("accountId","wechat");put("actualCents",800L);put("date","2026-10-06")}
        val once=DomainEngine.apply(s,Operation("cal-first","account.calibrate",args=args),now)
        val twice=DomainEngine.apply(once.state,Operation("cal-second","account.calibrate",args=args),now.plusSeconds(1))
        assertEquals("success",twice.result.status);assertEquals(800L,CoreFunctions.balance(twice.state,"wechat"))
        assertEquals(0L,twice.state.transactions.last().amountCents)
    }
    @Test fun sameDayLateConsumptionExcludedButNextDayBackfillIncludedInCutoffBalance() {
        val base=InitialData.create()
        val snapshot=now.minusSeconds(86400)
        val s=base.copy(accounts=base.accounts.map{it.copy(initialCents=1000,initialDate="2026-10-01")},
            reconciliations=listOf(Reconciliation("rec","wechat",1000,1000,0,snapshot.toString())))
        val late=Transaction("late","expense","wechat",100,"2026-10-06",createdAt=snapshot.plusSeconds(3600).toString(),modifiedAt=snapshot.plusSeconds(3600).toString())
        assertEquals(1000L,CoreFunctions.balanceAt(s.copy(transactions=listOf(late)),"wechat",snapshot))
        val inserted=DomainEngine.apply(s,Operation("backfill","transaction.create",args=buildJsonObject{put("accountId","wechat");put("amountCents",100L);put("date","2026-10-06")}),now)
        assertEquals(900L,CoreFunctions.balanceAt(inserted.state,"wechat",snapshot));assertTrue(inserted.state.reconciliations.single().stale)
    }
}
