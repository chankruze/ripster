package `in`.geekofia.ripster.download

import android.app.Notification
import android.app.PendingIntent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import `in`.geekofia.ripster.shared.R

/**
 * Media3's foreground download service (type `dataSync`). It keeps downloads running in the
 * background, shows their progress, and with [PlatformScheduler] is restarted by JobScheduler
 * when the network comes back after the process was stopped.
 */
@OptIn(UnstableApi::class)
class RipsterDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.ripster_download_channel_name,
    R.string.ripster_download_channel_description,
) {
    private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as RipsterAndroidHost).androidDownloads.downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notificationHelper.buildProgressNotification(
            this,
            R.drawable.ripster_ic_notification,
            launchAppIntent(),
            /* message= */ null,
            downloads,
            notMetRequirements,
        )

    private fun launchAppIntent(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val CHANNEL_ID = "downloads"
        const val JOB_ID = 1
    }
}
