package dev.jdtech.jellyfin.offline.queue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

@HiltWorker
class DownloadQueueWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val downloadQueueRunner: DownloadQueueRunner,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        setForeground(buildForegroundInfo(IDLE_TITLE))
        Timber.i("DownloadQueueWorker started")
        try {
            downloadQueueRunner.drain { entry ->
                runCatching { setForegroundAsync(buildForegroundInfo(entry.displayTitle)) }
            }
        } catch (cancel: CancellationException) {
            Timber.i("DownloadQueueWorker cancelled")
            throw cancel
        }
        Timber.i("DownloadQueueWorker finished")
        return Result.success()
    }

    private fun buildForegroundInfo(title: String): ForegroundInfo {
        ensureChannel()
        val notification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Offline downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID: String = "offline_downloads"
        const val NOTIFICATION_ID: Int = 4201
        const val NOTIFICATION_TITLE: String = "Findroid offline downloads"
        const val IDLE_TITLE: String = "Preparing queue"
    }
}
