package com.hubra.core

import java.time.Duration
import java.time.Instant

object AttachmentPolicy {
    fun classifications(state:AppState,attachment:Attachment):List<String> {
        val values=attachment.purposes.filter{it in setOf("meal","course","background","other")}.toMutableSet()
        if(state.meals.any{!it.deleted && attachment.id in it.attachmentIds})values+="meal"
        state.messages.filter{attachment.id in it.attachmentIds}.forEach{message->
            values+=when(message.mode){"meal"->"meal";"course"->"course";else->"other"}
        }
        if(state.settings.timetableAppearances.values.any{it.backgroundAttachmentId==attachment.id})values+="background"
        if(values.isEmpty())values+="other"
        return listOf("meal","course","background","other").filter{it in values}
    }
    fun references(state:AppState,id:String):List<String> = buildList {
        state.meals.filter{!it.deleted && id in it.attachmentIds}.forEach{add("饮食：${it.date} ${it.name.ifBlank{it.foods.take(24)}}")}
        state.messages.filter{id in it.attachmentIds}.forEach{add("对话：${it.createdAt} ${it.text.take(24)}")}
        state.settings.timetableAppearances.filterValues{it.backgroundAttachmentId==id}.keys.forEach{semesterId->
            add("课表背景：${state.semesters.firstOrNull{it.id==semesterId}?.name?:"学期"}")
        }
    }
    fun references(state:AppState,attachment:Attachment)=references(state,attachment.id)
    fun expiredCandidates(state:AppState,now:Instant,leasedIds:Set<String> = emptySet()):List<Attachment> {
        if(!state.settings.photoCleanupEnabled)return emptyList()
        val cutoff=now.minus(Duration.ofDays(state.settings.photoRetentionDays.toLong()))
        val pendingIds=state.messages.filter{it.pending}.flatMap{it.attachmentIds}.toSet()+leasedIds
        return state.attachments.filter{image->
            val kinds=classifications(state,image)
            val imported=runCatching{Instant.parse(image.importedAt)}.getOrNull()
            image.deletedAt==null && !image.deletionPending && !image.favorite && image.id !in pendingIds &&
                "meal" in kinds && "course" !in kinds && "background" !in kinds && imported!=null && imported<cutoff
        }
    }
}
