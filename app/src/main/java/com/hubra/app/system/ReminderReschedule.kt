package com.hubra.app.system

import com.hubra.core.AppState
import java.time.*
import kotlinx.serialization.Serializable

@Serializable data class ReminderRecord(val key:String,val at:String,val kind:String,val title:String,val body:String,val page:String) {
    fun reminder()=ScheduledReminder(key,Instant.parse(at),kind,title,body,page)
    companion object { fun from(value:ScheduledReminder)=ReminderRecord(value.key,value.at.toString(),value.kind,value.title,value.body,value.page) }
}

/** Refresh must not erase a delayed alarm merely because its scheduled time just passed. */
object ReminderReschedule {
    fun token(value:ScheduledReminder)="${value.key}@${value.at}"
    fun canCatchUp(state:AppState,value:ScheduledReminder,now:Instant):Boolean {
        if(value.kind=="refresh" || !ReminderPlan.isCurrent(state,value))return false
        val zone=ZoneId.of(state.settings.timezone)
        if(value.at.atZone(zone).toLocalDate()!=now.atZone(zone).toLocalDate())return false
        if(value.kind=="course") {
            val course=ReminderPlan.courseOccurrence(state,value)?:return false
            return now<LocalDate.parse(course.date).atTime(LocalTime.parse(course.end)).atZone(zone).toInstant()
        }
        return value.kind in setOf("task","nightly")
    }
    fun build(state:AppState,now:Instant,previous:List<ScheduledReminder>,suppressedTokens:Set<String>):List<ScheduledReminder> {
        val future=ReminderPlan.build(state,now)
        val overdue=previous.filter{it.at<=now && canCatchUp(state,it,now)}
        return (future+overdue).filter{it.kind=="refresh" || token(it) !in suppressedTokens}.distinctBy{it.key}.sortedBy{it.at}
    }
}
