package dev.jdtech.jellyfin.offline.queue

import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.models.DownloadQueueStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueRecoveryTest {

    @Test
    fun resets_downloading_rows_to_queued() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        repo.put(entry("a", DownloadQueueStatus.DOWNLOADING))
        repo.put(entry("b", DownloadQueueStatus.QUEUED))
        repo.put(entry("c", DownloadQueueStatus.DOWNLOADED))

        val recovery = DownloadQueueRecovery(repo)
        val report = recovery.run(nowMillis = 1_000L)

        assertEquals(1, report.resetDownloading)
        assertEquals(0, report.reclaimedRetries)
        assertEquals(DownloadQueueStatus.QUEUED, repo.requireStatus("a"))
        assertEquals(DownloadQueueStatus.QUEUED, repo.requireStatus("b"))
        assertEquals(DownloadQueueStatus.DOWNLOADED, repo.requireStatus("c"))
        assertTrue(report.hasPendingWork)
    }

    @Test
    fun overdue_retry_waits_are_reclaimed_to_queued() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        repo.put(
            entry("overdue", DownloadQueueStatus.RETRY_WAIT)
                .copy(nextAttemptAtMillis = 500L)
        )
        repo.put(
            entry("future", DownloadQueueStatus.RETRY_WAIT)
                .copy(nextAttemptAtMillis = 5_000L)
        )

        val recovery = DownloadQueueRecovery(repo)
        val report = recovery.run(nowMillis = 1_000L)

        assertEquals(0, report.resetDownloading)
        assertEquals(1, report.reclaimedRetries)
        assertEquals(DownloadQueueStatus.QUEUED, repo.requireStatus("overdue"))
        assertEquals(DownloadQueueStatus.RETRY_WAIT, repo.requireStatus("future"))
    }

    @Test
    fun all_terminal_rows_reports_no_pending_work() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        repo.put(entry("done", DownloadQueueStatus.DOWNLOADED))
        repo.put(entry("dead", DownloadQueueStatus.FAILED))
        repo.put(entry("nope", DownloadQueueStatus.CANCELED))

        val recovery = DownloadQueueRecovery(repo)
        val report = recovery.run(nowMillis = 1_000L)

        assertFalse(
            "All entries are terminal — recovery must not flag pending work",
            report.hasPendingWork,
        )
        assertEquals(0, report.resetDownloading)
        assertEquals(0, report.reclaimedRetries)
    }

    private fun entry(id: String, status: DownloadQueueStatus): DownloadQueueEntryDto =
        DownloadQueueEntryDto(
            entryId = id,
            packageId = id,
            serverId = "s1",
            itemId = "i-$id",
            seriesId = null,
            seasonId = null,
            displayTitle = "title $id",
            seriesTitle = null,
            seasonIndex = null,
            episodeIndex = null,
            status = status,
            attempt = 0,
            maxAttempts = DownloadQueueBackoff.MAX_ATTEMPTS,
            nextAttemptAtMillis = 0L,
            failureReason = null,
            failureMessage = null,
            enqueuedAtMillis = 10L,
            updatedAtMillis = 10L,
        )
}
