package com.hubra.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.hubra.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.hubra.app.ai.ChatHistory

/** One persisted aggregate makes domain operations and undo atomic, including transfers. */
class StateRepository(context: Context) {
    private val database = Room.databaseBuilder(context.applicationContext, HubraDatabase::class.java, "hubra.db").build()
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(InitialData.create())
    val state: StateFlow<AppState> = mutableState
    private var initialized = false
    @Volatile var requestEpoch: Long = 0L
        private set
    var onChanged: (suspend () -> Unit)? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!initialized) {
                val loaded = database.withTransaction {
                    val stored = database.stateDao().get()
                    if (stored == null) { database.stateDao().put(StateRow(revision = mutableState.value.revision, payload = HubraJson.json.encodeToString(mutableState.value))); mutableState.value }
                    else {
                        val original=HubraJson.json.decodeFromString<AppState>(stored.payload)
                        val migrated=StateMigration.toV3(original,Instant.now())
                        if(migrated!=original)database.stateDao().put(StateRow(revision=migrated.revision,payload=HubraJson.json.encodeToString(migrated)))
                        migrated
                    }
                }
                mutableState.value = loaded
                initialized = true
            }
        }
    }

    suspend fun snapshot(): AppState { initialize(); return mutableState.value }
    suspend fun appendRequest(message:Message,expectedEpoch:Long) { update { current ->
        if(expectedEpoch!=requestEpoch)throw CancellationException("数据已恢复，请重新发送输入")
        current.copy(messages=current.messages+message)
    } }

    suspend fun execute(operation: Operation): OperationResult = executeInternal(operation,null,null)

    suspend fun executeForRequest(operation:Operation,requestId:String,expectedEpoch:Long=requestEpoch):OperationResult = executeInternal(operation,requestId,expectedEpoch)

    private suspend fun executeInternal(operation: Operation, requestId:String?, expectedEpoch:Long?): OperationResult {
        initialize()
        val result = withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                val transition = database.withTransaction {
                    val current = database.stateDao().get()?.let { HubraJson.json.decodeFromString<AppState>(it.payload) } ?: mutableState.value
                    if(requestId!=null && (expectedEpoch!=requestEpoch || current.messages.none { it.id==requestId && it.role=="user" && it.pending }))throw CancellationException("对话已清除或数据已恢复，停止后续操作")
                    val transition = DomainEngine.apply(current, operation, Instant.now())
                    if (transition.state != current) {
                        database.stateDao().put(StateRow(revision = transition.state.revision, payload = HubraJson.json.encodeToString(transition.state)))
                    }
                    transition
                }
                mutableState.value = transition.state
                transition.result
                }
            }
        }
        if (result.status == "success") onChanged?.invoke()
        return result
    }

    /** System metadata uses the same serialization lock as user operations. */
    suspend fun update(transform: (AppState) -> AppState) { updateInternal(null,null,transform) }

    suspend fun updateForRequest(requestId:String,expectedEpoch:Long=requestEpoch,transform:(AppState)->AppState):Boolean = updateInternal(requestId,expectedEpoch,transform)

    private suspend fun updateInternal(requestId:String?,expectedEpoch:Long?,transform: (AppState) -> AppState):Boolean {
        initialize()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                val committed = database.withTransaction {
                    val current = database.stateDao().get()?.let { HubraJson.json.decodeFromString<AppState>(it.payload) } ?: mutableState.value
                    if(requestId!=null && (expectedEpoch!=requestEpoch || current.messages.none { it.id==requestId && it.role=="user" && it.pending }))return@withTransaction null
                    val next = transform(current).copy(revision = current.revision + 1)
                    database.stateDao().put(StateRow(revision = next.revision, payload = HubraJson.json.encodeToString(next)))
                    next
                }
                if(committed!=null)mutableState.value = committed
                committed!=null
                }
            }
        }.also { changed -> if(changed)onChanged?.invoke() }
    }

    suspend fun clearMessages(ids:Set<String>):Set<String> {
        var removed=emptySet<String>()
        update { current -> removed=ChatHistory.selection(current,ids);ChatHistory.clear(current,ids) }
        return removed
    }

    suspend fun restore(restored: AppState) { update { requestEpoch++;StateMigration.toV3(restored,Instant.now()).copy(revision = it.revision + 1) } }
}
