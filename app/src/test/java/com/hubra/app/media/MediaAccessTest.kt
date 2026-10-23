package com.hubra.app.media

import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class MediaAccessTest {
    @Test fun nestedAndConcurrentLeasesKeepIdsUntilLastUserLeaves()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val job=launch { MediaAccess.withLease(listOf("same","same")){entered.complete(Unit);release.await()} }
        entered.await()
        MediaAccess.withLease(listOf("same","other")){ assertEquals(setOf("same","other"),MediaAccess.activeIds) }
        assertEquals(setOf("same"),MediaAccess.activeIds)
        release.complete(Unit);job.join();assertTrue(MediaAccess.activeIds.isEmpty())
    }
    @Test fun cancellationReleasesLeaseAndNeverHoldsFilesystemLockDuringSuspension()=runBlocking {
        val entered=CompletableDeferred<Unit>()
        val job=launch { MediaAccess.withLease(listOf("active")){entered.complete(Unit);awaitCancellation()} }
        entered.await()
        withTimeout(1000){MediaAccess.serialized{assertTrue("active" in MediaAccess.activeIds)}}
        job.cancelAndJoin();assertTrue(MediaAccess.activeIds.isEmpty())
    }
    @Test fun failureAlsoReleasesAllIds()=runBlocking {
        try { MediaAccess.withLease(listOf("failed")){error("failure")} }catch(_:IllegalStateException){}
        assertTrue(MediaAccess.activeIds.isEmpty())
    }
    @Test fun serializedFilesystemTransactionsDoNotOverlap()=runBlocking {
        var inside=0;var largest=0
        coroutineScope { repeat(10){launch(Dispatchers.Default){MediaAccess.serialized{inside++;largest=maxOf(largest,inside);delay(5);inside--}}} }
        assertEquals(1,largest);assertEquals(0,inside)
    }
}
