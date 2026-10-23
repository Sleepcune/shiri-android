package com.hubra.app.ai

import android.content.Context
import com.hubra.app.hubra
import com.hubra.app.media.MediaAccess
import java.io.File

class ConversationMaintenance(private val context:Context) {
    suspend fun clear(ids:Set<String>):Int {
        val before=context.hubra.repository.snapshot()
        val removed=context.hubra.repository.clearMessages(ids)
        val requests=before.messages.filter { it.id in removed && it.role=="user" }.map { it.id }.toSet()
        ChatRequests.cancel(requests)
        MediaAccess.serialized {
            requests.filter { it.matches(Regex("[A-Za-z0-9_-]+")) }.forEach { id ->
                File(context.filesDir,"action-plans/$id.json").delete()
                File(context.filesDir,"action-plans/$id.json.tmp").delete()
            }
        }
        return removed.size
    }
}
