package dev.jdtech.jellyfin.offline.queue

import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.offline.artwork.OfflineArtworkDownloader
import dev.jdtech.jellyfin.offline.download.OfflineAsset
import dev.jdtech.jellyfin.offline.download.OfflineAssetKind
import dev.jdtech.jellyfin.offline.download.OfflineAssetRequiredness
import dev.jdtech.jellyfin.offline.download.OfflineAssetStatus
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailure
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailureKind
import dev.jdtech.jellyfin.offline.download.OfflinePackageManifest
import dev.jdtech.jellyfin.offline.download.OfflinePackageReadiness
import dev.jdtech.jellyfin.offline.download.OfflineStorageScope
import dev.jdtech.jellyfin.offline.storage.DirectFileAssetResult
import dev.jdtech.jellyfin.offline.storage.DirectFileAssetStore
import dev.jdtech.jellyfin.offline.transfer.OfflineAssetTransferRunner
import dev.jdtech.jellyfin.repository.OfflinePackageRepository
import dev.jdtech.jellyfin.repository.OfflineTransferPlanResult
import dev.jdtech.jellyfin.repository.OfflineTransferPlanner
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed interface EpisodeStepOutcome {
    data object Success : EpisodeStepOutcome

    data class Retryable(val failure: OfflineDownloadFailure) : EpisodeStepOutcome

    data class Terminal(val failure: OfflineDownloadFailure) : EpisodeStepOutcome

    data object Canceled : EpisodeStepOutcome
}

interface EpisodeStepRunner {
    suspend fun run(entry: DownloadQueueEntryDto): EpisodeStepOutcome
}

