package dev.jdtech.jellyfin.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadQueueStatus {
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    RETRY_WAIT,
    FAILED,
    CANCELED,
}

enum class DownloadQueueFailureReason {
    NetworkUnavailable,
    SourceMissingOrChanged,
    AuthDenied,
    IntegrityFailed,
    StorageUnavailable,
    Unknown,
}

@Entity(
    tableName = "downloadQueue",
    indices = [
        Index("status"),
        Index("serverId"),
        Index("itemId"),
        Index("seriesId"),
        Index("nextAttemptAtMillis"),
    ],
)
data class DownloadQueueEntryDto(
    @PrimaryKey val entryId: String,
    val packageId: String,
    val serverId: String,
    val itemId: String,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val displayTitle: String,
    val seriesTitle: String? = null,
    val seasonIndex: Int? = null,
    val episodeIndex: Int? = null,
    val status: DownloadQueueStatus,
    val attempt: Int = 0,
    val maxAttempts: Int = 5,
    val nextAttemptAtMillis: Long = 0L,
    val failureReason: DownloadQueueFailureReason? = null,
    val failureMessage: String? = null,
    val enqueuedAtMillis: Long,
    val updatedAtMillis: Long,
)
