package com.hubra.app.ai

import kotlinx.coroutines.Job
import org.junit.Test
import kotlin.test.*

class ChatRequestsTest {
    @Test fun cancelAllCancelsOnlyRegisteredJobsAndReturnsThemForAwaiting() {
        val first=Job();val second=Job();val unrelated=Job()
        try {
            ChatRequests.register("restore-first",first);ChatRequests.register("restore-second",second)
            assertEquals(setOf(first,second),ChatRequests.cancelAll().toSet())
            assertTrue(first.isCancelled);assertTrue(second.isCancelled);assertFalse(unrelated.isCancelled)
            assertTrue(ChatRequests.cancelAll().isEmpty())
        }finally{first.cancel();second.cancel();unrelated.cancel()}
    }
    @Test fun finishingReplacedRequestCannotUnregisterItsRetry() {
        val previous=Job();val retry=Job()
        try {
            ChatRequests.register("retry",previous);ChatRequests.register("retry",retry)
            assertTrue(previous.isCancelled)
            ChatRequests.unregister("retry",previous)
            ChatRequests.cancel(setOf("retry"));assertTrue(retry.isCancelled)
        }finally{ChatRequests.cancelAll();previous.cancel();retry.cancel()}
    }
}
