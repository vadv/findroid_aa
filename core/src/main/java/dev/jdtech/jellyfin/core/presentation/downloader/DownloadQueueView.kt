package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.models.DownloadQueueFailureReason
import dev.jdtech.jellyfin.models.DownloadQueueStatus

data class DownloadQueueView(
    val packageId: String,
    val displayTitle: String,
    val seriesTitle: String?,
    val seasonIndex: Int?,
    val episodeIndex: Int?,
    val status: DownloadQueueStatus,
    val attempt: Int,
    val maxAttempts: Int,
    val nextAttemptAtMillis: Long,
    val failureReason: DownloadQueueFailureReason?,
    val failureMessage: String?,
    val canRetry: Boolean,
)

fun DownloadQueueEntryDto.toDownloadQueueView(): DownloadQueueView =
    DownloadQueueView(
        packageId = packageId,
        displayTitle = displayTitle,
        seriesTitle = seriesTitle,
        seasonIndex = seasonIndex,
        episodeIndex = episodeIndex,
        status = status,
        attempt = attempt,
        maxAttempts = maxAttempts,
        nextAttemptAtMillis = nextAttemptAtMillis,
        failureReason = failureReason,
        failureMessage = failureMessage,
        canRetry = status == DownloadQueueStatus.FAILED || status == DownloadQueueStatus.CANCELED,
    )
