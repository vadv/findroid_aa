package dev.jdtech.jellyfin.offline.queue

import dev.jdtech.jellyfin.models.DownloadQueueFailureReason
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailure
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailureKind

data class ClassifiedFailure(
    val reason: DownloadQueueFailureReason,
    val retryable: Boolean,
    val message: String?,
)

object DownloadQueueFailureClassifier {
    fun classify(failure: OfflineDownloadFailure): ClassifiedFailure =
        when (failure.kind) {
            OfflineDownloadFailureKind.NetworkUnavailable,
            OfflineDownloadFailureKind.ServerUnavailable,
            OfflineDownloadFailureKind.Server5xx,
            OfflineDownloadFailureKind.RateLimited,
            OfflineDownloadFailureKind.StreamInterrupted,
            OfflineDownloadFailureKind.AppInterrupted ->
                ClassifiedFailure(
                    reason = DownloadQueueFailureReason.NetworkUnavailable,
                    retryable = true,
                    message = failure.message,
                )
            OfflineDownloadFailureKind.SourceMissingOrChanged ->
                ClassifiedFailure(
                    reason = DownloadQueueFailureReason.SourceMissingOrChanged,
                    retryable = false,
                    message = failure.message,
                )
            OfflineDownloadFailureKind.MissingRequiredAsset,
            OfflineDownloadFailureKind.PathProjectionUnavailable,
            OfflineDownloadFailureKind.InvalidProjectedPath,
            OfflineDownloadFailureKind.CollisionWithForeignFile,
            OfflineDownloadFailureKind.ProfileUnsupported ->
                ClassifiedFailure(
                    reason = DownloadQueueFailureReason.SourceMissingOrChanged,
                    retryable = false,
                    message = failure.message,
                )
            OfflineDownloadFailureKind.AuthExpired,
            OfflineDownloadFailureKind.Forbidden ->
                ClassifiedFailure(
                    reason = DownloadQueueFailureReason.AuthDenied,
                    retryable = false,
                    message = failure.message,
                )
            OfflineDownloadFailureKind.IntegrityFailed,
            OfflineDownloadFailureKind.ResumeRejected,
            OfflineDownloadFailureKind.PublishFailed,
            OfflineDownloadFailureKind.ScanFailed ->
                ClassifiedFailure(
                    reason = DownloadQueueFailureReason.IntegrityFailed,
                    retryable = true,
                    message = failure.message,
                )
            OfflineDownloadFailureKind.PermissionRequired,
            OfflineDownloadFailureKind.StorageRootUnavailable,
            OfflineDownloadFailureKind.InsufficientSpace ->
                ClassifiedFailure(
                    reason = DownloadQueueFailureReason.StorageUnavailable,
                    retryable = false,
                    message = failure.message,
                )
            OfflineDownloadFailureKind.Canceled ->
                ClassifiedFailure(
                    reason = DownloadQueueFailureReason.Unknown,
                    retryable = false,
                    message = failure.message,
                )
        }
}
