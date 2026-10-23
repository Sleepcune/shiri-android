package com.hubra.core

import java.time.*
import kotlin.test.*
import kotlinx.serialization.json.*

class CalendarAdjustmentTest {
    private val now=Instant.parse("2026-10-08T02:00:00Z")
    private fun notification(id:String="holiday")=Operation(id,"course.calendar.adjust",args=buildJsonObject {
        put("holidays",buildJsonArray {
            add(buildJsonObject {put("startDate","2026-09-25");put("endDate","2026-09-27")})
            add(buildJsonObject {put("startDate","2026-10-01");put("endDate","2026-10-07")})
        })
        put("makeups",buildJsonArray {
            add(buildJsonObject {put("sourceDate","2026-10-06");put("targetDate","2026-09-20")})
            add(buildJsonObject {put("sourceDate","2026-10-07");put("targetDate","2026-10-10")})
        })
    })
    @Test fun notificationCancelsHolidaysAndPlacesPastAndFutureMakeupsAtomically() {
        val original=InitialData.create()
        val result=DomainEngine.apply(original,notification(),now)
        assertEquals("success",result.result.status)
        assertEquals(original.courseRules,result.state.courseRules)
        assertEquals(1,result.state.operations.size)
        assertEquals(11,result.state.operations.single().changes.size)
        assertEquals(1,CoreFunctions.occurrences(result.state,LocalDate.parse("2026-09-20")).size)
        assertEquals(3,CoreFunctions.occurrences(result.state,LocalDate.parse("2026-10-10")).size)
        listOf("2026-09-25","2026-10-01","2026-10-02","2026-10-05","2026-10-06","2026-10-07").forEach{date->assertTrue(CoreFunctions.occurrences(result.state,LocalDate.parse(date)).isEmpty())}
        assertTrue(result.result.message.contains("2026-09-20"));assertTrue(result.result.message.contains("2026-10-07"));assertFalse(result.result.message=="操作已保存")
    }
    @Test fun canceledSourceIsReadFromOriginalRulesAndWrongPreviousAdjustmentIsRepaired() {
        val base=InitialData.create().copy(courseExceptions=listOf(CourseException("wrong","functional-tue","2026-10-06",cancelled=true,movedDate="2026-10-11",startPeriod=1,endPeriod=1,room="错误"),
            CourseException("cancel-matrix","matrix-wed","2026-10-07",cancelled=true)))
        val result=DomainEngine.apply(base,notification(),now)
        assertEquals("success",result.result.status)
        val repaired=result.state.courseExceptions.single{it.id=="wrong"}
        assertEquals("2026-09-20",repaired.movedDate);assertFalse(repaired.cancelled)
        assertNull(repaired.startPeriod);assertNull(repaired.room);assertEquals(2L,repaired.version)
        assertEquals(3,CoreFunctions.occurrences(result.state,LocalDate.parse("2026-10-10")).size)
        assertTrue(CoreFunctions.occurrences(result.state,LocalDate.parse("2026-10-11")).isEmpty())
    }
    @Test fun repeatedNotificationDoesNotRewriteVersionsOrDuplicateAndOriginalCanUndo() {
        val original=InitialData.create()
        val first=DomainEngine.apply(original,notification(),now)
        val second=DomainEngine.apply(first.state,notification("repeat"),now)
        assertEquals(first.state.courseExceptions,second.state.courseExceptions)
        assertTrue(second.state.operations.last().changes.isEmpty())
        assertTrue(second.result.message.contains("已安排"))
        val undone=DomainEngine.apply(second.state,Operation("undo-first","undo",args=buildJsonObject{put("operationId","holiday")}),now)
        assertEquals("success",undone.result.status)
        assertEquals(3,CoreFunctions.occurrences(undone.state,LocalDate.parse("2026-10-07")).size)
        assertTrue(CoreFunctions.occurrences(undone.state,LocalDate.parse("2026-10-10")).isEmpty())
    }
    @Test fun laterChangedExceptionBlocksWholeBatchUndo() {
        val first=DomainEngine.apply(InitialData.create(),notification(),now)
        val moved=first.state.courseExceptions.first{it.date=="2026-10-07"}
        val edited=DomainEngine.apply(first.state,Operation("later","courseException.update",moved.id,moved.version,buildJsonObject{put("room","B301")}),now)
        assertEquals("success",edited.result.status)
        val undo=DomainEngine.apply(edited.state,Operation("unsafe-undo","undo",args=buildJsonObject{put("operationId","holiday")}),now)
        assertEquals("conflict",undo.result.status);assertEquals(edited.state.courseExceptions,undo.state.courseExceptions)
    }
    @Test fun collisionsAreKeptAndReportedRatherThanDeletingTargetClasses() {
        val original=InitialData.create()
        val extra=CourseRule("extra-sunday","ai",7,5,6,listOf(4),"目标教室")
        val result=DomainEngine.apply(original.copy(courseRules=original.courseRules+extra),notification(),now)
        assertEquals("success",result.result.status)
        assertEquals(2,CoreFunctions.occurrences(result.state,LocalDate.parse("2026-09-20")).size)
        assertTrue(result.result.message.contains("冲突"));assertTrue(result.state.courseRules.any{it==extra})
    }
    @Test fun baseDayTemplateUsesKnownWeekOnlyEvenWhenAllSourceCoursesCanceled() {
        val original=InitialData.create()
        val canceled=original.copy(courseExceptions=CoreFunctions.occurrences(original,LocalDate.parse("2026-10-07")).map{CourseException("cancel-${it.ruleId}",it.ruleId,it.date,cancelled=true)})
        val result=DomainEngine.apply(canceled,Operation("template","course.day.copy",args=buildJsonObject{put("sourceDate","2026-10-07");put("targetDate","2026-10-10")}),now)
        assertEquals("success",result.result.status);assertEquals(3,CoreFunctions.occurrences(result.state,LocalDate.parse("2026-10-10")).size)
        val nextWeek=DomainEngine.apply(original,Operation("next-week","course.day.copy",args=buildJsonObject{put("sourceDate","2026-10-14");put("targetDate","2026-10-17")}),now)
        assertEquals("success",nextWeek.result.status)
        assertEquals(2,CoreFunctions.occurrences(nextWeek.state,LocalDate.parse("2026-10-17")).size)
    }
    @Test fun dayCopyKeepsSourceAndDuplicateCopyDoesNotAddRules() {
        val original=InitialData.create()
        val operation=Operation("genuine-copy","course.day.copy",args=buildJsonObject{put("sourceDate","2026-10-07");put("targetDate","2026-10-10")})
        val copied=DomainEngine.apply(original,operation,now)
        assertEquals("success",copied.result.status)
        assertEquals(3,CoreFunctions.occurrences(copied.state,LocalDate.parse("2026-10-07")).size)
        assertEquals(3,CoreFunctions.occurrences(copied.state,LocalDate.parse("2026-10-10")).size)
        assertTrue(copied.state.courseExceptions.isEmpty())
        assertTrue(copied.state.courseRules.containsAll(original.courseRules))
        val repeated=DomainEngine.apply(copied.state,operation.copy(id="same-copy-again"),now)
        assertEquals(copied.state.courseRules,repeated.state.courseRules)
        assertTrue(repeated.state.operations.last().changes.isEmpty())
        val undo=DomainEngine.apply(repeated.state,Operation("undo-copy","undo",args=buildJsonObject{put("operationId","genuine-copy")}),now)
        assertEquals("success",undo.result.status)
        assertEquals(3,CoreFunctions.occurrences(undo.state,LocalDate.parse("2026-10-07")).size)
        assertTrue(CoreFunctions.occurrences(undo.state,LocalDate.parse("2026-10-10")).isEmpty())
    }
    @Test fun replayCopyAfterCancellationPreservesLaterExceptionAndRuleVersion() {
        val operation=Operation("copy-before-cancel","course.day.copy",args=buildJsonObject{put("sourceDate","2026-10-07");put("targetDate","2026-10-10")})
        val copied=DomainEngine.apply(InitialData.create(),operation,now)
        val rule=copied.state.courseRules.first{it.id.startsWith("calendar-copy-")}
        val canceled=DomainEngine.apply(copied.state,Operation("cancel-copy","course.exception",args=buildJsonObject{put("ruleId",rule.id);put("date","2026-10-10");put("cancelled",true)}),now)
        assertEquals("success",canceled.result.status)
        val replay=DomainEngine.apply(canceled.state,operation.copy(id="copy-replayed-after-cancel"),now)
        assertEquals("success",replay.result.status)
        assertEquals(canceled.state.courseRules,replay.state.courseRules)
        assertEquals(canceled.state.courseExceptions,replay.state.courseExceptions)
        assertTrue(replay.state.operations.last().changes.isEmpty())
        assertEquals(2,CoreFunctions.occurrences(replay.state,LocalDate.parse("2026-10-10")).size)
        assertTrue(replay.result.message.contains("后续调整"));assertTrue(replay.result.message.contains("停课"))
    }
    @Test fun contradictoryPairsOrWrongSemesterLeaveEveryExceptionUntouched() {
        val original=InitialData.create()
        val bad=Operation("bad","course.calendar.adjust",args=buildJsonObject {put("makeups",buildJsonArray {
            add(buildJsonObject {put("sourceDate","2026-10-07");put("targetDate","2026-10-10")})
            add(buildJsonObject {put("sourceDate","2026-10-07");put("targetDate","2026-10-11")})
        })})
        val result=DomainEngine.apply(original,bad,now)
        assertEquals("failed",result.result.status);assertEquals(original.courseExceptions,result.state.courseExceptions)
        val wrong=DomainEngine.apply(original,notification("wrong-term").copy(args=JsonObject(notification().args+("semesterId" to JsonPrimitive("another")))),now)
        assertEquals("conflict",wrong.result.status);assertEquals(original.courseExceptions,wrong.state.courseExceptions)
    }
    @Test fun restoredSettingsCannotReattachDeletedOrPendingBackground() {
        val original=InitialData.create().copy(attachments=listOf(Attachment("photo","photo.jpg")),settings=InitialData.create().settings.copy(timetableAppearances=mapOf("default" to TimetableAppearance(backgroundAttachmentId="photo"))))
        val changed=DomainEngine.apply(original,Operation("remove-bg","settings.update",args=buildJsonObject{put("timetableAppearances",HubraJson.json.encodeToJsonElement(mapOf("default" to TimetableAppearance())))}),now)
        listOf(original.attachments.single().copy(deletionPending=true),original.attachments.single().copy(deletedAt=now.toString())).forEachIndexed { index,image->
            val state=changed.state.copy(attachments=listOf(image))
            val undone=DomainEngine.apply(state,Operation("undo-bg-$index","undo",args=buildJsonObject{put("operationId","remove-bg")}),now)
            assertEquals("conflict",undone.result.status);assertNull(undone.state.settings.timetableAppearances["default"]?.backgroundAttachmentId)
        }
    }
}
