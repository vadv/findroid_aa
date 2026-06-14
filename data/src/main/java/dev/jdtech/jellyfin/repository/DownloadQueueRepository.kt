package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.models.DownloadQueueFailureReason
import dev.jdtech.jellyfin.models.DownloadQueueStatus
import kotlinx.coroutines.flow.Flow

interface DownloadQueueRepository {
    suspend fun enqueue(entry: DownloadQueueEntryDto)

    suspend fun get(entryId: String): DownloadQueueEntryDto?

    suspend fun getByPackageId(packageId: String): DownloadQueueEntryDto?

    suspend fun getByItemId(itemId: String): List<DownloadQueueEntryDto>

    suspend fun getBySeriesId(seriesId: String): List<DownloadQueueEntryDto>

    suspend fun getBySeasonId(seasonId: String): List<DownloadQueueEntryDto>

    suspend fun getAll(): List<DownloadQueueEntryDto>

    fun observeAll(): Flow<List<DownloadQueueEntryDto>>

    fun observeByItemId(itemId: String): Flow<List<DownloadQueueEntryDto>>

    fun observeBySeriesId(seriesId: String): Flow<List<DownloadQueueEntryDto>>

    fun observeBySeasonId(seasonId: String): Flow<List<DownloadQueueEntryDto>>

    suspend fun peekNextRunnable(nowMillis: Long): DownloadQueueEntryDto?

    suspend fun nextRetryWaitWakeup(nowMillis: Long): Long?

    suspend fun updateState(
        entryId: String,
        status: DownloadQueueStatus,
        attempt: Int,
        nextAttemptAtMillis: Long,
        failureReason: DownloadQueueFailureReason?,
        failureMessage: String?,
        nowMillis: Long,
    )

    suspend fun resetInterruptedDownloading(nowMillis: Long): Int

    suspend fun reclaimOverdueRetryWaits(nowMillis: Long): Int

    suspend fun delete(entryId: String)

    suspend fun deleteByPackageId(packageId: String)

    suspend fun countLive(): Int
}
