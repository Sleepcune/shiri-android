package com.hubra.app.system

import android.content.Context
import com.hubra.core.AppState
import com.hubra.core.HubraJson
import java.time.Instant
import java.time.ZoneId

/** The persisted queue survives process death/reboot; tokens identify one scheduled delivery. */
internal class ReminderJournal(context: Context) {
    private val prefs = context.getSharedPreferences("hubra-reminders", Context.MODE_PRIVATE)
    fun previous(state: AppState, now: Instant): List<ScheduledReminder> {
        val encoded = prefs.getString("plan", null)
        if (encoded != null) return runCatching {
            HubraJson.json.decodeFromString<List<ReminderRecord>>(encoded).map { it.reminder() }
        }.getOrElse { failure("读取提醒队列", it); emptyList() }
        // Upgrade from the old key-only queue. Recover only alarms that were already arranged.
        val keys = prefs.getStringSet("keys", emptySet()).orEmpty()
        val dayStart = now.atZone(ZoneId.of(state.settings.timezone)).toLocalDate().atStartOfDay(ZoneId.of(state.settings.timezone)).toInstant()
        return ReminderPlan.build(state, dayStart.minusNanos(1)).filter { it.key in keys }
    }
    fun delivered(): Set<String> = synchronized(guard) { prefs.getStringSet("deliveredTokens", emptySet()).orEmpty().toSet() }
    fun blocked(): Set<String> = synchronized(guard) { prefs.getStringSet("blockedTokens", emptySet()).orEmpty().toSet() }
    fun clearBlocked() = synchronized(guard) { prefs.edit().remove("blockedTokens").commit(); Unit }
    fun recordDelivery(reminder: ScheduledReminder, submitted: Boolean, now: Instant) = synchronized(guard) {
        val token = ReminderReschedule.token(reminder)
        val delivered = delivered().toMutableSet()
        val blocked = blocked().toMutableSet()
        if (submitted) { delivered += token; blocked -= token } else blocked += token
        fun recent(tokens: Set<String>) = tokens.filter { value ->
            runCatching { Instant.parse(value.substringAfterLast('@')) >= now.minusSeconds(7 * 86400L) }.getOrDefault(false)
        }.toSet()
        prefs.edit().putStringSet("deliveredTokens", recent(delivered)).putStringSet("blockedTokens", recent(blocked))
            .putString("lastTrigger", now.toString()).putString("lastKey", reminder.key).putString("lastDue", reminder.at.toString())
            .putString("delivery", if (submitted) "已提交系统通知" else "未投递：通知权限或频道受限")
            .apply { if (reminder.kind == "nightly") putString("lastNight", reminder.at.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString()) }
            .commit()
        Unit
    }
    fun received(key: String, due: String?, now: Instant) {
        prefs.edit().putString("lastReceived", "$key · 计划 ${due ?: "未知"} · 接收 $now").apply()
    }
    fun discarded(reason: String) { prefs.edit().putString("lastDiscard", reason).apply() }
    fun failure(stage: String, error: Throwable) {
        prefs.edit().putString("lastError", "$stage：${error.javaClass.simpleName} ${error.message.orEmpty().take(180)}").apply()
    }
    companion object { private val guard = Any() }
}
