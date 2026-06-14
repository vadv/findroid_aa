package dev.jdtech.jellyfin.offline.queue

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface DownloadQueueScheduler {
    fun kick()

    fun kickWithDelay(delayMillis: Long)

    fun cancelWorker()
}

@Singleton
class WorkManagerDownloadQueueScheduler
@Inject
constructor(
    private val workManager: WorkManager,
) : DownloadQueueScheduler {
    override fun kick() {
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            buildRequest(delayMillis = 0L),
        )
    }

    override fun kickWithDelay(delayMillis: Long) {
        val effectiveDelay = delayMillis.coerceAtLeast(0L)
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            buildRequest(delayMillis = effectiveDelay),
        )
    }

    override fun cancelWorker() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun buildRequest(delayMillis: Long) =
        OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .apply {
                if (delayMillis > 0L) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            }
            .build()

    companion object {
        const val UNIQUE_WORK_NAME: String = "download_queue_worker"
    }
}
