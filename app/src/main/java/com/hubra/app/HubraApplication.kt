package com.hubra.app

import android.app.Application
import android.content.Context
import com.hubra.app.data.*
import com.hubra.app.system.SystemCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.BufferOverflow

class HubraApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { StateRepository(this) }
    val secrets by lazy { SecretStore(this) }
    val preferences by lazy { PreferencesStore(this) }
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        repository.onChanged = { changes.tryEmit(Unit); Unit }
        scope.launch { changes.debounce(350).collect { runCatching { SystemCoordinator(this@HubraApplication).refresh() }.onFailure { recordReminderFailure("数据更新后重排",it) } } }
        scope.launch { runCatching { repository.initialize();SystemCoordinator(this@HubraApplication).refresh() }.onFailure { recordReminderFailure("启动时重排",it) } }
    }
    fun recordReminderFailure(stage:String,error:Throwable) { getSharedPreferences("hubra-reminders",Context.MODE_PRIVATE).edit().putString("lastError","$stage：${error.javaClass.simpleName}").apply() }
}
val Context.hubra: HubraApplication get() = applicationContext as HubraApplication
