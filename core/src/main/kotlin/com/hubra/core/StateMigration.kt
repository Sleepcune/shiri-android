package com.hubra.core

import java.time.Instant

object StateMigration {
    /** In-memory migration only; the repository commits the returned state atomically. */
    fun toV2(state: AppState, now: Instant): AppState {
        if (state.schemaVersion == 3) return state
        require(state.schemaVersion in 1..2) { "不支持的数据版本：${state.schemaVersion}" }
        val normalized=CoreFunctions.normalizeSemesters(state)
        val messages=run {
            var previousUser: String?=null
            normalized.messages.map { message ->
                if(message.role=="user")previousUser=message.id
                if(message.role=="assistant" && message.requestId==null)message.copy(requestId=previousUser) else message
            }
        }
        val attached=normalized.copy(messages=messages)
        val attachments=attached.attachments.map { image ->
            val linkedMessages=messages.filter{image.id in it.attachmentIds}
            val linkedMeals=attached.meals.filter{!it.deleted && image.id in it.attachmentIds}
            val importTime=image.importedAt.takeIf{validInstant(it)} ?: linkedMessages.mapNotNull{m->m.createdAt.takeIf{validInstant(it)}}.minOrNull() ?: now.toString()
            val date=image.date ?: linkedMeals.map{it.date}.distinct().singleOrNull()
            image.copy(purposes=AttachmentPolicy.classifications(attached,image),importedAt=importTime,date=date)
        }
        return attached.copy(schemaVersion=2,attachments=attachments)
    }
    /** Migrate legacy v1/v2 snapshots (including backup snapshots) to the current schema. */
    fun toV3(state: AppState, now: Instant): AppState {
        val base = when (state.schemaVersion) {
            1, 2 -> toV2(state, now)
            3 -> state
            else -> throw IllegalArgumentException("不支持的数据版本：${state.schemaVersion}")
        }
        val settings = base.settings.copy(aiContextMessageCount = base.settings.aiContextMessageCount.coerceIn(1, 20))
        val deferrals = base.taskDeferrals.mapNotNull { item ->
            runCatching { java.time.LocalDate.parse(item.sourceDate); java.time.LocalDate.parse(item.targetDate); item }
                .getOrNull()
        }
        val reports = base.reports.map { report ->
            if (report.summary.isNotBlank() || report.content.isBlank()) report
            else report.copy(summary = report.content.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n").take(150))
        }
        return base.copy(schemaVersion = 3, settings = settings, taskDeferrals = deferrals, reports = reports)
    }
    fun migrate(state: AppState, now: Instant): AppState = toV3(state, now)
    private fun validInstant(value:String)=runCatching{Instant.parse(value)}.isSuccess
}
