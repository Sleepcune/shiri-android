package com.hubra.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hubra.app.ui.HubraRoot
import com.hubra.app.system.SystemCoordinator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var launch by mutableStateOf(Pair("chat", ""))
    private var launchKey by mutableStateOf(0L)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent { HubraRoot(launch.first, launch.second, launchKey) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        hubra.scope.launch { runCatching { SystemCoordinator(this@MainActivity).refresh() }.onFailure { hubra.recordReminderFailure("回到应用时重排",it) } }
    }
    private fun readIntent(intent: Intent) {
        launch = (intent.getStringExtra("page") ?: "chat") to (intent.getStringExtra("action") ?: "")
        launchKey++
    }
}
