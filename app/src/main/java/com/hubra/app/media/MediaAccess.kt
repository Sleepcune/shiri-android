package com.hubra.app.media

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Short filesystem transactions share this lock; leases never hold it across a network call. */
object MediaAccess {
    private val mutex = Mutex()
    private val leases = mutableMapOf<String, Int>()
    @Volatile private var currentIds: Set<String> = emptySet()
    val activeIds: Set<String> get() = currentIds
    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }
    suspend fun <T> withLease(ids: List<String>, block: suspend () -> T): T {
        val unique = ids.distinct()
        serialized { unique.forEach { leases[it] = (leases[it] ?: 0) + 1 }; currentIds = leases.keys.toSet() }
        try { return block() }
        finally { withContext(NonCancellable) { serialized { unique.forEach { id ->
            val count = (leases[id] ?: 1) - 1
            if (count == 0) leases.remove(id) else leases[id] = count
        }; currentIds = leases.keys.toSet() } } }
    }
}
