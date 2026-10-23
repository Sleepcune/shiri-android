package com.hubra.app

import com.hubra.app.ai.ApiProtocol
import com.hubra.core.ApiProfile
import kotlin.test.*
import kotlinx.serialization.json.*

class ApiProtocolTest {
    @Test fun malformedEnvelopeExplainsTheFieldInsteadOfLeakingJsonArrayException() {
        val failure=assertFails { ApiProtocol.parseActions("""{"reply":["已保存"],"actions":[]}""","request") }
        assertEquals("AI 回复说明应为文字",failure.message)
        assertFails { ApiProtocol.parseActions("""{"actions":["meal.create"]}""","request") }
    }
    @Test fun screenshotCourseImportCannotSmuggleCancellations() {
        val args=buildJsonObject { put("courses",JsonArray(emptyList()));put("rules",JsonArray(emptyList()));put("exceptions",buildJsonArray { add(buildJsonObject { put("cancelled",true) }) }) }
        assertFalse("exceptions" in ApiProtocol.argumentsInMode("course","course.import",args))
        assertTrue("exceptions" in ApiProtocol.argumentsInMode("chat","course.import",args))
    }
    @Test fun invalidVersionCannotSilentlyBecomeAnUnversionedUpdate() {
        for(version in listOf("1.5","\"2\"","true","0","-1"))assertFails {
            ApiProtocol.parseActions("""{"actions":[{"kind":"task.update","targetId":"t","expectedVersion":$version,"args":{}}]}""","request")
        }
        assertEquals(2L,ApiProtocol.parseActions("""{"actions":[{"kind":"task.update","expectedVersion":2,"args":{}}]}""","request").single().expectedVersion)
    }
    @Test fun screenshotModesCannotDeleteOrChangeExistingRecords() {
        for(mode in listOf("course","meal"))for(kind in listOf("transaction.create","course.delete","courseRule.delete","course.exception","course.calendar.adjust","course.day.copy","meal.delete","meal.update","task.complete","settings.update"))
            assertFalse(ApiProtocol.permitsInMode(mode,kind),"$mode must reject $kind")
        assertTrue(ApiProtocol.permitsInMode("course","course.import"))
        assertTrue(ApiProtocol.permitsInMode("meal","meal.create"))
        assertTrue(ApiProtocol.permitsInMode("chat","meal.update"))
    }
    @Test fun normalizesCompatibleEndpoints() {
        assertEquals("https://monk.party/v1/chat/completions", ApiProtocol.endpoint("https://monk.party/v1/"))
        assertEquals("https://monk.party/v1/chat/completions", ApiProtocol.endpoint("https://monk.party/v1/chat/completions"))
    }
    @Test fun editingProfileMetadataPreservesSuccessfulCapabilityDetection() {
        val profile = ApiProfile("p", "服务", "https://example.com/v1", "text", "image", supportsImages = true, supportsTools = true, supportsJson = true, testedAt = "2026-10-23T00:00:00Z")
        assertEquals(profile, ApiProtocol.capabilitiesFor(profile, profile.baseUrl, profile.textModel, profile.imageModel, keyChanged = false))
        assertNull(ApiProtocol.capabilitiesFor(profile, profile.baseUrl, profile.textModel, profile.imageModel, keyChanged = true))
        assertNull(ApiProtocol.capabilitiesFor(profile, "https://other.example/v1", profile.textModel, profile.imageModel, keyChanged = false))
        assertNull(ApiProtocol.capabilitiesFor(profile, profile.baseUrl, "new-text", profile.imageModel, keyChanged = false))
    }
    @Test fun rejectsAnActionOutsideTheAllowList() {
        assertFails { ApiProtocol.parseActions("""{"actions":[{"kind":"execute_sql","args":{"sql":"delete from records"}}]}""", "request") }
    }
}