class EpisodeDownloadStep
@Inject
constructor(
    private val offlinePackageRepository: OfflinePackageRepository,
    private val offlineTransferPlanner: OfflineTransferPlanner,
    private val offlineAssetTransferRunner: OfflineAssetTransferRunner,
    private val directFileAssetStore: DirectFileAssetStore,
    private val offlineArtworkDownloader: OfflineArtworkDownloader,
) : EpisodeStepRunner {
    override suspend fun run(entry: DownloadQueueEntryDto): EpisodeStepOutcome {
        val manifest =
            offlinePackageRepository.getPackage(entry.packageId)
                ?: return EpisodeStepOutcome.Terminal(
                    OfflineDownloadFailure(OfflineDownloadFailureKind.MissingRequiredAsset)
                )

        val videoAsset =
            manifest.publicVideoAsset()
                ?: return EpisodeStepOutcome.Terminal(
                    OfflineDownloadFailure(OfflineDownloadFailureKind.MissingRequiredAsset)
                )

        if (manifest.isPublicVideoPublished()) {
            runCatching { offlineArtworkDownloader.downloadPackageArtwork(manifest.packageId) }
            return EpisodeStepOutcome.Success
        }

        val plan =
            when (
                val planResult =
                    offlineTransferPlanner.planVideoTransfer(
                        itemId = manifest.itemId,
                        mediaSourceId = manifest.mediaSourceId,
                        profile = manifest.profile,
                    )
            ) {
                is OfflineTransferPlanResult.Success -> planResult.plan
                is OfflineTransferPlanResult.Failure -> {
                    writeAssetFailure(videoAsset, planResult.failure, entry.attempt)
                    return planResult.failure.toEpisodeOutcome()
                }
            }

        val prepared =
            when (
                val preparedResult =
                    directFileAssetStore.preparePublicAsset(
                        packageId = manifest.packageId,
                        assetId = videoAsset.assetId,
                        projectedPath = manifest.projectedPath,
                        expectedBytes = plan.expectedBytes,
                    )
            ) {
                is DirectFileAssetResult.Success -> preparedResult.value
                is DirectFileAssetResult.Failure -> {
                    writeAssetFailure(videoAsset, preparedResult.failure, entry.attempt)
                    return preparedResult.failure.toEpisodeOutcome()
                }
            }

        writeAssetDownloading(
            videoAsset = videoAsset,
            tempPath = prepared.tempFile.absolutePath,
            attempt = entry.attempt,
        )

        var lastProgressMillis = 0L
        return try {
            when (
                val transferResult =
                    offlineAssetTransferRunner.transferPublicAsset(
                        preparedAsset = prepared,
                        request = plan.request,
                        expectedBytes = plan.expectedBytes,
                        onBytesTransferred = { bytes ->
                            val nowMillis = System.currentTimeMillis()
                            if (nowMillis - lastProgressMillis >= PROGRESS_INTERVAL_MS) {
                                lastProgressMillis = nowMillis
                                offlinePackageRepository.setAssetState(
                                    asset = videoAsset,
                                    status = OfflineAssetStatus.DOWNLOADING,
                                    failure = null,
                                    bytes = bytes,
                                    tempPath = prepared.tempFile.absolutePath,
                                    finalPath = null,
                                    retryCount = entry.attempt,
                                    nowMillis = nowMillis,
                                )
                            }
                        },
                    )
            ) {
                is DirectFileAssetResult.Success -> {
                    val publishedFile = transferResult.value.file
                    val publishedFailure = transferResult.value.scanFailure
                    val nowMillis = System.currentTimeMillis()
                    offlinePackageRepository.setAssetState(
                        asset = videoAsset,
                        status = OfflineAssetStatus.READY,
                        failure = publishedFailure,
                        bytes = publishedFile.length(),
                        tempPath = null,
                        finalPath = publishedFile.absolutePath,
                        retryCount = entry.attempt,
                        nowMillis = nowMillis,
                    )
                    refreshReadiness(manifest.packageId, nowMillis)
                    runCatching { directFileAssetStore.cleanupTempPackage(manifest.packageId) }
                    runCatching {
                        offlineArtworkDownloader.downloadPackageArtwork(manifest.packageId)
                    }
                    EpisodeStepOutcome.Success
                }
                is DirectFileAssetResult.Failure -> {
                    writeAssetFailure(videoAsset, transferResult.failure, entry.attempt)
                    transferResult.failure.toEpisodeOutcome()
                }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                Timber.i("Download cancelled for entry=%s", entry.entryId)
                runCatching { directFileAssetStore.cleanupTempPackage(manifest.packageId) }
                val nowMillis = System.currentTimeMillis()
                offlinePackageRepository.setAssetState(
                    asset = videoAsset,
                    status = OfflineAssetStatus.FAILED_REQUIRED,
                    failure = OfflineDownloadFailure(OfflineDownloadFailureKind.Canceled),
                    bytes = null,
                    tempPath = null,
                    finalPath = null,
                    retryCount = entry.attempt,
                    nowMillis = nowMillis,
                )
                refreshReadiness(manifest.packageId, nowMillis)
            }
            throw cancel
        }
    }

    private suspend fun writeAssetFailure(
        videoAsset: OfflineAsset,
        failure: OfflineDownloadFailure,
        attempt: Int,
    ) {
        val nowMillis = System.currentTimeMillis()
        offlinePackageRepository.setAssetState(
            asset = videoAsset,
            status = failure.toAssetStatus(videoAsset.requiredness),
            failure = failure,
            bytes = null,
            tempPath = videoAsset.tempPath,
            finalPath = null,
            retryCount = attempt,
            nowMillis = nowMillis,
        )
        refreshReadiness(videoAsset.packageId, nowMillis)
    }

    private suspend fun writeAssetDownloading(
        videoAsset: OfflineAsset,
        tempPath: String,
        attempt: Int,
    ) {
        val nowMillis = System.currentTimeMillis()
        offlinePackageRepository.setAssetState(
            asset = videoAsset,
            status = OfflineAssetStatus.DOWNLOADING,
            failure = null,
            bytes = null,
            tempPath = tempPath,
            finalPath = null,
            retryCount = attempt,
            nowMillis = nowMillis,
        )
    }

    private suspend fun refreshReadiness(packageId: String, nowMillis: Long) {
        val readiness =
            offlinePackageRepository.getPackage(packageId)?.readiness
                ?: OfflinePackageReadiness.NOT_READY
        offlinePackageRepository.setPackageReadiness(packageId, readiness, nowMillis)
    }

    private fun OfflinePackageManifest.publicVideoAsset(): OfflineAsset? =
        assets.firstOrNull {
            it.kind == OfflineAssetKind.VIDEO && it.storageScope == OfflineStorageScope.PUBLIC_MEDIA
        }

    private fun OfflinePackageManifest.isPublicVideoPublished(): Boolean {
        val asset = publicVideoAsset() ?: return false
        if (asset.status != OfflineAssetStatus.READY) return false
        return directFileAssetStore.existingPublishedPublicAsset(projectedPath) != null
    }

    private fun OfflineDownloadFailure.toAssetStatus(
        requiredness: OfflineAssetRequiredness
    ): OfflineAssetStatus =
        if (requiredness == OfflineAssetRequiredness.OPTIONAL) {
            OfflineAssetStatus.FAILED_OPTIONAL
        } else {
            OfflineAssetStatus.FAILED_REQUIRED
        }

    private fun OfflineDownloadFailure.toEpisodeOutcome(): EpisodeStepOutcome {
        val classified = DownloadQueueFailureClassifier.classify(this)
        return if (classified.retryable) {
            EpisodeStepOutcome.Retryable(this)
        } else {
            EpisodeStepOutcome.Terminal(this)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS: Long = 1_000L
    }
}
