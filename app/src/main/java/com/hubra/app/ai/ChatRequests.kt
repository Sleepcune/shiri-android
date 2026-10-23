package com.hubra.app.ai

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** Cancellation improves responsiveness; the repository guard is the write authority. */
object ChatRequests {
    private val jobs=ConcurrentHashMap<String,Job>()
    fun register(id:String,job:Job) { jobs.put(id,job)?.takeIf { it!==job }?.cancel() }
    fun unregister(id:String,job:Job) { jobs.remove(id,job) }
    fun cancel(ids:Set<String>) { ids.forEach { jobs.remove(it)?.cancel() } }
    fun cancelAll():List<Job> {
        val registered=jobs.entries.map{it.key to it.value}
        registered.forEach{(id,job)->if(jobs.remove(id,job))job.cancel()}
        return registered.map{it.second}.distinct()
    }
}
