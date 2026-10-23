package com.hubra.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object HubraJson { val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true } }

@Serializable data class Account(val id: String, val name: String, val initialCents: Long? = null, val initialDate: String? = null, val version: Long = 1)
@Serializable data class Transaction(val id: String, val type: String = "expense", val accountId: String, val amountCents: Long,
    val date: String, val note: String = "", val category: String = "其他", val toAccountId: String? = null, val relatedId: String? = null,
    val createdAt: String, val modifiedAt: String, val version: Long = 1, val deleted: Boolean = false, val source: String = "manual")
@Serializable data class Meal(val id: String, val date: String, val name: String = "", val foods: String = "", val portion: String = "",
    val attachmentIds: List<String> = emptyList(), val transactionId: String? = null, val analysis: String = "", val source: String = "manual", val version: Long = 1, val deleted: Boolean = false, val createdAt: String = "", val modifiedAt: String = "")
@Serializable data class Period(val number: Int, val start: String, val end: String)
@Serializable data class Semester(val id: String, val name: String, val startDate: String, val weekCount: Int = 20, val version: Long = 1)
@Serializable data class Course(val id: String, val name: String, val code: String = "", val teacher: String = "", val color: String = "#8EADF0", val pending: Boolean = false, val sourceText: String = "", val version: Long = 1, val deleted: Boolean = false, val semesterId: String = "default")
@Serializable data class CourseRule(val id: String, val courseId: String, val dayOfWeek: Int, val startPeriod: Int, val endPeriod: Int,
    val weeks: List<Int>, val room: String = "", val sourceText: String = "", val version: Long = 1, val deleted: Boolean = false)
@Serializable data class CourseException(val id: String, val ruleId: String, val date: String, val cancelled: Boolean = false,
    val movedDate: String? = null, val startPeriod: Int? = null, val endPeriod: Int? = null, val room: String? = null, val version: Long = 1, val deleted: Boolean = false)
@Serializable data class CourseOccurrence(val id: String, val courseId: String, val ruleId: String, val name: String, val teacher: String, val color: String,
    val date: String, val startPeriod: Int, val endPeriod: Int, val start: String, val end: String, val room: String, val pending: Boolean = false)
@Serializable data class Task(val id: String, val title: String, val note: String = "", val date: String? = null, val time: String? = null,
    val priority: Int = 0, val tags: List<String> = emptyList(), val repeat: String = "none", val reminder: Boolean = false,
    val courseRuleId: String? = null, val courseDate: String? = null, val offsetMinutes: Int = 0,
    val parentId: String? = null, val completed: Boolean = false, val version: Long = 1, val deleted: Boolean = false)
@Serializable data class TaskCompletion(val id: String, val taskId: String, val date: String, val completedAt: String)
/** A one-time move of one occurrence of a repeating task. The task rule itself is unchanged. */
@Serializable data class TaskDeferral(val id: String, val taskId: String, val sourceDate: String, val targetDate: String,
    val targetTime: String? = null, val createdAt: String = "", val version: Long = 1, val deleted: Boolean = false) {
    val date: String get() = sourceDate
    val deferredDate: String get() = targetDate
}
@Serializable data class TaskOccurrence(val task: Task, val date: String?, val time: String?, val completed: Boolean)
@Serializable data class Reconciliation(val id: String, val accountId: String, val actualCents: Long, val predictedCents: Long?, val differenceCents: Long?, val cutoff: String, val stale: Boolean = false, val version: Long = 1, val deleted: Boolean = false)
@Serializable data class AccountRule(val id: String, val keyword: String, val accountId: String, val version: Long = 1, val deleted: Boolean = false)
@Serializable data class ApiProfile(val id: String, val name: String, val baseUrl: String, val textModel: String = "monk", val imageModel: String = "monk", val timeoutSeconds: Int = 60,
    val streaming: Boolean = false, val supportsImages: Boolean = false, val supportsTools: Boolean = false, val supportsJson: Boolean = false, val testedAt: String? = null)
