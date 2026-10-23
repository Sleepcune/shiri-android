package com.hubra.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*
import kotlinx.serialization.json.*

class SemesterTest {
    private val now=Instant.parse("2026-10-07T14:00:00Z")
    private fun create(s:AppState,id:String="spring")=DomainEngine.apply(s,Operation("new-$id","semester.create",id,args=buildJsonObject{put("name","2027 春季");put("startDate","2027-02-22");put("weekCount",20)}),now)
    @Test fun creatingNewSemesterSelectsEmptyScheduleAndPreservesSharedPeriods() {
        val old=InitialData.create();val t=create(old)
        assertEquals("success",t.result.status);assertEquals("spring",t.state.settings.activeSemesterId)
        assertEquals("2027-02-22",t.state.settings.semesterStart);assertEquals(old.settings.periods,t.state.settings.periods)
        assertEquals(old.courses,t.state.courses);assertTrue(CoreFunctions.currentCourses(t.state).isEmpty())
        assertTrue(CoreFunctions.occurrences(t.state,LocalDate.parse("2026-10-07")).isEmpty())
    }
    @Test fun switchingAndUndoRestoreHistoricalCoursesAndSettingsProjection() {
        val old=InitialData.create();val spring=create(old)
        val switched=DomainEngine.apply(spring.state,Operation("switch-back","semester.switch",args=buildJsonObject{put("semesterId","default")}),now)
        assertEquals("success",switched.result.status);assertEquals("2026-08-24",switched.state.settings.semesterStart)
        assertEquals(CoreFunctions.occurrences(old,LocalDate.parse("2026-10-07")),CoreFunctions.occurrences(switched.state,LocalDate.parse("2026-10-07")))
        val undone=DomainEngine.apply(switched.state,Operation("undo-switch","undo",args=buildJsonObject{put("operationId","switch-back")}),now)
        assertEquals("success",undone.result.status);assertEquals("spring",undone.state.settings.activeSemesterId);assertTrue(CoreFunctions.currentCourses(undone.state).isEmpty())
    }
    @Test fun identicalImportedIdsAcrossSemestersAreRemappedWithoutChangingOldCourse() {
        val old=InitialData.create();val selected=create(old).state
        val matrix=old.courses.single{it.id=="matrix"}
        val rule=old.courseRules.single{it.id=="matrix-wed"}.copy(weeks=listOf(3))
        val exception=CourseException("shared-exception","matrix-wed","2027-03-10",room="新校区B301")
        val args=buildJsonObject{put("courses",HubraJson.json.encodeToJsonElement(listOf(matrix)));put("rules",HubraJson.json.encodeToJsonElement(listOf(rule)));put("exceptions",HubraJson.json.encodeToJsonElement(listOf(exception)))}
        val imported=DomainEngine.apply(selected,Operation("spring-import","course.import",args=args),now)
        assertEquals("success",imported.result.status)
        val current=CoreFunctions.currentCourses(imported.state).single()
        assertNotEquals("matrix",current.id);assertEquals("spring",current.semesterId)
        assertEquals(matrix,imported.state.courses.single{it.id=="matrix"})
        val currentRule=imported.state.courseRules.single{it.courseId==current.id};assertNotEquals("matrix-wed",currentRule.id)
        assertEquals(currentRule.id,imported.state.courseExceptions.single().ruleId)
        val occurrences=CoreFunctions.occurrences(imported.state,LocalDate.parse("2027-03-10"));assertEquals(1,occurrences.size);assertEquals("新校区B301",occurrences.single().room)
        val repeated=DomainEngine.apply(imported.state,Operation("spring-import-repeat","course.import",args=args),now)
        assertEquals("success",repeated.result.status);assertEquals(1,CoreFunctions.currentCourses(repeated.state).size)
    }
    @Test fun oldStateMigrationPreservesCoursesAndOriginalSemesterSettings() {
        val old=InitialData.create().copy(semesters=emptyList(),settings=InitialData.create().settings.copy(semesterStart="2026-09-07",semesterWeeks=22))
        val migrated=CoreFunctions.normalizeSemesters(old)
        assertEquals(Semester("default","2026 秋季","2026-09-07",22),migrated.semesters.single())
        assertEquals(old.courses,migrated.courses);assertEquals(old.settings.periods,migrated.settings.periods)
        assertEquals(migrated,CoreFunctions.normalizeSemesters(migrated))
    }
    @Test fun semesterSettingsSyncCurrentSemesterAndSharedPeriodsValidateHistoricalReferences() {
        val spring=create(InitialData.create()).state
        val changed=DomainEngine.apply(spring,Operation("spring-settings","settings.update",args=buildJsonObject{put("semesterWeeks",16);put("semesterStart","2027-03-01")}),now)
        assertEquals("success",changed.result.status)
        assertEquals(16,changed.state.semesters.single{it.id=="spring"}.weekCount)
        assertEquals("2027-03-01",changed.state.semesters.single{it.id=="spring"}.startDate)
        val invalid=DomainEngine.apply(spring,Operation("period-delete","settings.update",args=buildJsonObject{put("periods",HubraJson.json.encodeToJsonElement(spring.settings.periods.filterNot{it.number==8}))}),now)
        assertEquals("failed",invalid.result.status);assertEquals(spring.settings.periods,invalid.state.settings.periods)
        assertNull(CoreFunctions.nextCourse(spring,now))
    }
    @Test fun cachedCourseWriteCannotApplyAfterAnotherSemesterWasSelected() {
        val spring=create(InitialData.create()).state
        val t=DomainEngine.apply(spring,Operation("stale-course","course.create",args=buildJsonObject{put("name","不应创建");put("semesterId","default")}),now)
        assertEquals("conflict",t.result.status);assertTrue(CoreFunctions.currentCourses(t.state).isEmpty())
    }
    @Test fun globallyLinkedTaskUsesItsOwnSemesterAfterSwitching() {
        val old=InitialData.create().copy(tasks=listOf(Task("linked","问老师",courseRuleId="matrix-wed",courseDate="2026-10-07",offsetMinutes=30,reminder=true)))
        val spring=create(old).state
        val task=CoreFunctions.tasksForDate(spring,LocalDate.parse("2026-10-07")).single()
        assertEquals("18:30",task.time);assertEquals("2026-10-07",task.date)
    }
    @Test fun semesterCreationCannotBeUndoneAfterCoursesAreAddedAndSwitchUndoDetectsSettingsChanges() {
        val created=create(InitialData.create())
        val course=DomainEngine.apply(created.state,Operation("add-spring-course","course.create",args=buildJsonObject{put("name","春季课程")}),now)
        val undo=DomainEngine.apply(course.state,Operation("undo-created-term","undo",args=buildJsonObject{put("operationId","new-spring")}),now)
        assertEquals("conflict",undo.result.status)
        val switched=DomainEngine.apply(created.state,Operation("switch-to-default","semester.switch",args=buildJsonObject{put("semesterId","default")}),now)
        val settings=DomainEngine.apply(switched.state,Operation("change-times-after-switch","settings.update",args=buildJsonObject{put("courseReminderMinutes",30)}),now)
        val switchUndo=DomainEngine.apply(settings.state,Operation("undo-changed-switch","undo",args=buildJsonObject{put("operationId","switch-to-default")}),now)
        assertEquals("conflict",switchUndo.result.status)
    }
    @Test fun staleSettingsCaptureCannotEditAnotherSelectedSemester() {
        val s=create(InitialData.create()).state
        val t=DomainEngine.apply(s,Operation("stale-settings","settings.update",args=buildJsonObject{put("activeSemesterId","default");put("semesterWeeks",18)}),now)
        assertEquals("conflict",t.result.status);assertEquals(s.settings,t.state.settings)
    }
    @Test fun historicalRulesAndExceptionsCannotBeReassignedIntoActiveSemester() {
        val base=InitialData.create().copy(courseExceptions=listOf(CourseException("old-exception","matrix-wed","2026-10-07",room="原教室")))
        var s=create(base).state
        val newCourse=DomainEngine.apply(s,Operation("fresh-spring-course","course.create","fresh-spring-course-id",args=buildJsonObject{put("name","春季矩阵")}),now);s=newCourse.state
        val newRule=DomainEngine.apply(s,Operation("fresh-spring-rule","courseRule.create","spring-rule",args=buildJsonObject{put("courseId",newCourse.result.targetId!!);put("dayOfWeek",3);put("startPeriod",7);put("endPeriod",8);put("weeks",JsonArray(listOf(JsonPrimitive(3))))}),now);s=newRule.state
        val ruleWrite=DomainEngine.apply(s,Operation("reassign-old-rule","courseRule.update","matrix-wed",1,buildJsonObject{put("courseId",newCourse.result.targetId!!);put("semesterId","spring")}),now)
        assertEquals("conflict",ruleWrite.result.status);assertEquals("matrix",ruleWrite.state.courseRules.single{it.id=="matrix-wed"}.courseId)
        val exceptionWrite=DomainEngine.apply(s,Operation("reassign-old-exception","courseException.update","old-exception",1,buildJsonObject{put("ruleId","spring-rule");put("date","2027-03-10");put("semesterId","spring")}),now)
        assertEquals("conflict",exceptionWrite.result.status);assertEquals("matrix-wed",exceptionWrite.state.courseExceptions.single().ruleId)
    }
}
