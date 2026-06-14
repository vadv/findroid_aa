package dev.jdtech.jellyfin.offline.queue

import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.models.DownloadQueueFailureReason
import dev.jdtech.jellyfin.models.DownloadQueueStatus
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailure
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailureKind
import dev.jdtech.jellyfin.repository.DownloadQueueRepository
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueRunnerTest {

    @Test
    fun drains_sequentially_in_enqueue_order() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        val order = mutableListOf<String>()
        val step = FakeStepRunner { entry ->
            order += entry.entryId
            EpisodeStepOutcome.Success
        }
        val scheduler = FakeScheduler()
        val runner =
            DownloadQueueRunner(
                downloadQueueRepository = repo,
                episodeStepRunner = step,
                activeDownloadRegistry = ActiveDownloadRegistry(),
                downloadQueueScheduler = scheduler,
                clock = { 100L },
            )

        repo.put(entry("a", enqueuedAt = 10L))
        repo.put(entry("b", enqueuedAt = 20L))
        repo.put(entry("c", enqueuedAt = 30L))

        runner.drain()

        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(DownloadQueueStatus.DOWNLOADED, repo.requireStatus("a"))
        assertEquals(DownloadQueueStatus.DOWNLOADED, repo.requireStatus("b"))
        assertEquals(DownloadQueueStatus.DOWNLOADED, repo.requireStatus("c"))
    }

    @Test
    fun terminal_failure_does_not_block_next_item() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        val processed = mutableListOf<String>()
        val step = FakeStepRunner { entry ->
            processed += entry.entryId
            when (entry.entryId) {
                "bad" ->
                    EpisodeStepOutcome.Terminal(
                        OfflineDownloadFailure(OfflineDownloadFailureKind.SourceMissingOrChanged)
                    )
                else -> EpisodeStepOutcome.Success
            }
        }
        val runner =
            DownloadQueueRunner(
                downloadQueueRepository = repo,
                episodeStepRunner = step,
                activeDownloadRegistry = ActiveDownloadRegistry(),
                downloadQueueScheduler = FakeScheduler(),
                clock = { 100L },
            )

        repo.put(entry("bad", enqueuedAt = 10L))
        repo.put(entry("good", enqueuedAt = 20L))

        runner.drain()

        assertEquals(
            "Failed entry must not prevent next from running",
            listOf("bad", "good"),
            processed,
        )
        assertEquals(DownloadQueueStatus.FAILED, repo.requireStatus("bad"))
        assertEquals(
            DownloadQueueFailureReason.SourceMissingOrChanged,
            repo.require("bad").failureReason,
        )
        assertEquals(DownloadQueueStatus.DOWNLOADED, repo.requireStatus("good"))
    }

    @Test
    fun retryable_failure_writes_retry_wait_with_backoff() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        var seenAttempt = -1
        val step = FakeStepRunner { entry ->
            seenAttempt = entry.attempt
            EpisodeStepOutcome.Retryable(
                OfflineDownloadFailure(OfflineDownloadFailureKind.NetworkUnavailable)
            )
        }
        val clockValue = AtomicReference(1000L)
        val runner =
            DownloadQueueRunner(
                downloadQueueRepository = repo,
                episodeStepRunner = step,
                activeDownloadRegistry = ActiveDownloadRegistry(),
                downloadQueueScheduler = FakeScheduler(),
                clock = { clockValue.get() },
            )

        repo.put(entry("net", enqueuedAt = 10L))

        runner.drain()

        assertEquals(1, seenAttempt)
        val stored = repo.require("net")
        assertEquals(DownloadQueueStatus.RETRY_WAIT, stored.status)
        assertEquals(1, stored.attempt)
        assertEquals(
            "First retry should wait DownloadQueueBackoff.delayForAttempt(1)",
            1000L + DownloadQueueBackoff.delayForAttempt(1),
            stored.nextAttemptAtMillis,
        )
        assertEquals(DownloadQueueFailureReason.NetworkUnavailable, stored.failureReason)
    }

    @Test
    fun after_max_attempts_retryable_failure_becomes_failed_not_retry_wait() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        val step = FakeStepRunner {
            EpisodeStepOutcome.Retryable(
                OfflineDownloadFailure(OfflineDownloadFailureKind.NetworkUnavailable)
            )
        }
        val runner =
            DownloadQueueRunner(
                downloadQueueRepository = repo,
                episodeStepRunner = step,
                activeDownloadRegistry = ActiveDownloadRegistry(),
                downloadQueueScheduler = FakeScheduler(),
                clock = { 100L },
            )

        // Pretend the entry has already failed MAX_ATTEMPTS - 1 times.
        repo.put(
            entry("net", enqueuedAt = 10L).copy(attempt = DownloadQueueBackoff.MAX_ATTEMPTS - 1)
        )

        runner.drain()

        val stored = repo.require("net")
        assertEquals(DownloadQueueStatus.FAILED, stored.status)
        assertEquals(DownloadQueueBackoff.MAX_ATTEMPTS, stored.attempt)
        assertEquals(DownloadQueueFailureReason.NetworkUnavailable, stored.failureReason)
    }

    @Test
    fun long_retry_wait_hands_off_to_scheduler_and_exits() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        val scheduler = FakeScheduler()
        val runner =
            DownloadQueueRunner(
                downloadQueueRepository = repo,
                episodeStepRunner = FakeStepRunner { EpisodeStepOutcome.Success },
                activeDownloadRegistry = ActiveDownloadRegistry(),
                downloadQueueScheduler = scheduler,
                clock = { 0L },
            )

        // Single entry in RETRY_WAIT with a wakeup > IN_WORKER_SLEEP_THRESHOLD
        val wakeupAt = DownloadQueueBackoff.IN_WORKER_SLEEP_THRESHOLD_MILLIS + 60_000L
        repo.put(
            entry("waiting", enqueuedAt = 10L)
                .copy(status = DownloadQueueStatus.RETRY_WAIT, nextAttemptAtMillis = wakeupAt)
        )

        runner.drain()

        assertEquals(
            "Worker must hand off long backoff to scheduler",
            listOf(wakeupAt),
            scheduler.delayedKicks,
        )
        // Status untouched — scheduler will fire later
        assertEquals(DownloadQueueStatus.RETRY_WAIT, repo.requireStatus("waiting"))
    }

    @Test
    fun empty_queue_exits_without_kicking_scheduler() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        val scheduler = FakeScheduler()
        val runner =
            DownloadQueueRunner(
                downloadQueueRepository = repo,
                episodeStepRunner = FakeStepRunner { EpisodeStepOutcome.Success },
                activeDownloadRegistry = ActiveDownloadRegistry(),
                downloadQueueScheduler = scheduler,
                clock = { 0L },
            )

        runner.drain()

        assertTrue(scheduler.kickCount == 0)
        assertTrue(scheduler.delayedKicks.isEmpty())
    }

    @Test
    fun canceled_entry_in_db_is_left_as_is_after_step_returns_canceled() = runBlocking {
        val repo = FakeDownloadQueueRepository()
        val registry = ActiveDownloadRegistry()

        // Step suspends until cancelled, then returns by throwing CancellationException.
        // The runner translates to EpisodeStepOutcome.Canceled.
        val step =
            FakeStepRunner { entry ->
                // Wait for cancel — propagate cancellation when the deferred is cancelled.
                CompletableDeferred<EpisodeStepOutcome>().await()
            }
        val runner =
            DownloadQueueRunner(
                downloadQueueRepository = repo,
                episodeStepRunner = step,
                activeDownloadRegistry = registry,
                downloadQueueScheduler = FakeScheduler(),
                clock = { 100L },
            )

        repo.put(entry("c", enqueuedAt = 10L))

        // Run drain in background; cancel mid-flight via registry.
        val drainJob =
            GlobalScope.async(Dispatchers.Default) {
                runner.drain()
            }

        // Wait until the entry is in DOWNLOADING (i.e., step was called).
        waitUntil { repo.requireStatus("c") == DownloadQueueStatus.DOWNLOADING }

        // External cancel:
        repo.put(repo.require("c").copy(status = DownloadQueueStatus.CANCELED))
        assertTrue(registry.cancelIfActive("c"))

        drainJob.await()

        assertEquals(
            "Runner must not overwrite CANCELED written externally",
            DownloadQueueStatus.CANCELED,
            repo.requireStatus("c"),
        )
    }

    private fun entry(id: String, enqueuedAt: Long): DownloadQueueEntryDto =
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
            status = DownloadQueueStatus.QUEUED,
            attempt = 0,
            maxAttempts = DownloadQueueBackoff.MAX_ATTEMPTS,
            nextAttemptAtMillis = 0L,
            failureReason = null,
            failureMessage = null,
            enqueuedAtMillis = enqueuedAt,
            updatedAtMillis = enqueuedAt,
        )

    private suspend fun waitUntil(timeoutMs: Long = 2_000L, check: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!check()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                error("waitUntil timed out")
            }
            yield()
            delay(5)
        }
    }
}

