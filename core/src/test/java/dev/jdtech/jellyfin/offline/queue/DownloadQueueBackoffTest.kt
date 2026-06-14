package dev.jdtech.jellyfin.offline.queue

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueBackoffTest {
    @Test
    fun backoff_schedule_is_5s_30s_2m_10m_30m() {
        assertEquals(TimeUnit.SECONDS.toMillis(5), DownloadQueueBackoff.delayForAttempt(1))
        assertEquals(TimeUnit.SECONDS.toMillis(30), DownloadQueueBackoff.delayForAttempt(2))
        assertEquals(TimeUnit.MINUTES.toMillis(2), DownloadQueueBackoff.delayForAttempt(3))
        assertEquals(TimeUnit.MINUTES.toMillis(10), DownloadQueueBackoff.delayForAttempt(4))
        assertEquals(TimeUnit.MINUTES.toMillis(30), DownloadQueueBackoff.delayForAttempt(5))
    }

    @Test
    fun attempts_above_max_clamp_to_last_step() {
        assertEquals(
            DownloadQueueBackoff.delayForAttempt(5),
            DownloadQueueBackoff.delayForAttempt(99),
        )
    }

    @Test
    fun zero_or_negative_attempt_uses_first_step() {
        assertEquals(
            DownloadQueueBackoff.delayForAttempt(1),
            DownloadQueueBackoff.delayForAttempt(0),
        )
        assertEquals(
            DownloadQueueBackoff.delayForAttempt(1),
            DownloadQueueBackoff.delayForAttempt(-3),
        )
    }

    @Test
    fun retry_is_exhausted_at_max_attempts() {
        assertFalse(DownloadQueueBackoff.isRetryExhausted(0))
        assertFalse(DownloadQueueBackoff.isRetryExhausted(4))
        assertTrue(DownloadQueueBackoff.isRetryExhausted(5))
        assertTrue(DownloadQueueBackoff.isRetryExhausted(99))
    }

    @Test
    fun in_worker_sleep_threshold_is_under_two_minutes_so_long_backoffs_release_worker() {
        assertTrue(
            "Backoff at attempt 3 (2m) should NOT sleep in-worker — release to WorkManager.",
            DownloadQueueBackoff.delayForAttempt(3) > DownloadQueueBackoff.IN_WORKER_SLEEP_THRESHOLD_MILLIS,
        )
        assertTrue(
            "Backoff at attempt 2 (30s) SHOULD sleep in-worker.",
            DownloadQueueBackoff.delayForAttempt(2) <= DownloadQueueBackoff.IN_WORKER_SLEEP_THRESHOLD_MILLIS,
        )
    }
}
