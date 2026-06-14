package dev.jdtech.jellyfin.offline.queue

import java.util.concurrent.TimeUnit

object DownloadQueueBackoff {
    const val MAX_ATTEMPTS: Int = 5

    private val SCHEDULE_MILLIS: LongArray =
        longArrayOf(
            TimeUnit.SECONDS.toMillis(5),
            TimeUnit.SECONDS.toMillis(30),
            TimeUnit.MINUTES.toMillis(2),
            TimeUnit.MINUTES.toMillis(10),
            TimeUnit.MINUTES.toMillis(30),
        )

    val IN_WORKER_SLEEP_THRESHOLD_MILLIS: Long = TimeUnit.SECONDS.toMillis(90)

    fun delayForAttempt(attempt: Int): Long {
        if (attempt < 1) return SCHEDULE_MILLIS[0]
        val index = (attempt - 1).coerceAtMost(SCHEDULE_MILLIS.lastIndex)
        return SCHEDULE_MILLIS[index]
    }

    fun isRetryExhausted(attempt: Int): Boolean = attempt >= MAX_ATTEMPTS
}
