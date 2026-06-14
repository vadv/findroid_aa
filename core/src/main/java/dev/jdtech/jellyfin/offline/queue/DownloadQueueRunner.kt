package dev.jdtech.jellyfin.offline.queue

import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.models.DownloadQueueFailureReason
import dev.jdtech.jellyfin.models.DownloadQueueStatus
import dev.jdtech.jellyfin.repository.DownloadQueueRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import timber.log.Timber

/**
 * Pure-Kotlin queue drain. Sequential single-flight: pulls one runnable entry
 * at a time, advances state, and either sleeps in-process for short retry
 * waits or hands the wake-up back to WorkManager for long ones.
 *
 * Has no Android dependencies so it can be unit-tested.
 */
class DownloadQueueRunner
@Inject
constructor(
    private val downloadQueueRepository: DownloadQueueRepository,
    private val episodeStepRunner: EpisodeStepRunner,
    private val activeDownloadRegistry: ActiveDownloadRegistry,
    private val downloadQueueScheduler: DownloadQueueScheduler,
    private val clock: Clock = SystemClock,
) {
    fun interface Clock {
        fun nowMillis(): Long
    }

    object SystemClock : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    fun interface ProgressSink {
        fun onEntryStarted(entry: DownloadQueueEntryDto)
    }

    suspend fun drain(progressSink: ProgressSink = ProgressSink {}) {
        while (true) {
            val now = clock.nowMillis()
            val nextEntry = downloadQueueRepository.peekNextRunnable(now)
            if (nextEntry == null) {
                val wakeupAt = downloadQueueRepository.nextRetryWaitWakeup(now)
                if (wakeupAt == null) {
                    return
                }
                val sleepMillis = (wakeupAt - clock.nowMillis()).coerceAtLeast(0L)
                if (sleepMillis > DownloadQueueBackoff.IN_WORKER_SLEEP_THRESHOLD_MILLIS) {
                    downloadQueueScheduler.kickWithDelay(sleepMillis)
                    return
                }
                if (sleepMillis > 0L) delay(sleepMillis)
                continue
            }
            progressSink.onEntryStarted(nextEntry)
            processEntry(nextEntry)
        }
    }

    private suspend fun processEntry(entry: DownloadQueueEntryDto) {
        val nextAttempt = entry.attempt + 1
        downloadQueueRepository.updateState(
            entryId = entry.entryId,
            status = DownloadQueueStatus.DOWNLOADING,
            attempt = nextAttempt,
            nextAttemptAtMillis = 0L,
            failureReason = null,
            failureMessage = null,
            nowMillis = clock.nowMillis(),
        )
        val runningEntry = entry.copy(attempt = nextAttempt)

        val outcome =
            try {
                runUnderRegistry(runningEntry)
            } catch (cancel: CancellationException) {
                onCancelledByWorkerStop(runningEntry)
                throw cancel
            }

        if (downloadQueueRepository.get(runningEntry.entryId)?.status ==
            DownloadQueueStatus.CANCELED
        ) {
            // CANCELED was written externally. Don't overwrite.
            return
        }
        when (outcome) {
            EpisodeStepOutcome.Success ->
                downloadQueueRepository.updateState(
                    entryId = runningEntry.entryId,
                    status = DownloadQueueStatus.DOWNLOADED,
                    attempt = runningEntry.attempt,
                    nextAttemptAtMillis = 0L,
                    failureReason = null,
                    failureMessage = null,
                    nowMillis = clock.nowMillis(),
                )
            is EpisodeStepOutcome.Retryable -> {
                val classified =
                    DownloadQueueFailureClassifier.classify(outcome.failure)
                if (DownloadQueueBackoff.isRetryExhausted(runningEntry.attempt)) {
                    downloadQueueRepository.updateState(
                        entryId = runningEntry.entryId,
                        status = DownloadQueueStatus.FAILED,
                        attempt = runningEntry.attempt,
                        nextAttemptAtMillis = 0L,
                        failureReason = classified.reason,
                        failureMessage = classified.message,
                        nowMillis = clock.nowMillis(),
                    )
                } else {
                    val delayMillis =
                        DownloadQueueBackoff.delayForAttempt(runningEntry.attempt)
                    downloadQueueRepository.updateState(
                        entryId = runningEntry.entryId,
                        status = DownloadQueueStatus.RETRY_WAIT,
                        attempt = runningEntry.attempt,
                        nextAttemptAtMillis = clock.nowMillis() + delayMillis,
                        failureReason = classified.reason,
                        failureMessage = classified.message,
                        nowMillis = clock.nowMillis(),
                    )
                }
            }
            is EpisodeStepOutcome.Terminal -> {
                val classified =
                    DownloadQueueFailureClassifier.classify(outcome.failure)
                downloadQueueRepository.updateState(
                    entryId = runningEntry.entryId,
                    status = DownloadQueueStatus.FAILED,
                    attempt = runningEntry.attempt,
                    nextAttemptAtMillis = 0L,
                    failureReason = classified.reason,
                    failureMessage = classified.message,
                    nowMillis = clock.nowMillis(),
                )
            }
            EpisodeStepOutcome.Canceled -> Unit // CANCELED already in DB
        }
    }

    private suspend fun runUnderRegistry(entry: DownloadQueueEntryDto): EpisodeStepOutcome =
        supervisorScope {
            val deferred = async { episodeStepRunner.run(entry) }
            activeDownloadRegistry.attach(entry.entryId, deferred)
            try {
                deferred.await()
            } catch (cancel: CancellationException) {
                if (deferred.isCancelled) {
                    EpisodeStepOutcome.Canceled
                } else {
                    throw cancel
                }
            } finally {
                activeDownloadRegistry.detach(entry.entryId)
            }
        }

    private suspend fun onCancelledByWorkerStop(entry: DownloadQueueEntryDto) {
        val current = downloadQueueRepository.get(entry.entryId) ?: return
        if (current.status == DownloadQueueStatus.CANCELED) return
        Timber.i("Worker stopped mid-flight for entry=%s, resetting to QUEUED", entry.entryId)
        downloadQueueRepository.updateState(
            entryId = entry.entryId,
            status = DownloadQueueStatus.QUEUED,
            attempt = (entry.attempt - 1).coerceAtLeast(0),
            nextAttemptAtMillis = 0L,
            failureReason = DownloadQueueFailureReason.NetworkUnavailable,
            failureMessage = "Worker stopped",
            nowMillis = clock.nowMillis(),
        )
    }
}
