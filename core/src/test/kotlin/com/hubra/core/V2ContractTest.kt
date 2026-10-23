package com.hubra.core

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class V2ContractTest {
    private val now = Instant.parse("2026-10-08T02:00:00Z")
    @Test fun freshStateUsesVersionThree() { assertEquals(3, InitialData.create().schemaVersion) }
    @Test fun newSemesterInheritsAppearanceAndKeepsPeriods() {
        val original = InitialData.create()
        val settings = HubraJson.json.decodeFromJsonElement<Settings>(JsonObject(HubraJson.json.encodeToJsonElement(original.settings).jsonObject +
            ("timetableAppearances" to buildJsonObject { put("default", buildJsonObject { put("fontSizeSp", 16); put("showWeekends", false) }) })))
        val created = DomainEngine.apply(original.copy(settings=settings), Operation("new-v2-term", "semester.create", "spring", args=buildJsonObject {
            put("name", "春季"); put("startDate", "2027-02-22")
        }), now)
        assertEquals("success", created.result.status)
        assertEquals(original.settings.periods, created.state.settings.periods)
        val appearance=HubraJson.json.encodeToJsonElement(created.state.settings).jsonObject["timetableAppearances"]?.jsonObject
        assertEquals(16, appearance?.get("spring")?.jsonObject?.get("fontSizeSp")?.jsonPrimitive?.int)
    }
}
