package dev.jdtech.jellyfin.offline.queue

import dev.jdtech.jellyfin.models.DownloadQueueFailureReason
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailure
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueFailureClassifierTest {
    @Test
    fun network_failures_become_retryable_network_unavailable() {
        listOf(
            OfflineDownloadFailureKind.NetworkUnavailable,
            OfflineDownloadFailureKind.ServerUnavailable,
            OfflineDownloadFailureKind.Server5xx,
            OfflineDownloadFailureKind.RateLimited,
            OfflineDownloadFailureKind.StreamInterrupted,
            OfflineDownloadFailureKind.AppInterrupted,
        ).forEach { kind ->
            val classified = DownloadQueueFailureClassifier.classify(OfflineDownloadFailure(kind))
            assertEquals(
                "Kind $kind should map to NetworkUnavailable",
                DownloadQueueFailureReason.NetworkUnavailable,
                classified.reason,
            )
            assertTrue("Kind $kind should be retryable", classified.retryable)
        }
    }

    @Test
    fun source_missing_or_changed_is_terminal() {
        val classified =
            DownloadQueueFailureClassifier.classify(
                OfflineDownloadFailure(OfflineDownloadFailureKind.SourceMissingOrChanged)
            )
        assertEquals(DownloadQueueFailureReason.SourceMissingOrChanged, classified.reason)
        assertFalse(classified.retryable)
    }

    @Test
    fun auth_failures_are_terminal_auth_denied() {
        listOf(OfflineDownloadFailureKind.AuthExpired, OfflineDownloadFailureKind.Forbidden)
            .forEach { kind ->
                val classified =
                    DownloadQueueFailureClassifier.classify(OfflineDownloadFailure(kind))
                assertEquals(DownloadQueueFailureReason.AuthDenied, classified.reason)
                assertFalse("Auth failures must not retry", classified.retryable)
            }
    }

    @Test
    fun integrity_failures_are_retryable() {
        listOf(
            OfflineDownloadFailureKind.IntegrityFailed,
            OfflineDownloadFailureKind.ResumeRejected,
            OfflineDownloadFailureKind.PublishFailed,
            OfflineDownloadFailureKind.ScanFailed,
        ).forEach { kind ->
            val classified = DownloadQueueFailureClassifier.classify(OfflineDownloadFailure(kind))
            assertEquals(DownloadQueueFailureReason.IntegrityFailed, classified.reason)
            assertTrue("Integrity kind $kind should be retryable", classified.retryable)
        }
    }

    @Test
    fun storage_failures_are_terminal_storage_unavailable() {
        listOf(
            OfflineDownloadFailureKind.PermissionRequired,
            OfflineDownloadFailureKind.StorageRootUnavailable,
            OfflineDownloadFailureKind.InsufficientSpace,
        ).forEach { kind ->
            val classified = DownloadQueueFailureClassifier.classify(OfflineDownloadFailure(kind))
            assertEquals(DownloadQueueFailureReason.StorageUnavailable, classified.reason)
            assertFalse(classified.retryable)
        }
    }
}
