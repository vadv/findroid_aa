package dev.jdtech.jellyfin.offline

import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.models.DownloadQueueStatus
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailure
import dev.jdtech.jellyfin.offline.download.OfflineItemKind
import dev.jdtech.jellyfin.offline.download.OfflineItemSnapshot
import dev.jdtech.jellyfin.offline.download.OfflinePackageManifest
import dev.jdtech.jellyfin.offline.queue.ActiveDownloadRegistry
import dev.jdtech.jellyfin.offline.queue.DownloadQueueScheduler
import dev.jdtech.jellyfin.offline.storage.DirectFileAssetStore
import dev.jdtech.jellyfin.repository.DownloadQueueRepository
import dev.jdtech.jellyfin.repository.OfflinePackageRepository

interface OfflineDownloadManager {
    suspend fun enqueueVideoPackage(
        serverId: String,
        manifest: OfflinePackageManifest,
        itemSnapshot: OfflineItemSnapshot? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): OfflineDownloadEnqueueResult

    suspend fun retryVideoPackage(
        packageId: String,
        nowMillis: Long = System.currentTimeMillis(),
    )

    suspend fun cancelVideoPackage(
        packageId: String,
        nowMillis: Long = System.currentTimeMillis(),
    )

    suspend fun deleteVideoPackage(packageId: String)
}

sealed interface OfflineDownloadEnqueueResult {
    data object Enqueued : OfflineDownloadEnqueueResult

    data class Failed(val failure: OfflineDownloadFailure) : OfflineDownloadEnqueueResult
}

class OfflineDownloadManagerImpl(
    private val offlinePackageRepository: OfflinePackageRepository,
    private val downloadQueueRepository: DownloadQueueRepository,
    private val downloadQueueScheduler: DownloadQueueScheduler,
    private val activeDownloadRegistry: ActiveDownloadRegistry,
    private val directFileAssetStore: DirectFileAssetStore,
) : OfflineDownloadManager {

    override suspend fun enqueueVideoPackage(
        serverId: String,
        manifest: OfflinePackageManifest,
        itemSnapshot: OfflineItemSnapshot?,
        nowMillis: Long,
    ): OfflineDownloadEnqueueResult {
        offlinePackageRepository.savePackage(
            serverId = serverId,
            manifest = manifest,
            itemSnapshot = itemSnapshot,
            nowMillis = nowMillis,
        )
        directFileAssetStore.cleanupTempPackage(manifest.packageId)

        val entry =
            downloadQueueEntryFor(
                serverId = serverId,
                manifest = manifest,
                itemSnapshot = itemSnapshot,
                nowMillis = nowMillis,
            )
        downloadQueueRepository.enqueue(entry)
        downloadQueueScheduler.kick()
        return OfflineDownloadEnqueueResult.Enqueued
    }

    override suspend fun retryVideoPackage(packageId: String, nowMillis: Long) {
        val existing = downloadQueueRepository.getByPackageId(packageId) ?: return
        downloadQueueRepository.enqueue(
            existing.copy(
                status = DownloadQueueStatus.QUEUED,
                attempt = 0,
                nextAttemptAtMillis = 0L,
                failureReason = null,
                failureMessage = null,
                updatedAtMillis = nowMillis,
            )
        )
        downloadQueueScheduler.kick()
    }

    override suspend fun cancelVideoPackage(packageId: String, nowMillis: Long) {
        val existing = downloadQueueRepository.getByPackageId(packageId) ?: return
        downloadQueueRepository.updateState(
            entryId = existing.entryId,
            status = DownloadQueueStatus.CANCELED,
            attempt = existing.attempt,
            nextAttemptAtMillis = 0L,
            failureReason = null,
            failureMessage = null,
            nowMillis = nowMillis,
        )
        activeDownloadRegistry.cancelIfActive(existing.entryId)
        runCatching { directFileAssetStore.cleanupTempPackage(packageId) }
    }

    override suspend fun deleteVideoPackage(packageId: String) {
        cancelVideoPackage(packageId)
        downloadQueueRepository.deleteByPackageId(packageId)
        val manifest = offlinePackageRepository.getPackage(packageId)
        if (manifest != null) {
            val referencedFinalPaths =
                offlinePackageRepository
                    .getAllPackages()
                    .asSequence()
                    .filter { it.packageId != packageId }
                    .flatMap { it.assets.asSequence() }
                    .mapNotNull { it.finalPath }
                    .toSet()
            manifest.assets.forEach { asset ->
                if (asset.finalPath != null && asset.finalPath in referencedFinalPaths) {
                    return@forEach
                }
                runCatching {
                    when (asset.storageScope) {
                        dev.jdtech.jellyfin.offline.download.OfflineStorageScope.PUBLIC_MEDIA ->
                            directFileAssetStore.deletePublicAsset(asset.finalPath)
                        dev.jdtech.jellyfin.offline.download.OfflineStorageScope.APP_PRIVATE ->
                            directFileAssetStore.deletePrivateAsset(asset.finalPath)
                        dev.jdtech.jellyfin.offline.download.OfflineStorageScope.HIDDEN_WORK -> Unit
                    }
                }
            }
            runCatching { directFileAssetStore.cleanupTempPackage(packageId) }
            offlinePackageRepository.deletePackage(packageId)
        }
    }

    private fun downloadQueueEntryFor(
        serverId: String,
        manifest: OfflinePackageManifest,
        itemSnapshot: OfflineItemSnapshot?,
        nowMillis: Long,
    ): DownloadQueueEntryDto {
        val displayTitle =
            itemSnapshot?.displayTitleFor()
                ?: manifest.projectedPath.displayName
        return DownloadQueueEntryDto(
            entryId = manifest.packageId,
            packageId = manifest.packageId,
            serverId = serverId,
            itemId = manifest.itemId,
            seriesId = itemSnapshot?.seriesId,
            seasonId = itemSnapshot?.seasonId,
            displayTitle = displayTitle,
            seriesTitle = itemSnapshot?.seriesName,
            seasonIndex = itemSnapshot?.parentIndexNumber,
            episodeIndex = itemSnapshot?.indexNumber,
            status = DownloadQueueStatus.QUEUED,
            attempt = 0,
            maxAttempts = dev.jdtech.jellyfin.offline.queue.DownloadQueueBackoff.MAX_ATTEMPTS,
            nextAttemptAtMillis = 0L,
            failureReason = null,
            failureMessage = null,
            enqueuedAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
    }

    private fun OfflineItemSnapshot.displayTitleFor(): String =
        when (itemKind) {
            OfflineItemKind.EPISODE -> {
                val series = seriesName.orEmpty()
                val season = parentIndexNumber
                val episode = indexNumber
                if (season != null && episode != null) {
                    val prefix = "S%02dE%02d".format(season, episode)
                    if (series.isNotBlank()) "$series $prefix — $name" else "$prefix — $name"
                } else {
                    if (series.isNotBlank()) "$series — $name" else name
                }
            }
            OfflineItemKind.MOVIE -> name
        }
}
