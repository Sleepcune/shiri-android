package com.hubra.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data class CalendarHoliday(val startDate:String,val endDate:String)
@Serializable data class CalendarMakeup(val sourceDate:String,val targetDate:String)
data class CalendarAdjustment(val state:AppState,val message:String)

object CalendarAdjustments {
    /** Build final intents before changing rows, so holiday + makeup retries never increment versions. */
    fun apply(state:AppState,holidays:List<CalendarHoliday>,makeups:List<CalendarMakeup>,useBaseSchedule:Boolean=true):CalendarAdjustment {
        require(holidays.isNotEmpty() || makeups.isNotEmpty()){ "请提供放假日期或补课日期" }
        require(holidays.size<=100 && makeups.size<=100){ "一次调整的日期过多" }
        val pairs=makeups.distinct()
        require(pairs.groupBy{it.sourceDate}.values.all{group->group.map{it.targetDate}.distinct().size==1}){ "同一天的课程不能同时移到两个日期，请分别说明" }
        val original=state.copy(courseExceptions=emptyList())
        val intended=linkedMapOf<Pair<String,String>,CourseException>()
        val holidayRanges=holidays.distinct().map { holiday->
            val start=LocalDate.parse(holiday.startDate);val end=LocalDate.parse(holiday.endDate)
            require(start<=end && ChronoUnit.DAYS.between(start,end)<=366){ "放假日期范围无效或超过一年" }
            start to end
        }
        holidayRanges.forEach{(start,end)->
            var date=start
            while(date<=end) {
                CoreFunctions.occurrences(original,date).forEach{item->
                    val key=item.ruleId to item.date
                    intended[key]=CourseException(identity(key),item.ruleId,item.date,cancelled=true)
                }
                date=date.plusDays(1)
            }
        }
        val makeupDetails=mutableListOf<String>()
        pairs.forEach { pair->
            val source=LocalDate.parse(pair.sourceDate);val target=LocalDate.parse(pair.targetDate)
            require(source!=target){ "补课日期不能与原上课日期相同" }
            val items=CoreFunctions.occurrences(if(useBaseSchedule)original else state,source)
            require(items.isNotEmpty()){ "原始课表在${pair.sourceDate}没有已知课程，不能猜测补课内容" }
            items.forEach { item ->
                // The occurrence ID retains its original date even if its effective date was moved.
                val originalDate=item.id.substringAfterLast(':',item.date)
                val key=item.ruleId to originalDate
                intended[key]=CourseException(identity(key),item.ruleId,originalDate,movedDate=pair.targetDate,
                    startPeriod=if(useBaseSchedule)null else item.startPeriod,endPeriod=if(useBaseSchedule)null else item.endPeriod,
                    room=if(useBaseSchedule)null else item.room)
            }
            makeupDetails+="${pair.sourceDate} → ${pair.targetDate}（${items.size}门）"
        }
        var rows=state.courseExceptions
        var changed=false
        intended.forEach { (key,desired) ->
            val existing=rows.firstOrNull{!it.deleted && it.ruleId==key.first && it.date==key.second}
                ?: rows.firstOrNull{it.ruleId==key.first && it.date==key.second}
            val desiredRow=desired.copy(id=existing?.id?:desired.id,version=existing?.version?:1)
            if(existing==null) { rows=rows+desiredRow;changed=true }
            else if(existing!=desiredRow) { rows=rows.map{if(it.id==existing.id)desiredRow.copy(version=existing.version+1) else it};changed=true }
            // Repair accidental legacy duplicates while retaining their audit identities.
            rows=rows.map{item->
                if(item.id!=desiredRow.id && !item.deleted && item.ruleId==key.first && item.date==key.second){changed=true;item.copy(deleted=true,version=item.version+1)} else item
            }
        }
        val updated=state.copy(courseExceptions=rows)
        val canceled=intended.values.filter{it.cancelled}.groupBy{it.date}.entries.sortedBy{it.key}.joinToString("；"){(date,items)->"$date（${items.size}门）"}
        val movedIds=intended.values.filter{it.movedDate!=null}.map{"${it.ruleId}:${it.date}"}.toSet()
        val conflicts=mutableListOf<String>()
        pairs.map{it.targetDate}.distinct().forEach{date->
            val occurrences=CoreFunctions.occurrences(updated,LocalDate.parse(date))
            occurrences.forEachIndexed{index,left->occurrences.drop(index+1).forEach{right->
                if((left.id in movedIds || right.id in movedIds) && left.startPeriod<=right.endPeriod && right.startPeriod<=left.endPeriod)
                    conflicts+="$date ${left.name}与${right.name}"
            }}
        }
        val details=buildList {
            if(canceled.isNotEmpty())add("停课：$canceled")
            if(makeupDetails.isNotEmpty())add("补课：${makeupDetails.joinToString("；")}")
            if(intended.isEmpty())add("所选放假日期没有已知课程")
            if(conflicts.isNotEmpty())add("保留${conflicts.size}处时间冲突，请核对：${conflicts.joinToString("；")}")
        }.joinToString("\n")
        return CalendarAdjustment(updated,if(changed)"已调整课表\n$details" else "已安排，未重复修改\n$details")
    }
    /** A true copy adds explicit one-week rules and leaves all source rules/occurrences intact. */
    fun copyDay(state:AppState,sourceDate:String,targetDate:String,useBaseSchedule:Boolean=true):CalendarAdjustment {
        val source=LocalDate.parse(sourceDate);val target=LocalDate.parse(targetDate)
        require(source!=target){"复制目标不能与原日期相同"}
        val week=CoreFunctions.weekNumber(state.settings,target)
        require(week!=null && week in 1..state.settings.semesterWeeks){"复制目标日期应在当前学期内"}
        val items=CoreFunctions.occurrences(if(useBaseSchedule)state.copy(courseExceptions=emptyList()) else state,source)
        require(items.isNotEmpty()){ "原始课表在${sourceDate}没有已知课程，不能猜测复制内容" }
        var rows=state.courseRules
        var changed=false
        val copiedIds=mutableSetOf<String>()
        val retained=mutableListOf<String>()
        items.forEach{item->
            val id="calendar-copy-${UUID.nameUUIDFromBytes("${item.ruleId}:$sourceDate:$targetDate".toByteArray(Charsets.UTF_8))}"
            val previous=rows.firstOrNull{it.id==id && !it.deleted}
            if(previous!=null) {
                val exceptions=state.courseExceptions.filter{!it.deleted && it.ruleId==id && it.date==targetDate}
                exceptions.forEach{exception->retained+="${item.name}：${if(exception.cancelled)"停课" else if(exception.movedDate!=null)"调至${exception.movedDate}" else "时间或教室调整"}"}
                // A replay must preserve edits made after the original copy, including cancellations.
                return@forEach
            }
            val live=CoreFunctions.occurrences(state.copy(courseRules=rows),target).firstOrNull{other->
                other.courseId==item.courseId && other.startPeriod==item.startPeriod && other.endPeriod==item.endPeriod && other.room==item.room
            }
            if(live==null) {
                val old=rows.firstOrNull{it.id==id}
                val rule=CourseRule(id,item.courseId,target.dayOfWeek.value,item.startPeriod,item.endPeriod,listOf(week),item.room,
                    "单次复制：$sourceDate → $targetDate",(old?.version?:0)+1)
                rows=if(old==null)rows+rule else rows.map{if(it.id==id)rule else it}
                copiedIds+="$id:$targetDate";changed=true
            }
        }
        val updated=state.copy(courseRules=rows)
        val occurrences=CoreFunctions.occurrences(updated,target)
        val conflicts=mutableListOf<String>()
        occurrences.forEachIndexed{index,left->occurrences.drop(index+1).forEach{right->
            if((left.id in copiedIds || right.id in copiedIds) && left.startPeriod<=right.endPeriod && right.startPeriod<=left.endPeriod)
                conflicts+="${left.name}与${right.name}"
        }}
        val detail="复制参照：$sourceDate → $targetDate（来源${items.size}门），原日期课程保留"+
            (if(retained.isEmpty())"" else "\n后续调整已保留：${retained.joinToString("；")}")+
            if(conflicts.isEmpty())"" else "\n保留${conflicts.size}处时间冲突，请核对：${conflicts.joinToString("；") }"
        return CalendarAdjustment(updated,if(changed)"已复制课程\n$detail" else "已安排，未重复添加\n$detail")
    }
    private fun identity(key:Pair<String,String>)="calendar-${UUID.nameUUIDFromBytes("${key.first}:${key.second}".toByteArray(Charsets.UTF_8))}"
}