private class FakeStepRunner(
    private val behaviour: suspend (DownloadQueueEntryDto) -> EpisodeStepOutcome,
) : EpisodeStepRunner {
    override suspend fun run(entry: DownloadQueueEntryDto): EpisodeStepOutcome = behaviour(entry)
}

private class FakeScheduler : DownloadQueueScheduler {
    var kickCount: Int = 0
    val delayedKicks: MutableList<Long> = mutableListOf()
    var cancelCount: Int = 0

    override fun kick() {
        kickCount += 1
    }

    override fun kickWithDelay(delayMillis: Long) {
        delayedKicks += delayMillis
    }

    override fun cancelWorker() {
        cancelCount += 1
    }
}

internal class FakeDownloadQueueRepository : DownloadQueueRepository {
    private val mutex = Mutex()
    private val store: LinkedHashMap<String, DownloadQueueEntryDto> = LinkedHashMap()
    private val stateFlow = MutableStateFlow<List<DownloadQueueEntryDto>>(emptyList())

    suspend fun put(entry: DownloadQueueEntryDto) {
        mutex.withLock {
            store[entry.entryId] = entry
            stateFlow.value = store.values.toList()
        }
    }

    suspend fun require(entryId: String): DownloadQueueEntryDto =
        mutex.withLock {
            requireNotNull(store[entryId]) { "entry $entryId missing" }
        }