@Serializable data class HealthProfile(val highUricAcid: Boolean = true, val lowBmi: Boolean = true, val heightCm: String = "", val weightKg: String = "", val uricAcid: String = "", val testDate: String = "", val restrictions: String = "", val doctorAdvice: String = "")
@Serializable data class TimetableAppearance(val fontSizeSp: Int = 12, val rowHeightDp: Int = 64, val cornerRadiusDp: Int = 8,
    val opacity: Float = .85f, val showWeekends: Boolean = true, val showTeacher: Boolean = false, val showTimes: Boolean = true,
    val backgroundColor: String = "#F3F4FC", val backgroundAttachmentId: String? = null)
@Serializable data class Settings(val semesterStart: String = "2026-08-24", val semesterWeeks: Int = 20, val periods: List<Period> = emptyList(), val timezone: String = "Asia/Shanghai",
    val activeProfileId: String = "monk", val imageProfileId: String = "monk", val reportProfileId: String = "monk", val courseReminderMinutes: Int = 15,
    val nightlyReminder: Boolean = true, val nightlyTime: String = "22:00", val onboardingComplete: Boolean = false, val health: HealthProfile = HealthProfile(), val lastExportAt: String? = null, val activeSemesterId: String = "default",
    val themeMode: String = "system", val photoCleanupEnabled: Boolean = false, val photoRetentionDays: Int = 90,
    val lastPhotoCleanupAt: String? = null, val lastPhotoCleanupCount: Int = 0, val lastPhotoCleanupBytes: Long = 0,
    val timetableAppearances: Map<String, TimetableAppearance> = emptyMap(), val aiContextMessageCount: Int = 5)
@Serializable data class DailyReport(val id: String, val date: String, val cutoff: String, val dataRevision: Long, val status: String = "pending", val content: String = "", val stale: Boolean = false, val attempts: Int = 0, val summary: String = "") {
    val summaryText: String get() = summary
}
@Serializable data class Message(val id: String, val role: String, val text: String, val createdAt: String, val attachmentIds: List<String> = emptyList(), val operationIds: List<String> = emptyList(), val pending: Boolean = false, val mode: String = "chat", val requestId: String? = null)
@Serializable data class Attachment(val id: String, val relativePath: String, val mimeType: String = "image/jpeg", val sourceText: String = "",
    val purposes: List<String> = emptyList(), val importedAt: String = "", val date: String? = null, val favorite: Boolean = false,
    val sizeBytes: Long = 0, val deletedAt: String? = null, val deletionPending: Boolean = false, val deleteError: String? = null)
@Serializable data class Operation(val id: String, val kind: String, val targetId: String? = null, val expectedVersion: Long? = null, val args: JsonObject = JsonObject(emptyMap()))
@Serializable data class OperationResult(val status: String, val message: String, val targetId: String? = null, val operationId: String, val affectedIds: List<String> = emptyList())
@Serializable data class EntityChange(val collection: String, val id: String, val before: String? = null, val after: String? = null)
@Serializable data class OperationRecord(val id: String, val kind: String, val result: OperationResult, val createdAt: String, val changes: List<EntityChange> = emptyList(), val undone: Boolean = false)
@Serializable data class AppState(val schemaVersion: Int = 1, val revision: Long = 0, val accounts: List<Account> = emptyList(), val transactions: List<Transaction> = emptyList(),
    val meals: List<Meal> = emptyList(), val courses: List<Course> = emptyList(), val courseRules: List<CourseRule> = emptyList(), val courseExceptions: List<CourseException> = emptyList(),
    val tasks: List<Task> = emptyList(), val taskCompletions: List<TaskCompletion> = emptyList(), val taskDeferrals: List<TaskDeferral> = emptyList(), val reconciliations: List<Reconciliation> = emptyList(), val accountRules: List<AccountRule> = emptyList(),
    val operations: List<OperationRecord> = emptyList(), val profiles: List<ApiProfile> = emptyList(), val reports: List<DailyReport> = emptyList(), val messages: List<Message> = emptyList(),
    val attachments: List<Attachment> = emptyList(), val settings: Settings = Settings(), val semesters: List<Semester> = emptyList())
data class Transition(val state: AppState, val result: OperationResult)
