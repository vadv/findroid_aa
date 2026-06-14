package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.models.DownloadQueueFailureReason
import dev.jdtech.jellyfin.models.DownloadQueueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DownloadQueueRepositoryImpl(
    private val dao: ServerDatabaseDao,
) : DownloadQueueRepository {
    override suspend fun enqueue(entry: DownloadQueueEntryDto) =
        withContext(Dispatchers.IO) { dao.upsertDownloadQueueEntry(entry) }

    override suspend fun get(entryId: String): DownloadQueueEntryDto? =
        withContext(Dispatchers.IO) { dao.getDownloadQueueEntry(entryId) }

    override suspend fun getByPackageId(packageId: String): DownloadQueueEntryDto? =
        withContext(Dispatchers.IO) { dao.getDownloadQueueEntryByPackageId(packageId) }

    override suspend fun getByItemId(itemId: String): List<DownloadQueueEntryDto> =
        withContext(Dispatchers.IO) { dao.getDownloadQueueEntriesByItemId(itemId) }

    override suspend fun getBySeriesId(seriesId: String): List<DownloadQueueEntryDto> =
        withContext(Dispatchers.IO) { dao.getDownloadQueueEntriesBySeriesId(seriesId) }

    override suspend fun getBySeasonId(seasonId: String): List<DownloadQueueEntryDto> =
        withContext(Dispatchers.IO) { dao.getDownloadQueueEntriesBySeasonId(seasonId) }

    override suspend fun getAll(): List<DownloadQueueEntryDto> =
        withContext(Dispatchers.IO) { dao.getAllDownloadQueueEntries() }

    override fun observeAll(): Flow<List<DownloadQueueEntryDto>> =
        dao.observeAllDownloadQueueEntries()

    override fun observeByItemId(itemId: String): Flow<List<DownloadQueueEntryDto>> =
        dao.observeDownloadQueueEntriesByItemId(itemId)

    override fun observeBySeriesId(seriesId: String): Flow<List<DownloadQueueEntryDto>> =
        dao.observeDownloadQueueEntriesBySeriesId(seriesId)

    override fun observeBySeasonId(seasonId: String): Flow<List<DownloadQueueEntryDto>> =
        dao.observeDownloadQueueEntriesBySeasonId(seasonId)

    override suspend fun peekNextRunnable(nowMillis: Long): DownloadQueueEntryDto? =
        withContext(Dispatchers.IO) { dao.peekNextRunnableDownloadQueueEntry(nowMillis) }

    override suspend fun nextRetryWaitWakeup(nowMillis: Long): Long? =
        withContext(Dispatchers.IO) { dao.nextRetryWaitWakeupMillis(nowMillis) }

    override suspend fun updateState(
        entryId: String,
        status: DownloadQueueStatus,
        attempt: Int,
        nextAttemptAtMillis: Long,
        failureReason: DownloadQueueFailureReason?,
        failureMessage: String?,
        nowMillis: Long,
    ) =
        withContext(Dispatchers.IO) {
            dao.setDownloadQueueEntryState(
                entryId = entryId,
                status = status,
                attempt = attempt,
                nextAttemptAtMillis = nextAttemptAtMillis,
                failureReason = failureReason,
                failureMessage = failureMessage,
                updatedAtMillis = nowMillis,
            )
        }

    override suspend fun resetInterruptedDownloading(nowMillis: Long): Int =
        withContext(Dispatchers.IO) { dao.resetInterruptedDownloadingEntries(nowMillis) }

    override suspend fun reclaimOverdueRetryWaits(nowMillis: Long): Int =
        withContext(Dispatchers.IO) { dao.reclaimOverdueRetryWaitEntries(nowMillis) }

    override suspend fun delete(entryId: String) =
        withContext(Dispatchers.IO) { dao.deleteDownloadQueueEntry(entryId) }

    override suspend fun deleteByPackageId(packageId: String) =
        withContext(Dispatchers.IO) { dao.deleteDownloadQueueEntryByPackageId(packageId) }

    override suspend fun countLive(): Int =
        withContext(Dispatchers.IO) { dao.countLiveDownloadQueueEntries() }
}
