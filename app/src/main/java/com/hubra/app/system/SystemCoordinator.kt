package com.hubra.app.system

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.hubra.app.hubra
import com.hubra.app.widgets.Widgets
import com.hubra.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.*
import java.util.concurrent.TimeUnit

class SystemCoordinator(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("hubra-reminders", Context.MODE_PRIVATE)
    suspend fun refresh() = lock.withLock {
        val state = context.hubra.repository.snapshot()
        val now = Instant.now()
        val journal = ReminderJournal(context)
        val previous = journal.previous(state, now)
        val notificationAllowed = canNotify(context)
        if (notificationAllowed && !prefs.getBoolean("notificationAllowed", true)) journal.clearBlocked()
        // Keep blocked items in the persisted queue so granting permission can recover them.
        val delivered = journal.delivered()
        val plan = ReminderReschedule.build(state, now, previous, delivered)
        ReminderNotification.obsolete(previous, plan, state, delivered, now).forEach { cancelReminderNotification(context, it) }
        val journalPlan = ReminderNotification.planForJournal(plan, previous, state, delivered, now)
        val blocked = journal.blocked()
        prefs.getStringSet("keys", emptySet()).orEmpty().forEach { key -> alarms.cancel(pending(key)) }
        var failed = 0
        var fallback = 0
        val scheduled = mutableListOf<Pair<ScheduledReminder, Instant>>()
        val errors = mutableListOf<String>()
        plan.filter { ReminderReschedule.token(it) !in blocked }.forEach { item ->
            // Overdue delivery still carries the original deadline for validation and deduplication.
            val trigger = if (item.at <= now) now.plusSeconds(1) else item.at
            val intent = Intent(context, ReminderReceiver::class.java).setData(Uri.parse("hubra://reminder/${Uri.encode(item.key)}"))
                .putExtra("key", item.key).putExtra("kind", item.kind).putExtra("at", item.at.toString())
                .putExtra("title", item.title).putExtra("body", item.body).putExtra("page", item.page)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                // Widget boundaries can be deferred; reserve idle-time exact alarms for user reminders.
                if (item.kind == "refresh") alarms.set(AlarmManager.RTC, trigger.toEpochMilli(), pi)
                else if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                    try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pi) }
                    catch (_: SecurityException) {
                        // Permission can be revoked between the capability check and scheduling.
                        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pi)
                        fallback++
                    }
                } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pi)
                scheduled += item to trigger
            } catch (e: RuntimeException) { failed++; errors += "${item.kind}/${item.key}：${e.javaClass.simpleName} ${e.message.orEmpty().take(100)}" }
        }
        val next = scheduled.filter { it.first.kind != "refresh" }.minByOrNull { it.second }
        prefs.edit().putStringSet("keys", scheduled.map { it.first.key }.toSet())
            .putString("plan", HubraJson.json.encodeToString(journalPlan.map(ReminderRecord::from)))
            .putString("next", next?.second?.toString()).putString("nextDue", next?.first?.at?.toString())
            .putString("lastSchedule", Instant.now().toString()).putBoolean("notificationAllowed", notificationAllowed).putBoolean("nightlyEnabled", state.settings.nightlyReminder)
            .putString("nightlyTime", state.settings.nightlyTime).putInt("failed", failed).putInt("fallback", fallback)
            .putString("scheduleErrors", errors.joinToString("\n"))
            .putString("queuedCounts", "课前 ${scheduled.count { it.first.kind == "course" }} · 待办 ${scheduled.count { it.first.kind == "task" }} · 核账 ${scheduled.count { it.first.kind == "nightly" }}")
            .putInt("blockedCount", plan.count { ReminderReschedule.token(it) in blocked })
            .putInt("expiredCount", previous.count { it.kind != "refresh" && it.at <= now && !ReminderReschedule.canCatchUp(state, it, now) && ReminderReschedule.token(it) !in journal.delivered() })
            .commit()
        val wm = WorkManager.getInstance(context)
        val pendingDeletion=state.attachments.any{it.deletionPending && it.deletedAt==null}
        if(state.settings.photoCleanupEnabled || pendingDeletion) {
            wm.enqueueUniquePeriodicWork("hubra-photo-cleanup",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<PhotoCleanupWorker>(24,TimeUnit.HOURS).build())
            val cleanupDue=state.settings.photoCleanupEnabled && (state.settings.lastPhotoCleanupAt?.let { runCatching{Instant.parse(it).plusSeconds(86400)<=now}.getOrDefault(true) }?:true)
            val lastPendingRetry=context.getSharedPreferences("hubra-photo-cleanup",Context.MODE_PRIVATE).getLong("lastPendingRetry",0)
            val due=cleanupDue || (pendingDeletion && now.toEpochMilli()-lastPendingRetry>=3600000)
            if(due)wm.enqueueUniqueWork("hubra-photo-cleanup-catchup",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<PhotoCleanupWorker>().build())
        } else {
            wm.cancelUniqueWork("hubra-photo-cleanup")
            wm.cancelUniqueWork("hubra-photo-cleanup-catchup")
        }
        wm.enqueueUniquePeriodicWork("hubra-daily-backup", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build())
        wm.enqueueUniquePeriodicWork("hubra-widget-refresh", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.HOURS).build())
        wm.enqueueUniqueWork("hubra-widget-data-change", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetOnlyRefreshWorker>().build())
        val availableReports = state.reports.filter { report ->
            report.id.matches(Regex("[A-Za-z0-9_-]+")) && File(context.filesDir, "reports/${report.id}.json").isFile
        }.map { it.id }.toSet()
        val recovery = ReportRecovery.plan(state.reports, availableReports)
        recovery.enqueueIds.forEach { reportId ->
            val request = OneTimeWorkRequestBuilder<ReportWorker>().setInputData(workDataOf("reportId" to reportId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniqueWork("report-$reportId", ExistingWorkPolicy.KEEP, request)
        }
        // Only transition newly missing sources once. Terminal attempts prevent onChanged/refresh loops.
        if (recovery.missingIds.isNotEmpty()) context.hubra.repository.update { current ->
            current.copy(reports = current.reports.map { report ->
                if (report.id in recovery.missingIds && report.status in setOf("pending", "failed"))
                    report.copy(status = "failed", stale = true, attempts = 3, content = report.content + "\n${ReportRecovery.missingSourceMessage}")
                else report
            })
        }
    }
    private fun pending(key: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("hubra://reminder/${Uri.encode(key)}")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    suspend fun testNotification() {
        val delivered = sendNotification(context, "test", "拾日提醒测试", "通知已触发，可以继续检查锁屏及后台提醒。", "today")
        if(delivered)ReminderJournal(context).clearBlocked()
        prefs.edit().putString("lastTrigger", LocalDateTime.now(CoreFunctions.zone).toString())
            .putString("delivery", if (delivered) "测试通知已提交系统" else "测试未投递：通知权限或频道受限").apply()
        refresh()
    }
    fun status(): String {
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        val today = LocalDate.now(CoreFunctions.zone)
        val afterTen = LocalTime.now(CoreFunctions.zone) >= LocalTime.parse(prefs.getString("nightlyTime", "22:00"))
        val lastNight = prefs.getString("lastNight", null)
        val missing = if (prefs.getBoolean("nightlyEnabled", true) && afterTen && lastNight != today.toString()) "\n今天的晚间核账尚无触发记录" else ""
        val channel=context.getSystemService(NotificationManager::class.java).getNotificationChannel("hubra-reminders")
        val channelDescription=when {
            channel==null->"尚未建立（首次通知时创建）"
            channel.importance==NotificationManager.IMPORTANCE_NONE->"已关闭"
            channel.importance<NotificationManager.IMPORTANCE_DEFAULT->"静默或低优先级，可能只出现在通知栏"
            channel.importance<NotificationManager.IMPORTANCE_HIGH->"普通通知，横幅和锁屏展示取决于系统设置"
            else->"高优先级，锁屏展示仍取决于系统设置"
        }
        fun local(value:String?)=value?.let { runCatching{Instant.parse(it).atZone(CoreFunctions.zone).toLocalDateTime().toString()}.getOrDefault(it) }?:"暂无"
        val errors=prefs.getString("scheduleErrors","").orEmpty()
        return "通知：${if (canNotify(context)) "已允许" else "权限或通知频道未允许"}"+
            "\n提醒频道：$channelDescription"+
            "\n提醒方式：${if (exact) "精确提醒" else "系统允许时提醒（可能延迟）"}"+
            "\n已安排：${prefs.getString("queuedCounts","暂无")}"+
            "\n下一触发：${local(prefs.getString("next",null))}"+
            "\n原定时间：${local(prefs.getString("nextDue",null))}"+
            "\n最近重排：${local(prefs.getString("lastSchedule",null))}"+
            "\n最近提醒接收：${prefs.getString("lastReceived","暂无")}"+
            "\n最近投递：${prefs.getString("delivery","暂无")}"+
            "\n已提交对象：${prefs.getString("lastKey","暂无")}"+
            "\n最近完成动作：${prefs.getString("lastAction","暂无")}"+
            "\n未投递等待权限恢复：${prefs.getInt("blockedCount",0)}"+
            "\n过期或已修改而不再补发：${prefs.getInt("expiredCount",0)}"+
            "\n安排失败：${prefs.getInt("failed",0)} · 权限变化后降为非精确：${prefs.getInt("fallback",0)}"+
            (if(errors.isBlank())""else"\n安排错误：$errors")+
            "\n最近忽略：${prefs.getString("lastDiscard","暂无")}"+
            "\n最近异常：${prefs.getString("lastError","暂无")}$missing"+
            "\n“已提交系统”表示通知请求成功，不代表你已看到或系统已在锁屏展示。"
    }
    companion object { private val lock = Mutex() }
}

internal fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
    context.getSystemService(NotificationManager::class.java).getNotificationChannel("hubra-reminders")?.importance != NotificationManager.IMPORTANCE_NONE &&
    (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

internal fun cancelReminderNotification(context: Context, key: String) {
    val identity = ReminderNotification.identity(key)
    context.getSystemService(NotificationManager::class.java).cancel(identity.tag, identity.id)
}

internal fun sendNotification(context: Context, key: String, title: String, body: String, page: String, actionName: String = "", taskVersion: Long? = null): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    ensureReminderChannel(manager)
    if (!canNotify(context)) return false
    val intent = Intent().setClassName(context, "com.hubra.app.MainActivity").setData(Uri.parse("hubra://open/${Uri.encode(key)}")).putExtra("page", page).putExtra("action", actionName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val action = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val complete = if (page == "tasks" && key.startsWith("task:") && actionName.isNotBlank()) {
        val completeIntent = Intent(context, ReminderTaskCompleteReceiver::class.java)
            .setAction(ACTION_COMPLETE_TASK).setData(Uri.parse("hubra://complete/${Uri.encode(key)}"))
            .putExtra(EXTRA_TASK_KEY, key).putExtra(EXTRA_TASK_ID, actionName)
            .putExtra(EXTRA_TASK_DATE, key.substringAfterLast(':'))
        taskVersion?.let { completeIntent.putExtra(EXTRA_TASK_VERSION, it) }
        PendingIntent.getBroadcast(context, (key + ":complete").hashCode(), completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    } else null
    val notification = NotificationCompat.Builder(context, "hubra-reminders").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(action).setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .apply { if (complete != null) addAction(android.R.drawable.ic_menu_agenda, "完成", complete) }
        .build()
    return try {
        val identity = ReminderNotification.identity(key)
        manager.notify(identity.tag, identity.id, notification)
        true
    } catch (_: SecurityException) { false }
}

private fun ensureReminderChannel(manager: NotificationManager) {
    manager.createNotificationChannel(NotificationChannel("hubra-reminders", "课程、待办与核账", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "课程、待办与晚间核账提醒"
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    })
}
