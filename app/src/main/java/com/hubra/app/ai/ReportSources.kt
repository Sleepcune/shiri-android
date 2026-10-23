package com.hubra.app.ai

import android.content.Context
import com.hubra.app.hubra
import com.hubra.app.media.MediaAccess
import com.hubra.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/** Publish the immutable source before its row under the shared backup/filesystem lock. */
object ReportSources {
    suspend fun create(context:Context,report:DailyReport,snapshot:AppState,expectedEpoch:Long):Boolean=withContext(Dispatchers.IO){MediaAccess.serialized {
        require(report.id.matches(Regex("[A-Za-z0-9_-]+")))
        val repo=context.hubra.repository
        fun duplicate(value:DailyReport)=value.id==report.id || (report.id.startsWith("night-") && value.id.startsWith("night-") && value.cutoff==report.cutoff)
        if(repo.requestEpoch!=expectedEpoch)throw CancellationException("数据已恢复，旧日报停止")
        if(repo.snapshot().reports.any(::duplicate))return@serialized false
        val file=File(context.filesDir,"reports/${report.id}.json").apply{parentFile?.mkdirs()}
        val tmp=File(file.parentFile,"${file.name}.tmp")
        try {
            tmp.writeText(HubraJson.json.encodeToString(snapshot))
            check(tmp.renameTo(file)){"日报快照保存失败"}
            repo.update { state->
                if(repo.requestEpoch!=expectedEpoch)throw CancellationException("数据已恢复，旧日报停止")
                if(state.reports.any(::duplicate))state else state.copy(reports=state.reports+report)
            }
        } finally { tmp.delete() }
        true
    } }
}
