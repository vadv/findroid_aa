package dev.jdtech.jellyfin.di

import android.app.Application
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.offline.OfflineDownloadManager
import dev.jdtech.jellyfin.offline.OfflineDownloadManagerImpl
import dev.jdtech.jellyfin.offline.download.OfflinePackageManifestFactory
import dev.jdtech.jellyfin.offline.queue.ActiveDownloadRegistry
import dev.jdtech.jellyfin.offline.queue.DownloadQueueScheduler
import dev.jdtech.jellyfin.offline.queue.EpisodeDownloadStep
import dev.jdtech.jellyfin.offline.queue.EpisodeStepRunner
import dev.jdtech.jellyfin.offline.queue.WorkManagerDownloadQueueScheduler
import dev.jdtech.jellyfin.offline.storage.AllFilesAccessHelper
import dev.jdtech.jellyfin.offline.storage.DirectFileAssetStore
import dev.jdtech.jellyfin.offline.storage.OfflineTempPackageCleaner
import dev.jdtech.jellyfin.offline.transfer.OfflineAssetTransferRunner
import dev.jdtech.jellyfin.offline.transfer.OfflineVideoPostProcessor
import dev.jdtech.jellyfin.repository.DownloadQueueRepository
import dev.jdtech.jellyfin.repository.DownloadQueueRepositoryImpl
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.OfflinePackageRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.DownloaderImpl
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DownloaderModule {
    @Singleton
    @Provides
    fun provideOfflinePackageManifestFactory(): OfflinePackageManifestFactory {
        return OfflinePackageManifestFactory()
    }

    @Singleton
    @Provides
    fun provideDirectFileAssetStore(application: Application): DirectFileAssetStore {
        return DirectFileAssetStore(application)
    }

    @Singleton
    @Provides
    fun provideOfflineTempPackageCleaner(
        directFileAssetStore: DirectFileAssetStore
    ): OfflineTempPackageCleaner = directFileAssetStore

    @Singleton
    @Provides
    fun provideAllFilesAccessHelper(application: Application): AllFilesAccessHelper {
        return AllFilesAccessHelper(application)
    }

    @Singleton
    @Provides
    fun provideOfflineDownloadOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

    @Singleton
    @Provides
    fun provideOfflineVideoPostProcessor(): OfflineVideoPostProcessor {
        return OfflineVideoPostProcessor()
    }

    @Singleton
    @Provides
    fun provideOfflineAssetTransferRunner(
        okHttpClient: OkHttpClient,
        directFileAssetStore: DirectFileAssetStore,
        offlineVideoPostProcessor: OfflineVideoPostProcessor,
    ): OfflineAssetTransferRunner {
        return OfflineAssetTransferRunner(
            okHttpClient = okHttpClient,
            directFileAssetStore = directFileAssetStore,
            offlineVideoPostProcessor = offlineVideoPostProcessor,
        )
    }

    @Singleton
    @Provides
    fun provideDownloadQueueRepository(
        dao: ServerDatabaseDao
    ): DownloadQueueRepository = DownloadQueueRepositoryImpl(dao)

    @Singleton
    @Provides
    fun provideDownloadQueueScheduler(
        workManager: WorkManager
    ): DownloadQueueScheduler = WorkManagerDownloadQueueScheduler(workManager)

    @Singleton
    @Provides
    fun provideEpisodeStepRunner(step: EpisodeDownloadStep): EpisodeStepRunner = step

    @Singleton
    @Provides
    fun provideOfflineDownloadManager(
        offlinePackageRepository: OfflinePackageRepository,
        downloadQueueRepository: DownloadQueueRepository,
        downloadQueueScheduler: DownloadQueueScheduler,
        activeDownloadRegistry: ActiveDownloadRegistry,
        directFileAssetStore: DirectFileAssetStore,
    ): OfflineDownloadManager {
        return OfflineDownloadManagerImpl(
            offlinePackageRepository = offlinePackageRepository,
            downloadQueueRepository = downloadQueueRepository,
            downloadQueueScheduler = downloadQueueScheduler,
            activeDownloadRegistry = activeDownloadRegistry,
            directFileAssetStore = directFileAssetStore,
        )
    }

    @Provides
    fun provideDownloadQueueRunnerClock(): dev.jdtech.jellyfin.offline.queue.DownloadQueueRunner.Clock =
        dev.jdtech.jellyfin.offline.queue.DownloadQueueRunner.SystemClock

    @Singleton
    @Provides
    fun provideDownloader(
        application: Application,
        serverDatabase: ServerDatabaseDao,
        jellyfinRepository: JellyfinRepository,
        appPreferences: AppPreferences,
        workManager: WorkManager,
    ): Downloader {
        return DownloaderImpl(
            application,
            serverDatabase,
            jellyfinRepository,
            appPreferences,
            workManager,
        )
    }
}
