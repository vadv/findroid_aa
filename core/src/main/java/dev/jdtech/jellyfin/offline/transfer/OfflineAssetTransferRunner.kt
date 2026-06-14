package dev.jdtech.jellyfin.offline.transfer

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailure
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailureKind
import dev.jdtech.jellyfin.offline.download.OfflineTransferRequest
import dev.jdtech.jellyfin.offline.storage.DirectFileAssetResult
import dev.jdtech.jellyfin.offline.storage.DirectFileAssetStore
import dev.jdtech.jellyfin.offline.storage.OfflineStorageFailureMapper
import dev.jdtech.jellyfin.offline.storage.PreparedPublicAsset
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class OfflineAssetTransferRunner(
    private val okHttpClient: OkHttpClient,
    private val directFileAssetStore: DirectFileAssetStore,
    private val offlineVideoPostProcessor: OfflineVideoPostProcessor,
) {
    private val rangeDownloader =
        OfflineRangeDownloader(
            okHttpClient = okHttpClient,
            mediaIntegrityProbe = ::hasReadableMediaMetadata,
        )

    suspend fun transferPublicAsset(
        preparedAsset: PreparedPublicAsset,
        request: OfflineTransferRequest,
        expectedBytes: Long? = null,
        onBytesTransferred: suspend (Long) -> Unit = {},
    ): DirectFileAssetResult<PublishedPublicAsset> =
        withContext(Dispatchers.IO) {
            try {
                when (
                    val outcome =
                        rangeDownloader.download(
                            tempFile = preparedAsset.tempFile,
                            packageId = preparedAsset.packageId,
                            request = request,
                            expectedBytes = expectedBytes,
                            onBytesTransferred = onBytesTransferred,
                        )
                ) {
                    is RangeDownloadOutcome.Failure ->
                        return@withContext DirectFileAssetResult.Failure(outcome.failure)
                    RangeDownloadOutcome.Success -> Unit
                }

                when (
                    val postProcessResult =
                        offlineVideoPostProcessor.ensureSeekableMp4(
                            file = preparedAsset.tempFile,
                            label = "download:${preparedAsset.packageId}",
                        )
                ) {
                    is DirectFileAssetResult.Success -> Unit
                    is DirectFileAssetResult.Failure -> return@withContext postProcessResult
                }

                val publishedFile =
                    when (val publishResult = directFileAssetStore.publishPublicAsset(preparedAsset)) {
                        is DirectFileAssetResult.Success -> publishResult.value
                        is DirectFileAssetResult.Failure -> return@withContext publishResult
                    }
                val scanFailure =
                    when (val scanResult = directFileAssetStore.scanPublicAsset(publishedFile)) {
                        is DirectFileAssetResult.Success -> null
                        is DirectFileAssetResult.Failure -> scanResult.failure
                    }
                DirectFileAssetResult.Success(
                    PublishedPublicAsset(file = publishedFile, scanFailure = scanFailure)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                DirectFileAssetResult.Failure(
                    OfflineDownloadFailure(OfflineDownloadFailureKind.PermissionRequired, e.message)
                )
            } catch (e: IllegalArgumentException) {
                DirectFileAssetResult.Failure(
                    OfflineDownloadFailure(OfflineDownloadFailureKind.ProfileUnsupported, e.message)
                )
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                DirectFileAssetResult.Failure(
                    OfflineDownloadFailure(
                        OfflineTransferFailureMapper.fromIOException(
                            error = e,
                            hasNoUsableSpace =
                                e is TransferStorageIOException &&
                                    preparedAsset.hasNoUsableSpace(),
                        ),
                        e.message,
                    )
                )
            }
        }

    private fun hasReadableMediaMetadata(file: File): Boolean =
        hasReadableMediaMetadataRetriever(file) || hasVideoTrack(file)

    private fun hasReadableMediaMetadataRetriever(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val durationMillis =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            hasVideo && durationMillis > 0L
        } catch (_: RuntimeException) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun hasVideoTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { trackIndex ->
                val mime = extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)
                mime?.startsWith("video/") == true
            }
        } catch (_: RuntimeException) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }
}

data class PublishedPublicAsset(
    val file: File,
    val scanFailure: OfflineDownloadFailure? = null,
)

internal object OfflineTransferFailureMapper {
    fun fromHttpStatus(status: Int): OfflineDownloadFailureKind =
        when (status) {
            401 -> OfflineDownloadFailureKind.AuthExpired
            403 -> OfflineDownloadFailureKind.Forbidden
            404, 410 -> OfflineDownloadFailureKind.SourceMissingOrChanged
            408 -> OfflineDownloadFailureKind.ServerUnavailable
            409, 412, 416 -> OfflineDownloadFailureKind.ResumeRejected
            429 -> OfflineDownloadFailureKind.RateLimited
            in 400..499 -> OfflineDownloadFailureKind.ProfileUnsupported
            in 500..599 -> OfflineDownloadFailureKind.Server5xx
            else -> OfflineDownloadFailureKind.ServerUnavailable
        }

    fun fromIOException(
        error: IOException,
        hasNoUsableSpace: Boolean = false,
    ): OfflineDownloadFailureKind =
        when (error) {
            is TransferStorageIOException ->
                OfflineStorageFailureMapper.fromIOException(
                    error = error.cause as IOException,
                    hasNoUsableSpace = hasNoUsableSpace,
                )
            is TransferNetworkIOException -> fromIOException(error.cause as IOException)
            is UnknownHostException,
            is NoRouteToHostException -> OfflineDownloadFailureKind.NetworkUnavailable
            is ConnectException,
            is SocketTimeoutException -> OfflineDownloadFailureKind.ServerUnavailable
            is SSLException -> OfflineDownloadFailureKind.StreamInterrupted
            else -> OfflineDownloadFailureKind.StreamInterrupted
        }
}

private fun PreparedPublicAsset.hasNoUsableSpace(): Boolean {
    val minimumUsefulFreeBytes = 1L * 1024L * 1024L
    return sequenceOf(tempFile.parentFile, finalFile.parentFile)
        .filterNotNull()
        .any { it.usableSpace < minimumUsefulFreeBytes }
}
