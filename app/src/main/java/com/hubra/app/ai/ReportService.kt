package com.hubra.app.ai

import android.content.Context
import com.hubra.app.hubra
import com.hubra.core.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.*
import com.hubra.app.media.MediaAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReportService(private val context: Context) {
    private fun summaryOf(text: String): String = text.lineSequence()
        .filter { it.isNotBlank() }
        .take(3)
        .joinToString("\n")
        .take(150)
    suspend fun refresh(date:String) {
        val epoch=context.hubra.repository.requestEpoch
        val snapshot=context.hubra.repository.snapshot();val day=LocalDate.parse(date)
        val id="manual-$date-${snapshot.revision}"
        require(day<=LocalDate.now(CoreFunctions.zone)) { "不能生成未来的日报" }
        val cutoff=if(day==LocalDate.now(CoreFunctions.zone))Instant.now().toString() else day.plusDays(1).atStartOfDay(CoreFunctions.zone).toInstant().minusNanos(1).toString()
        ReportSources.create(context,DailyReport(id,day.toString(),cutoff,snapshot.revision),snapshot,epoch)
        generate(id)
    }
    suspend fun generate(reportId:String) {
        require(reportId.matches(Regex("[A-Za-z0-9_-]+"))) { "日报ID非法" }
        val source=withContext(Dispatchers.IO) { MediaAccess.serialized {
            val current=context.hubra.repository.snapshot()
            val report=current.reports.firstOrNull { it.id==reportId } ?: error("日报不存在")
            val file=File(context.filesDir,"reports/$reportId.json")
            require(file.exists()) { "日报原始快照缺失，请主动重新生成" }
            Triple(current,report,StateMigration.toV3(HubraJson.json.decodeFromString<AppState>(file.readText()),Instant.now())) to context.hubra.repository.requestEpoch
        } }
        val (current,report,snapshot)=source.first;val epoch=source.second
        if(report.status=="ready")return
        fun checkCurrent(s:AppState) {
            if(context.hubra.repository.requestEpoch!=epoch || s.reports.none{it.id==reportId && it.cutoff==report.cutoff && it.dataRevision==report.dataRevision})throw CancellationException("数据已恢复，旧日报停止")
        }
        val date=LocalDate.parse(report.date); val cutoff=Instant.parse(report.cutoff)
        val meals=snapshot.meals.filter { !it.deleted && it.date==report.date && (it.createdAt.isBlank() || Instant.parse(it.createdAt)<=cutoff) }
        val tx=snapshot.transactions.filter { !it.deleted && it.date==report.date && Instant.parse(it.createdAt)<=cutoff }
        val tomorrow=date.plusDays(1)
        val fixedFacts="${report.date}，截至 ${cutoff.atZone(CoreFunctions.zone).toLocalDateTime()}\n消费：¥${DomainEngine.formatMoney(tx.filter { it.type=="expense" }.sumOf { it.amountCents })}；退款：¥${DomainEngine.formatMoney(tx.filter { it.type=="refund" }.sumOf { it.amountCents })}\n"+
            snapshot.accounts.joinToString("\n") { a->"${a.name}：${CoreFunctions.balanceAt(snapshot,a.id,cutoff)?.let { "推算余额 ¥${DomainEngine.formatMoney(it)}" } ?: "未初始化"}；${if(snapshot.reconciliations.any { it.accountId==a.id && Instant.parse(it.cutoff).atZone(CoreFunctions.zone).toLocalDate()==date && !it.stale })"已提交核账观察" else "尚未核账"}" }+
            "\n明日课程：\n"+CoreFunctions.occurrences(snapshot,tomorrow).joinToString("\n") { "${it.start} ${it.name} · ${it.room}${if(it.pending)"（候选）"else ""}" }.ifBlank { "无" }+
            "\n明日待办：\n"+CoreFunctions.tasksForDate(snapshot,tomorrow).filter { !it.completed }.joinToString("\n") { "${it.time.orEmpty()} ${it.task.title}" }.ifBlank { "无" }
        val profile=current.profiles.firstOrNull { it.id==current.settings.reportProfileId } ?: error("日报 API 未配置")
        val payload="仅分析以下餐食，不重算或复述资金事实，不推断漏记等于漏吃。\n餐食：${HubraJson.json.encodeToString(meals.map { it.copy(attachmentIds=emptyList(),analysis="") })}\n用户资料：${HubraJson.json.encodeToString(snapshot.settings.health)}"
        try {
            val reply=ApiClient(context).request(profile,Prompts.nutrition,payload)
            require(reply.text.isNotBlank()) { "AI返回空日报" }
            val content = "$fixedFacts\n\n饮食回顾与建议\n${reply.text}"
            context.hubra.repository.update { s->checkCurrent(s);s.copy(reports=s.reports.map { if(it.id==reportId)it.copy(status="ready",content=content,summary=summaryOf(reply.text))else it }) }
        } catch(e:CancellationException){throw e}
        catch(e:Exception) {
            val content = "$fixedFacts\n\nAI分析未完成：${e.message}"
            context.hubra.repository.update { s->checkCurrent(s);s.copy(reports=s.reports.map { if(it.id==reportId)it.copy(status="failed",content=content,summary=summaryOf("AI分析未完成：${e.message}"))else it }) }
            throw e
        }
    }
}

