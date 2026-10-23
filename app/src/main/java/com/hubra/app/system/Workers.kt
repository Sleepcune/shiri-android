package com.hubra.app.system

import android.content.*
import androidx.work.*
import com.hubra.app.hubra
import com.hubra.app.ai.ReportService
import com.hubra.app.backup.BackupManager
import com.hubra.app.widgets.Widgets
import com.hubra.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.*
import java.util.UUID

const val ACTION_COMPLETE_TASK = "com.hubra.app.action.COMPLETE_TASK"
const val EXTRA_TASK_KEY = "taskKey"
const val EXTRA_TASK_ID = "taskId"
const val EXTRA_TASK_DATE = "taskDate"
const val EXTRA_TASK_VERSION = "taskVersion"

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        context.hubra.scope.launch {
            var active:ScheduledReminder?=null
            var persistReport:(suspend ()->Unit)?=null
            try {
                deliveryLock.withLock {
                val journal = ReminderJournal(context)
                val kind = intent.getStringExtra("kind") ?: "refresh"
                val key = intent.getStringExtra("key") ?: "unknown"
                val now = Instant.now()
                journal.received(key, intent.getStringExtra("at"), now)
                if (kind != "refresh") {
                    val scheduled = ScheduledReminder(key, intent.getStringExtra("at")?.let(Instant::parse) ?: now, kind,
                        intent.getStringExtra("title").orEmpty(), intent.getStringExtra("body").orEmpty(), intent.getStringExtra("page") ?: "today")
                    active=scheduled
                    val current = context.hubra.repository.snapshot()
                    if (ReminderReschedule.token(scheduled) in journal.delivered()) {
                        journal.discarded("重复投递已忽略：$key"); return@withLock
                    }
                    if (!ReminderPlan.isCurrent(current, scheduled)) {
                        journal.discarded("已修改、停课或完成：$key"); return@withLock
                    }
                    if (scheduled.at <= now && !ReminderReschedule.canCatchUp(current, scheduled, now)) {
                        journal.discarded("已超过有效提醒范围：$key"); return@withLock
                    }
                    var title = scheduled.title.ifBlank { "拾日" }
                    var body = intent.getStringExtra("body").orEmpty()
                    if (kind == "course") {
                        ReminderPlan.courseOccurrence(current, scheduled)?.let { title = it.name; body = "${it.start} · ${it.room}" }
                    } else if (kind == "task") {
                        val taskId = key.removePrefix("task:").substringBeforeLast(':')
                        current.tasks.firstOrNull { it.id == taskId }?.let { title = it.title; body = it.note.ifBlank { "待办提醒" } }
                    }
                    if (kind == "nightly") {
                        val cutoff = intent.getStringExtra("at")?.let(Instant::parse) ?: now
                        val date = cutoff.atZone(CoreFunctions.zone).toLocalDate()
                        val reportEpoch=context.hubra.repository.requestEpoch
                        val state = context.hubra.repository.snapshot()
                        val snapshot = ReportSnapshot.at(state, cutoff)
                        body = "今日已记支出 ¥${DomainEngine.formatMoney(CoreFunctions.expenseTotal(snapshot, date))}，点击核对账户余额。"
                        val reportId = "night-${date}-${state.revision}"
                        // Deliver locally before filesystem/network/report work: report errors must not hide reconciliation.
                        val delivered = sendNotification(context, key, intent.getStringExtra("title") ?: "晚间核账", body, "ledger", "reconcile")
                        journal.recordDelivery(scheduled, delivered, now)
                        if (state.reports.none { it.cutoff == cutoff.toString() }) {
                            // Preserve this immediate cutoff snapshot, but never make other local
                            // notifications wait for a long-running backup's filesystem lock.
                            persistReport={
                                val created=com.hubra.app.ai.ReportSources.create(context,DailyReport(reportId,date.toString(),cutoff.toString(),state.revision),snapshot,reportEpoch)
                                val request = OneTimeWorkRequestBuilder<ReportWorker>().setInputData(workDataOf("reportId" to reportId))
                                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
                                if(created)WorkManager.getInstance(context).enqueueUniqueWork("report-$reportId", ExistingWorkPolicy.KEEP, request)
                            }
                        }
                    }
                    if (kind != "nightly") {
                        val action = if (kind == "task") key.removePrefix("task:").substringBeforeLast(':') else key.removePrefix("course:")
                        val taskVersion = if (kind == "task") current.tasks.firstOrNull { it.id == action && !it.deleted }?.version else null
                        val delivered = sendNotification(context, key, title, body, scheduled.page, action, taskVersion)
                        journal.recordDelivery(scheduled, delivered, now)
                    }
                }
                }
                try { withTimeout(3000) { persistReport?.invoke() } }
                catch(e:TimeoutCancellationException){ReminderJournal(context).failure("日报快照保存超时，通知已处理；可手动重新生成日报",e)}
            } catch(e:CancellationException){throw e}
            catch (e: Exception) {
                // Do not immediately queue a failing delivery forever. A successful test notification
                // or notification permission restoration permits a fresh attempt.
                active?.let { scheduled->
                    val journal=ReminderJournal(context)
                    if(ReminderReschedule.token(scheduled) !in journal.delivered())journal.recordDelivery(scheduled,false,Instant.now())
                }
                ReminderJournal(context).failure("接收提醒 ${intent.getStringExtra("key")}", e)
                context.getSharedPreferences("hubra-reminders", Context.MODE_PRIVATE).edit().putString("delivery", "触发失败：${e.javaClass.simpleName}").apply()
            } finally {
                try { SystemCoordinator(context).refresh() }
                catch (e: Exception) { ReminderJournal(context).failure("触发后重新安排", e); WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()) }
                finally { result.finish() }
            }
        }
    }
    companion object { private val deliveryLock = Mutex() }
}

