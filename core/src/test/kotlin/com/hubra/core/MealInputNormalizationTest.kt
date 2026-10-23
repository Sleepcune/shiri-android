package com.hubra.core

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class MealInputNormalizationTest {
    private val now=Instant.parse("2026-10-08T04:00:00Z")
    @Test fun dishesArrayNormalizesWithoutCreatingOrChangingMoney() {
        val recorded=DomainEngine.apply(InitialData.create(),Operation("lunch-bill","transaction.create",args=buildJsonObject {
            put("note","午餐烤鸭饭");put("amountCents",1500);put("category","餐饮")
        }),now)
        val result=DomainEngine.apply(recorded.state,Operation("lunch-meal","meal.create",args=buildJsonObject {
            put("name","午餐");put("foods",buildJsonArray{add("烤鸭");add("米饭")});put("transactionId",recorded.result.targetId!!)
        }),now)
        assertEquals("success",result.result.status)
        assertEquals("烤鸭、米饭",result.state.meals.single().foods)
        assertEquals(recorded.state.transactions,result.state.transactions)
        assertEquals(1,result.state.transactions.size);assertEquals(1500L,result.state.transactions.single().amountCents)
        val repeated=DomainEngine.apply(result.state,Operation("lunch-meal","meal.create",args=buildJsonObject{put("foods","其他")}),now)
        assertEquals(result.state,repeated.state)
    }
    @Test fun updateAllowsTextAndArrayButMalformedDishObjectsAskForCorrection() {
        val initial=InitialData.create().copy(meals=listOf(Meal("m","2026-10-08",foods="面条")))
        val edited=DomainEngine.apply(initial,Operation("edit-array","meal.update","m",1,buildJsonObject {
            put("foods",buildJsonArray{add(" 烤鸭 ");add("");add("米饭")})
        }),now)
        assertEquals("success",edited.result.status);assertEquals("烤鸭、米饭",edited.state.meals.single().foods)
        val invalid=listOf(buildJsonObject{put("name","烤鸭")},buildJsonArray{add(buildJsonObject{put("name","烤鸭")})},buildJsonArray{add("烤鸭");add(15)},JsonPrimitive(true))
        invalid.forEachIndexed{index,foods->
            val result=DomainEngine.apply(initial,Operation("bad-food-$index","meal.update","m",1,buildJsonObject{put("foods",foods)}),now)
            assertEquals("needs_clarification",result.result.status);assertTrue(result.result.message.contains("菜品"))
            assertFalse(result.result.message.contains("JsonArray"));assertEquals(initial.meals,result.state.meals)
        }
    }
    @Test fun invalidStringAndStringListFieldsReturnChineseErrorsInsteadOfRawJsonTypes() {
        listOf(buildJsonObject{put("foods","米饭");put("portion",buildJsonArray{add("一碗")})},
            buildJsonObject{put("foods","米饭");put("attachmentIds",buildJsonArray{add(buildJsonObject{put("id","p")})})},
            buildJsonObject{put("foods","米饭");put("date",20261008)}).forEachIndexed{index,args->
            val result=DomainEngine.apply(InitialData.create(),Operation("bad-meal-field-$index","meal.create",args=args),now)
            assertEquals("needs_clarification",result.result.status)
            assertFalse(result.result.message.contains("JsonPrimitive"));assertTrue(result.state.meals.isEmpty())
        }
    }
    @Test fun summariesTolerateLegacyArraysAndMalformedNestedElements() {
        val old="""{"id":"m","foods":["烤鸭","米饭"],"name":[{"oops":true}],"date":"2026-10-08","deleted":false}"""
        val new="""{"id":"m","foods":["米饭",{"oops":true}],"name":[{"oops":true}],"date":"2026-10-08","deleted":false}"""
        val record=OperationRecord("old-malformed","meal.update",OperationResult("success","已修改",targetId="m",operationId="old-malformed"),now.toString(),listOf(EntityChange("meals","m",old,new)))
        val summary=OperationSummaries.describe(InitialData.create(),record)
        assertEquals("success",summary.status)
        assertTrue(summary.fields.any{it.label=="菜品"})
    }
}
