package com.hubra.app.ai

import com.hubra.core.Operation
import com.hubra.core.OperationRecord

data class RetryAction(val operation:Operation,val prior:OperationRecord?)

/** Keep successful writes immutable; a failed attempt gets a separate audit identity. */
object ActionRetries {
    fun resolve(operation:Operation,records:List<OperationRecord>):RetryAction {
        val family=records.filter { it.id==operation.id || it.id.startsWith("${operation.id}:retry:") }
        val saved=family.firstOrNull { it.result.status=="success" }
        if(saved!=null)return RetryAction(operation.copy(id=saved.id),saved)
        if(family.isEmpty())return RetryAction(operation,null)
        return RetryAction(operation.copy(id="${operation.id}:retry:${family.size}"),null)
    }
}