/** Handles the explicit "完成" action shown on task notifications. */
class ReminderTaskCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMPLETE_TASK) return
        val pending = goAsync()
        context.hubra.scope.launch {
            try {
                val key = intent.getStringExtra(EXTRA_TASK_KEY).orEmpty()
                val expectedVersion = if (intent.hasExtra(EXTRA_TASK_VERSION)) intent.getLongExtra(EXTRA_TASK_VERSION, -1L) else null
                val current = context.hubra.repository.snapshot()
                val request = TaskReminderCompletion.request(current, key, expectedVersion)
                if (request == null) {
                    // The action is no longer actionable (deleted, completed, or stale); remove
                    // only this notification rather than leaving a dead completion button.
                    cancelReminderNotification(context, key)
                    context.getSharedPreferences("hubra-reminders", Context.MODE_PRIVATE).edit()
                        .putString("lastDiscard", "完成动作已失效或记录已修改：$key").apply()
                    return@launch
                }
                val result = context.hubra.repository.execute(
                    com.hubra.core.Operation(UUID.randomUUID().toString(), "task.complete", request.taskId, request.expectedVersion,
                        buildJsonObject { put("date", request.date) })
                )
                if (result.status == "success" || result.status == "conflict") {
                    cancelReminderNotification(context, key)
                }
                context.getSharedPreferences("hubra-reminders", Context.MODE_PRIVATE).edit()
                    .putString(if (result.status == "success") "lastAction" else "lastDiscard",
                        if (result.status == "success") "已完成待办：${request.taskId} · ${request.date}" else "完成动作未应用：${result.message}").apply()
            } catch (e: Exception) {
                context.getSharedPreferences("hubra-reminders", Context.MODE_PRIVATE).edit()
                    .putString("lastError", "完成提醒动作：${e.javaClass.simpleName}").apply()
            } finally {
                try { SystemCoordinator(context).refresh() } catch (_: Exception) { }
                pending.finish()
            }
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        context.hubra.scope.launch {
            try { SystemCoordinator(context).refresh() }
            catch (e: Exception) { ReminderJournal(context).failure("系统事件重新安排 ${intent.action}", e); WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()) }
            finally { pending.finish() }
        }
    }
}

class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("reportId") ?: return Result.failure()
        var attempt = 0
        return try {
            val existing = applicationContext.hubra.repository.snapshot().reports.firstOrNull { it.id == id } ?: return Result.failure()
            if (existing.status == "ready" || existing.status == "complete") return Result.success()
            if (existing.attempts >= 3) return Result.failure()
            applicationContext.hubra.repository.update { state -> state.copy(reports = state.reports.map {
                if (it.id == id) { attempt = it.attempts + 1; it.copy(attempts = attempt) } else it
            }) }
            ReportService(applicationContext).generate(id)
            val report = applicationContext.hubra.repository.snapshot().reports.firstOrNull { it.id == id }
            if (report?.status == "ready" || report?.status == "complete") Result.success() else if (attempt < 3) Result.retry() else {
                applicationContext.hubra.repository.update { state -> state.copy(reports = state.reports.map { if (it.id == id) it.copy(status = "failed", attempts = 3) else it }) }
                Result.failure()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (attempt in 1..2) Result.retry() else {
                applicationContext.hubra.repository.update { state -> state.copy(reports = state.reports.map { if (it.id == id) it.copy(status = "failed", attempts = 3) else it }) }
                Result.failure()
            }
        }
    }
}
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { BackupManager(applicationContext).createSnapshot(); Result.success() } catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
}
class PhotoCleanupWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = try {
        applicationContext.getSharedPreferences("hubra-photo-cleanup",Context.MODE_PRIVATE).edit().putLong("lastPendingRetry",System.currentTimeMillis()).apply()
        val epoch=applicationContext.hubra.repository.requestEpoch
        val manager=com.hubra.app.media.AttachmentManager(applicationContext)
        val retry=manager.retryPendingDeletion()
        val cleanup=manager.cleanup()
        if(retry.count>0)applicationContext.hubra.repository.update { state->
            if(applicationContext.hubra.repository.requestEpoch!=epoch)state else state.copy(settings=state.settings.copy(lastPhotoCleanupAt=Instant.now().toString(),
                lastPhotoCleanupCount=retry.count+cleanup.count,lastPhotoCleanupBytes=retry.bytes+cleanup.bytes))
        }
        if((retry.failed+cleanup.failed)>0 && runAttemptCount<2)Result.retry()else Result.success()
    } catch(e:CancellationException){throw e}
    catch(_:Exception){if(runAttemptCount<2)Result.retry()else Result.failure()}
}
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { SystemCoordinator(applicationContext).refresh(); Result.success() }
        catch(e:CancellationException){throw e}
        catch (e: Exception) { ReminderJournal(applicationContext).failure("后台重新安排",e); if(runAttemptCount<3)Result.retry() else Result.failure() }
}
class WidgetOnlyRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { Widgets.refresh(applicationContext); Result.success() } catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
}
