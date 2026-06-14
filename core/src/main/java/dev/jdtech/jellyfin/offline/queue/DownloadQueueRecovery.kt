package dev.jdtech.jellyfin.offline.queue

import dev.jdtech.jellyfin.repository.DownloadQueueRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class DownloadQueueRecovery
@Inject
constructor(
    private val downloadQueueRepository: DownloadQueueRepository,
) {
    suspend fun run(nowMillis: Long = System.currentTimeMillis()): RecoveryReport {
        val resetDownloading = downloadQueueRepository.resetInterruptedDownloading(nowMillis)
        val reclaimedRetries = downloadQueueRepository.reclaimOverdueRetryWaits(nowMillis)
        Timber.i(
            "DownloadQueueRecovery reset_downloading=%d reclaimed_retries=%d",
            resetDownloading,
            reclaimedRetries,
        )
        val pendingCount = downloadQueueRepository.countLive()
        return RecoveryReport(
            resetDownloading = resetDownloading,
            reclaimedRetries = reclaimedRetries,
            hasPendingWork = pendingCount > 0,
        )
    }
}

data class RecoveryReport(
    val resetDownloading: Int,
    val reclaimedRetries: Int,
    val hasPendingWork: Boolean,
)
