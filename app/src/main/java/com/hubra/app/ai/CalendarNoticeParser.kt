package com.hubra.app.ai

import com.hubra.core.AppState
import com.hubra.core.Operation
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Only complete, explicit calendar edits are handled locally; ambiguous notices go to the AI. */
object CalendarNoticeParser {
    private const val DATE = "(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日"
    fun parse(text: String, state: AppState, today: LocalDate, requestId: String): Operation? = runCatching {
        val requested=Regex("(?:请|帮我|麻烦)[^。！？\\n]{0,50}(?:修改|调整|安排|应用|执行|设置)|根据(?:以上|上述|这些|这份)[^。！？\\n]{0,30}(?:修改|调整|安排|应用|执行|设置)|^(?:修改|调整|设置)(?:课表|课程|调休|：|:)").containsMatchIn(text)
        if (!requested || Regex("(?:不要|别|暂不|先不|不必|无需|不用)\\s*(?:修改|调整|安排|应用|执行|设置)|仅查询|只查询").containsMatchIn(text)) return null
        if(Regex("待办|提醒我|记账|记一笔|微信|花了|餐食").containsMatchIn(text)) return null
        val clean = text.replace(Regex("[（(](?:周|星期)[一二三四五六日天][）)]"), "")
        val year = Regex("(\\d{4})年").find(clean)?.groupValues?.get(1)?.toInt() ?: today.year
        fun date(y: String, m: String, d: String) = LocalDate.of(y.toIntOrNull() ?: year, m.toInt(), d.toInt())
        val holidayPattern=Regex("$DATE\\s*(?:至|到|—|-)\\s*(?:(\\d{4})年)?(?:(\\d{1,2})月)?(\\d{1,2})日\\s*(?:放假|停课)")
        val holidays = holidayPattern.findAll(clean).map { match ->
                val g = match.groupValues
                val first = date(g[1], g[2], g[3])
                val last = date(g[4].ifBlank { first.year.toString() }, g[5].ifBlank { first.monthValue.toString() }, g[6])
                require(last >= first)
                buildJsonObject { put("startDate", first.toString()); put("endDate", last.toString()) }
            }.toList()
        val makeups = mutableListOf<JsonObject>()
        val separators = "(?:\\s*[、,，]\\s*)"
        val pair = Regex("((?:$DATE)(?:$separators(?:$DATE))*)\\s*(分别)?(?:补上|补|上)\\s*((?:$DATE)(?:$separators(?:$DATE))*)\\s*的课")
        pair.findAll(clean).forEach { match ->
            // DATE has captures; named extraction uses whole matched sides to avoid group offsets.
            val sides = match.value.removeSuffix("的课").split(Regex("(?:分别)?(?:补上|补|上)"), limit = 2)
            require(sides.size == 2)
            fun dates(s: String) = Regex(DATE).findAll(s).map { val g = it.groupValues; date(g[1], g[2], g[3]) }.toList()
            val targets = dates(sides[0]); val sources = dates(sides[1])
            require(targets.size == sources.size && targets.isNotEmpty())
            sources.zip(targets).forEach { (source, target) -> makeups += buildJsonObject { put("sourceDate", source.toString()); put("targetDate", target.toString()) } }
        }
        // Never silently cancel only half of a notice whose makeup portion was unrecognized.
        if (clean.contains("分别") && makeups.isEmpty()) return null
        val uncovered=pair.replace(holidayPattern.replace(clean,""),"")
        if(Regex(DATE).containsMatchIn(uncovered))return null
        if (holidays.isEmpty() && makeups.isEmpty()) return null
        Operation("$requestId:calendar", "course.calendar.adjust", args = buildJsonObject {
            put("semesterId", state.settings.activeSemesterId)
            put("holidays", JsonArray(holidays)); put("makeups", JsonArray(makeups))
        })
    }.getOrNull()
}
