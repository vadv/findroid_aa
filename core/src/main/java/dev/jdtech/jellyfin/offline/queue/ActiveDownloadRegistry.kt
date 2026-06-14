package dev.jdtech.jellyfin.offline.queue

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job

@Singleton
class ActiveDownloadRegistry @Inject constructor() {
    @Volatile private var currentEntryId: String? = null
    @Volatile private var currentJob: Job? = null

    @Synchronized
    fun attach(entryId: String, job: Job) {
        currentEntryId = entryId
        currentJob = job
    }

    @Synchronized
    fun detach(entryId: String) {
        if (currentEntryId == entryId) {
            currentEntryId = null
            currentJob = null
        }
    }

    @Synchronized
    fun cancelIfActive(entryId: String): Boolean {
        if (currentEntryId != entryId) return false
        currentJob?.cancel()
        return true
    }

    @Synchronized
    fun activeEntryId(): String? = currentEntryId
}
