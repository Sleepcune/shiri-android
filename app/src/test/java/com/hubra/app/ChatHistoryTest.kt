package com.hubra.app

import com.hubra.app.ai.ChatHistory
import com.hubra.core.*
import org.junit.Test
import kotlin.test.*

class ChatHistoryTest {
    private val messages = listOf(Message("u1","user","午饭13元","2026-10-08T00:00:00Z",pending=true),Message("a1","assistant","已记录","2026-10-08T00:00:01Z",requestId="u1"),Message("u2","user","查看课程","2026-10-08T01:00:00Z"))
    @Test fun deletingReplySelectsItsRequestAndAllAssociatedReplies() {
        assertEquals(setOf("u1","a1"),ChatHistory.selection(AppState(messages=messages),setOf("a1")))
    }
    @Test fun clearingMessagesPreservesBusinessAndAuditRecords() {
        val task=Task("task","取快递")
        val audit=OperationRecord("op","task.create",OperationResult("success","已新增","task","op"),"2026-10-08T00:00:00Z")
        val state=AppState(tasks=listOf(task),operations=listOf(audit),messages=messages)
        val cleared=ChatHistory.clear(state,setOf("u1"))
        assertEquals(listOf("u2"),cleared.messages.map { it.id })
        assertEquals(state.tasks,cleared.tasks); assertEquals(state.operations,cleared.operations)
    }
    @Test fun staleSelectionDoesNotDeleteAnUnrelatedNewMessage() {
        assertEquals(messages,ChatHistory.clear(AppState(messages=messages),setOf("missing")).messages)
    }
}
