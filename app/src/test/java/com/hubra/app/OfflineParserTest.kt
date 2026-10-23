package com.hubra.app

import com.hubra.app.ai.OfflineParser
import com.hubra.core.InitialData
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.*

class OfflineParserTest {
    private val date=LocalDate.parse("2026-10-07")
    @Test fun splitsDistinctExpensesWithoutFloatingPointRounding() {
        val ops=OfflineParser.parse("早饭6元，午饭13.01元，公交2元",InitialData.create(),date,"request")
        assertEquals(listOf(600L,1301L,200L),ops.map { it.args["amountCents"]!!.jsonPrimitive.long })
        assertEquals(listOf("campus","campus","transport"),ops.map { it.args["accountId"]!!.jsonPrimitive.content })
    }
    @Test fun doesNotTreatQuantityAsMoney() { assertTrue(OfflineParser.parse("买水3瓶",InitialData.create(),date,"r").isEmpty()) }
    @Test fun doesNotSilentlyInvertNegativeAmount() { assertTrue(OfflineParser.parse("午饭-10元",InitialData.create(),date,"r").isEmpty()) }
    @Test fun futureIntentIsNotActualExpense() { assertTrue(OfflineParser.parse("明天午饭13元",InitialData.create(),date,"r").isEmpty()) }
    @Test fun incomeIsNotRecordedAsExpense() { assertTrue(OfflineParser.parse("收到工资3000元",InitialData.create(),date,"r").isEmpty()) }
    @Test fun ambiguousCorrectionWaitsForAi() { assertTrue(OfflineParser.parse("午饭改成14元",InitialData.create(),date,"r").isEmpty()) }
}
