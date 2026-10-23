package com.hubra.app.ai

import com.hubra.core.AppState

object ChatHistory {
    fun selection(state: AppState, ids: Set<String>): Set<String> {
        val requests=state.messages.filter { it.id in ids }.map { it.requestId ?: it.id }.toSet()
        return state.messages.filter { it.id in requests || it.requestId in requests }.map { it.id }.toSet()
    }
    fun clear(state: AppState, ids: Set<String>): AppState {
        val selected=selection(state,ids)
        return state.copy(messages=state.messages.filter { it.id !in selected })
    }
}
