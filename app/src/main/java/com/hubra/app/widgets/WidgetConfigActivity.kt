package com.hubra.app.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.hubra.app.hubra
import com.hubra.app.ui.ShiriTheme
import kotlinx.coroutines.launch

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            val state by hubra.repository.state.collectAsState()
            ShiriTheme(state.settings.themeMode) {
                var mask by remember { mutableStateOf(true) }
                var account by remember { mutableStateOf("campus") }
                var filter by remember { mutableStateOf("today") }
                var tagMode by remember { mutableStateOf(false) }
                var dark by remember { mutableStateOf(false) }
                var alpha by remember { mutableFloatStateOf(.96f) }
                var ready by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf("") }
                LaunchedEffect(id) {
                    mask = hubra.preferences.get("widget.$id.mask") != "false"
                    account = hubra.preferences.get("widget.$id.account") ?: "campus"
                    filter = hubra.preferences.get("widget.$id.filter") ?: "today"
                    tagMode=filter !in listOf("today","all")
                    dark = hubra.preferences.get("widget.$id.theme") == "dark"
                    alpha = hubra.preferences.get("widget.$id.alpha")?.toFloatOrNull()?.coerceIn(.65f,1f) ?: .96f
                    ready = true
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("拾日 · 桌面组件", style = MaterialTheme.typography.headlineMedium)
                        Text("每个组件可以分别设置，修改只影响当前组件。")
                        Row { Switch(mask, { mask = it }); Text("隐藏金额", Modifier.padding(12.dp)) }
                        Row { Switch(dark, { dark = it }); Text("深色背景", Modifier.padding(12.dp)) }
                        Text("背景不透明度 ${(alpha * 100).toInt()}%")
                        Slider(alpha, { alpha = it }, valueRange = .65f..1f)
                        Text("缩放组件会自动调整可见条数；放不下的课程和待办可点击查看更多。", style = MaterialTheme.typography.bodySmall)
                        Text("显示账户")
                        listOf("campus" to "校园卡", "transport" to "交通卡", "bank" to "银行卡", "wechat" to "微信零钱").forEach { (value, label) ->
                            Row { RadioButton(account == value, { account = value }); Text(label, Modifier.padding(top = 12.dp)) }
                        }
                        Text("待办范围")
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            FilterChip(!tagMode && filter=="today",{filter="today";tagMode=false},label={Text("今天")})
                            FilterChip(!tagMode && filter=="all",{filter="all";tagMode=false},label={Text("全部")})
                            FilterChip(tagMode,{if(!tagMode)filter="";tagMode=true},label={Text("按标签")})
                        }
                        if(tagMode)OutlinedTextField(filter, { filter = it }, label = { Text("标签名称") }, supportingText = { Text("显示今天带有这个标签的待办") })
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                        Button(enabled = ready, onClick = {
                            ready = false
                            lifecycleScope.launch {
                                try {
                                    hubra.preferences.set("widget.$id.mask", mask.toString())
                                    hubra.preferences.set("widget.$id.account", account)
                                    hubra.preferences.set("widget.$id.filter", filter.ifBlank { "today" })
                                    hubra.preferences.set("widget.$id.theme", if (dark) "dark" else "light")
                                    hubra.preferences.set("widget.$id.alpha", alpha.toString())
                                    Widgets.refresh(this@WidgetConfigActivity)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                                } catch (_: Exception) { error = "保存失败，请重试"; ready = true }
                            }
                        }) { Text("保存组件") }
                    }
                }
            }
        }
    }
}