    suspend fun requireStatus(entryId: String): DownloadQueueStatus = require(entryId).status

    override suspend fun enqueue(entry: DownloadQueueEntryDto) = put(entry)

    override suspend fun get(entryId: String): DownloadQueueEntryDto? =
        mutex.withLock { store[entryId] }

    override suspend fun getByPackageId(packageId: String): DownloadQueueEntryDto? =
        mutex.withLock { store.values.firstOrNull { it.packageId == packageId } }

    override suspend fun getByItemId(itemId: String): List<DownloadQueueEntryDto> =
        mutex.withLock { store.values.filter { it.itemId == itemId } }

    override suspend fun getBySeriesId(seriesId: String): List<DownloadQueueEntryDto> =
        mutex.withLock { store.values.filter { it.seriesId == seriesId } }

    override suspend fun getBySeasonId(seasonId: String): List<DownloadQueueEntryDto> =
        mutex.withLock { store.values.filter { it.seasonId == seasonId } }

    override suspend fun getAll(): List<DownloadQueueEntryDto> =
        mutex.withLock { store.values.toList() }

    override fun observeAll(): Flow<List<DownloadQueueEntryDto>> = stateFlow

    override fun observeByItemId(itemId: String): Flow<List<DownloadQueueEntryDto>> =
        stateFlow.map { all -> all.filter { it.itemId == itemId } }

