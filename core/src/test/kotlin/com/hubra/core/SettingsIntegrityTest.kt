package com.hubra.core

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class SettingsIntegrityTest {
    private val now=Instant.parse("2026-10-07T14:00:00Z")
    @Test fun settingsCannotInvalidateExistingWeeksPeriodsOrExceptionPeriods() {
        val s=InitialData.create().copy(courseExceptions=listOf(CourseException("exception","matrix-wed","2026-10-07",startPeriod=11,endPeriod=11)))
        val args=listOf(buildJsonObject{put("semesterWeeks",6)},
            buildJsonObject{put("periods",HubraJson.json.encodeToJsonElement(s.settings.periods.filterNot{it.number==8}))},
            buildJsonObject{put("periods",HubraJson.json.encodeToJsonElement(s.settings.periods.filterNot{it.number==11}))})
        args.forEachIndexed { i,settings ->
            val t=DomainEngine.apply(s,Operation("invalid-settings-$i","settings.update",args=settings),now)
            assertEquals("failed",t.result.status);assertEquals(s.settings,t.state.settings)
        }
    }
    @Test fun changingValidClassTimesPreservesRulesAndAllowsSave() {
        val s=InitialData.create()
        val updated=s.settings.periods.map{if(it.number==1)it.copy(start="08:05",end="08:55") else it}
        val t=DomainEngine.apply(s,Operation("valid-times","settings.update",args=buildJsonObject{put("periods",HubraJson.json.encodeToJsonElement(updated))}),now)
        assertEquals("success",t.result.status);assertEquals("08:05",t.state.settings.periods.first().start);assertEquals(s.courseRules,t.state.courseRules)
    }
    @Test fun historicalRulesAndExceptionsOfDeletedCourseDoNotRestrictCurrentSettings() {
        val base=InitialData.create()
        val s=base.copy(courses=base.courses.map{if(it.id=="matrix")it.copy(deleted=true) else it},
            courseExceptions=listOf(CourseException("historical-exception","matrix-wed","2026-10-07",startPeriod=11,endPeriod=11)))
        val updated=s.settings.periods.filterNot{it.number==11}
        val t=DomainEngine.apply(s,Operation("inactive-course-settings","settings.update",args=buildJsonObject{put("periods",HubraJson.json.encodeToJsonElement(updated))}),now)
        assertEquals("success",t.result.status);assertEquals(s.courseRules,t.state.courseRules);assertEquals(s.courseExceptions,t.state.courseExceptions)
    }
}
