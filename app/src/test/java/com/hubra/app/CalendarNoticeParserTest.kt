package com.hubra.app

import com.hubra.app.ai.CalendarNoticeParser
import com.hubra.core.InitialData
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.*

class CalendarNoticeParserTest {
    private val notice = "根据校办2026年度节假日放假通知及校历安排，中秋节：9月25日（周五）至27日（周日）放假，共3天。国庆节：10月1日（周四）至7日（周三）放假调休，共7天。9月20日（周日）、10月10日（周六）分别上10月6日（周二）、10月7日（周三）的课。根据以上信息修改"
    @Test fun fullSchoolNoticePreservesSourceAndDestinationDatesIncludingPastDates() {
        val op = CalendarNoticeParser.parse(notice, InitialData.create(), LocalDate.parse("2026-10-08"), "request")!!
        assertEquals("course.calendar.adjust", op.kind)
        val holidays = op.args["holidays"]!!.jsonArray
        assertEquals("2026-09-27", holidays[0].jsonObject["endDate"]!!.jsonPrimitive.content)
        assertEquals("2026-10-07", holidays[1].jsonObject["endDate"]!!.jsonPrimitive.content)
        val makeups = op.args["makeups"]!!.jsonArray
        assertEquals("2026-10-06", makeups[0].jsonObject["sourceDate"]!!.jsonPrimitive.content)
        assertEquals("2026-09-20", makeups[0].jsonObject["targetDate"]!!.jsonPrimitive.content)
        assertEquals("2026-10-07", makeups[1].jsonObject["sourceDate"]!!.jsonPrimitive.content)
        assertEquals("2026-10-10", makeups[1].jsonObject["targetDate"]!!.jsonPrimitive.content)
    }
    @Test fun pastedNoticeWithoutModificationInstructionDoesNotWrite() {
        assertNull(CalendarNoticeParser.parse(notice.substringBefore("根据以上信息修改"), InitialData.create(), LocalDate.parse("2026-10-08"), "request"))
    }
    @Test fun mismatchedMakeupListsCannotPartiallyCancelTheHoliday() {
        val malformed = "2026年10月1日至7日放假；9月20日、10月10日分别上10月6日的课。根据以上修改"
        assertNull(CalendarNoticeParser.parse(malformed, InitialData.create(), LocalDate.parse("2026-10-08"), "request"))
    }
    @Test fun oneDayMakeupUsesExplicitDatesRatherThanTodaysDate() {
        val op = CalendarNoticeParser.parse("10月10日上10月7日的课，请调整", InitialData.create(), LocalDate.parse("2026-10-08"), "request")!!
        assertEquals("2026-10-10", op.args["makeups"]!!.jsonArray[0].jsonObject["targetDate"]!!.jsonPrimitive.content)
    }
    @Test fun unrelatedTaskAndInvalidDateFallBackForClarification() {
        assertNull(CalendarNoticeParser.parse("10月10日提醒我修改作业", InitialData.create(), LocalDate.parse("2026-10-08"), "request"))
        assertNull(CalendarNoticeParser.parse("2026年2月30日至3月2日放假，根据以上修改", InitialData.create(), LocalDate.parse("2026-10-08"), "request"))
    }
    @Test fun incompleteHolidayAndMakeupClausesCannotProducePartialEdits() {
        for(text in listOf("请修改课表：10月1日至7日放假，10月10日补周三的课", "请修改课表：9月25日至27日、10月1日至7日放假", "请修改课表：10月1日至7日放假，明天提醒我取快递"))
            assertNull(CalendarNoticeParser.parse(text,InitialData.create(),LocalDate.parse("2026-10-08"),"request"),text)
    }
    @Test fun negativeInstructionCannotBeInterpretedAsAuthorization() {
        assertNull(CalendarNoticeParser.parse("不要修改课表：10月1日至7日放假",InitialData.create(),LocalDate.parse("2026-10-08"),"request"))
    }
}
