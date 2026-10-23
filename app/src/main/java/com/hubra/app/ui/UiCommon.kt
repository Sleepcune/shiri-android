package com.hubra.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hubra.core.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

fun today(): LocalDate = LocalDate.now(CoreFunctions.zone)
fun uid(): String = UUID.randomUUID().toString()
fun money(amount: Long?): String = amount?.let { "¥${DomainEngine.formatMoney(it)}" } ?: "未初始化"
fun cents(text: String): Long = BigDecimal(text.trim()).movePointRight(2).longValueExact()
fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.map { (key,value) ->
    key to when(value) { null -> JsonNull; is JsonElement -> value; is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) }
}.toMap())
fun operation(kind: String, target: String? = null, version: Long? = null, values: JsonObject = args()): Operation = Operation(uid(),kind,target,version,values)

data class Input(val key: String, val label: String, val initial: String = "", val type: String = "text", val options: List<Pair<String,String>> = emptyList(), val required: Boolean = false)
data class Editor(val title: String, val inputs: List<Input>, val help: String = "", val save: suspend (Map<String,String>) -> String, val remove: (suspend () -> String)? = null)
typealias Edit = (Editor) -> Unit
typealias Run = (suspend () -> String) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditorDialog(editor: Editor, busy: Boolean, onClose: () -> Unit, onSave: (Map<String,String>) -> Unit, onDelete: () -> Unit) {
    var values by rememberSaveable(editor.title,editor.inputs.map{it.initial}) { mutableStateOf(editor.inputs.associate{it.key to it.initial}) }
    fun change(key:String,value:String){values=values+mapOf(key to value)}
    ModalBottomSheet(onDismissRequest={if(!busy)onClose()}) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(editor.title,style=MaterialTheme.typography.headlineSmall)
            if(editor.help.isNotBlank()) Text(editor.help,style=MaterialTheme.typography.bodySmall)
            editor.inputs.forEach { field ->
                val value=values[field.key].orEmpty()
                when {
                    field.type=="bool" -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(field.label,Modifier.weight(1f));Switch(value=="true",{change(field.key,it.toString())},enabled=!busy)}
                    field.options.isNotEmpty() -> Choice(field.label,value,field.options,{change(field.key,it)},enabled=!busy)
                    else -> OutlinedTextField(value,{change(field.key,it)},label={Text(field.label)},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=field.type!="multiline",minLines=if(field.type=="multiline")2 else 1,
                        visualTransformation=if(field.type=="secret")PasswordVisualTransformation() else VisualTransformation.None)
                }
            }
            Button(onClick={onSave(values)},enabled=!busy && editor.inputs.all{!it.required || values[it.key].orEmpty().isNotBlank()},modifier=Modifier.fillMaxWidth()){Text(if(busy)"保存中…" else "保存")}
            Row { if(editor.remove!=null)TextButton(onClick=onDelete,enabled=!busy){Text("删除",color=MaterialTheme.colorScheme.error)};TextButton(onClick=onClose,enabled=!busy){Text("取消")} }
            Spacer(Modifier.height(24.dp))
        }
    }
}
@Composable fun Choice(label: String, value: String, options: List<Pair<String,String>>, onChange: (String)->Unit, enabled: Boolean=true) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth(),enabled=enabled){Text("$label：${options.firstOrNull{it.first==value}?.second ?: value.ifBlank{"请选择"}}")}
        DropdownMenu(expanded,onDismissRequest={expanded=false}){options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={onChange(id);expanded=false})}}
    }
}
@Composable fun Page(content: @Composable ColumnScope.()->Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content) }
@Composable fun Section(title: String, subtitle: String?=null, content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,style=MaterialTheme.typography.titleMedium);subtitle?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};content() }
    }
}
@Composable fun ActionRow(vararg buttons: Pair<String,()->Unit>) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){buttons.forEach{(label,click)->TextButton(onClick=click,modifier=Modifier.weight(1f)){Text(label)}}} }
@Composable fun Empty(text: String) { Text(text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp)) }
fun Map<String,String>.text(key:String):String=get(key).orEmpty().trim()
fun Map<String,String>.optional(key:String):String?=text(key).takeIf{it.isNotBlank()}
val repeatOptions=listOf("none" to "不重复","daily" to "每天","weekdays" to "工作日","weekly" to "每周")
val typeOptions=listOf("expense" to "支出","income" to "收入","transfer" to "转账 / 充值","refund" to "退款")
fun accountOptions(state:AppState)=state.accounts.map{it.id to it.name}
fun activeCourses(state:AppState)=state.courses.filter{!it.deleted && it.semesterId==state.settings.activeSemesterId}
fun activeRules(state:AppState):List<CourseRule> {
    val courseIds=activeCourses(state).map{it.id}.toSet()
    return state.courseRules.filter{!it.deleted && it.courseId in courseIds}
}
fun activeExceptions(state:AppState):List<CourseException> {
    val ruleIds=activeRules(state).map{it.id}.toSet()
    return state.courseExceptions.filter{!it.deleted && it.ruleId in ruleIds}
}
fun semesterOptions(state:AppState)=state.semesters.map{it.id to "${it.name} · ${it.startDate}"}
fun activeSemesterName(state:AppState)=state.semesters.find{it.id==state.settings.activeSemesterId}?.name?:"当前学期"

/** A stable identity for a destination, including nested settings pages. */
data class ScreenKey(val value: String) {
    val root: String get() = value.substringBefore(':')
}
fun key(page: String): ScreenKey = ScreenKey(page)

fun isOverdueTask(date: String?, completed: Boolean, today: String): Boolean =
    !completed && date?.let { runCatching { LocalDate.parse(it) < LocalDate.parse(today) }.getOrDefault(false) } == true

fun mergeMealAttachmentIds(existing: List<String>, added: List<String> = emptyList(), removed: List<String> = emptyList()): List<String> =
    (existing + added).distinct().filterNot { it in removed }

fun reportSummary(content: String, maxLines: Int = 3): String {
    val lines = content.lines()
    if (lines.size <= maxLines) return content
    return lines.take(maxLines).joinToString("\n") + "\n…"
}