    override fun observeBySeriesId(seriesId: String): Flow<List<DownloadQueueEntryDto>> =
        stateFlow.map { all -> all.filter { it.seriesId == seriesId } }

    override fun observeBySeasonId(seasonId: String): Flow<List<DownloadQueueEntryDto>> =
        stateFlow.map { all -> all.filter { it.seasonId == seasonId } }

    override suspend fun peekNextRunnable(nowMillis: Long): DownloadQueueEntryDto? =
        mutex.withLock {
            store.values
                .filter {
                    it.status == DownloadQueueStatus.QUEUED ||
                        (it.status == DownloadQueueStatus.RETRY_WAIT &&
                            it.nextAttemptAtMillis <= nowMillis)
                }
                .minByOrNull { it.enqueuedAtMillis }
        }

    override suspend fun nextRetryWaitWakeup(nowMillis: Long): Long? =
        mutex.withLock {
            store.values
                .filter {
                    it.status == DownloadQueueStatus.RETRY_WAIT && it.nextAttemptAtMillis > nowMillis
                }
                .minByOrNull { it.nextAttemptAtMillis }
                ?.nextAttemptAtMillis
        }

    override suspend fun updateState(
        entryId: String,
        status: DownloadQueueStatus,
        attempt: Int,
        nextAttemptAtMillis: Long,
        failureReason: DownloadQueueFailureReason?,
        failureMessage: String?,
        nowMillis: Long,
    ) {
        mutex.withLock {
            val existing = store[entryId] ?: return@withLock
            store[entryId] =
                existing.copy(
                    status = status,
                    attempt = attempt,
                    nextAttemptAtMillis = nextAttemptAtMillis,
                    failureReason = failureReason,
                    failureMessage = failureMessage,
                    updatedAtMillis = nowMillis,
                )
            stateFlow.value = store.values.toList()
        }
    }

    override suspend fun resetInterruptedDownloading(nowMillis: Long): Int =
        mutex.withLock {
            var count = 0
            store.values
                .filter { it.status == DownloadQueueStatus.DOWNLOADING }
                .forEach { entry ->
                    store[entry.entryId] =
                        entry.copy(
                            status = DownloadQueueStatus.QUEUED,
                            nextAttemptAtMillis = 0L,
                            updatedAtMillis = nowMillis,
                        )
                    count += 1
                }
            stateFlow.value = store.values.toList()
            count
        }

    override suspend fun reclaimOverdueRetryWaits(nowMillis: Long): Int =
        mutex.withLock {
            var count = 0
            store.values
                .filter {
                    it.status == DownloadQueueStatus.RETRY_WAIT &&
                        it.nextAttemptAtMillis <= nowMillis
                }
                .forEach { entry ->
                    store[entry.entryId] =
                        entry.copy(
                            status = DownloadQueueStatus.QUEUED,
                            nextAttemptAtMillis = 0L,
                            updatedAtMillis = nowMillis,
                        )
                    count += 1
                }
            stateFlow.value = store.values.toList()
            count
        }

    override suspend fun delete(entryId: String) {
        mutex.withLock {
            store.remove(entryId)
            stateFlow.value = store.values.toList()
        }
    }

    override suspend fun deleteByPackageId(packageId: String) {
        mutex.withLock {
            store.values.filter { it.packageId == packageId }.forEach { store.remove(it.entryId) }
            stateFlow.value = store.values.toList()
        }
    }

    override suspend fun countLive(): Int =
        mutex.withLock {
            store.values.count {
                it.status == DownloadQueueStatus.QUEUED ||
                    it.status == DownloadQueueStatus.DOWNLOADING ||
                    it.status == DownloadQueueStatus.RETRY_WAIT
            }
        }
}
