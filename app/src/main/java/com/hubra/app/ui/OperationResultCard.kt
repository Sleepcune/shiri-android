package com.hubra.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hubra.core.*

@Composable fun OperationResultCard(state: AppState, record: OperationRecord, execute: Execute, run: Run, onOpen: ((String,String)->Unit)? = null) {
    val summary = OperationSummaries.describe(state,record)
    val open = if (onOpen != null && summary.targetCollection != null && summary.targetId != null) {
        { onOpen(summary.targetCollection!!,summary.targetId!!) }
    } else null
    OutlinedCard(Modifier.fillMaxWidth().then(if(open!=null)Modifier.clickable(onClick=open)else Modifier)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(summary.title,style=MaterialTheme.typography.titleSmall)
            if(record.result.status!="success")Text(record.result.message,color=MaterialTheme.colorScheme.error)
            summary.fields.forEach { field ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(field.label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.widthIn(min=48.dp,max=80.dp))
                    Text(if(field.before!=null && field.after!=null && field.before!=field.after)"${field.before} → ${field.after}"else field.after?:field.before?:"—",style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                }
            }
            Row {
                if(open!=null)TextButton(onClick=open){Text("查看详情")}
                if(record.result.status=="success" && !record.undone && record.changes.isNotEmpty())TextButton(onClick={run{execute(operation("undo",values=args("operationId" to record.id)))}}){Text("撤销")}
                if(record.undone)Text("已撤销",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}
