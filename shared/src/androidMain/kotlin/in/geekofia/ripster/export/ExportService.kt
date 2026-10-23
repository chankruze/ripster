package `in`.geekofia.ripster.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import `in`.geekofia.ripster.download.RipsterAndroidHost
import `in`.geekofia.ripster.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What's being exported right now, shared between the exporter and [ExportService]. */
internal class ExportActivity {
    private val active = MutableStateFlow(0)
    private val current = MutableStateFlow<Pair<String, Float?>?>(null)

    val isActive: StateFlow<Int> = active.asStateFlow()
    val progress: StateFlow<Pair<String, Float?>?> = current.asStateFlow()

    /** Keeps the process in the foreground while an export runs. */
    fun begin(context: Context, title: String) {
        current.value = title to null
        if (active.getAndIncrement() == 0) ExportService.start(context)
    }

    fun report(progress: Float?) {
        current.update { it?.copy(second = progress) }
    }

    fun end() {
        active.update { (it - 1).coerceAtLeast(0) }
        if (active.value == 0) current.value = null
    }

    private fun MutableStateFlow<Int>.getAndIncrement(): Int {
        while (true) {
            val value = this.value
            if (compareAndSet(value, value + 1)) return value
        }
    }
}

/**
 * A foreground service that only keeps the process alive while an MP4 export runs, because the
 * download service stops being foreground as soon as the last download finishes. Typed
 * `mediaProcessing` on Android 15+, `dataSync` before.
 *
 * Sticky: if the system kills the process mid-export, it restarts the service, the Application
 * recovers the queue and the export starts again. With nothing to export it stops itself.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activity get() = (application as RipsterAndroidHost).androidDownloads.exportActivity

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!startInForeground(notification(null, null))) {
            stopSelf()
            return
        }
        scope.launch {
            // A sticky restart comes before recovery restarts the export: give it time to begin.
            delay(START_GRACE_MS)
            activity.isActive.first { it == 0 }
            stop()
        }
        scope.launch {
            combine(activity.isActive, activity.progress) { _, progress -> progress }
                .sample(NOTIFICATION_INTERVAL_MS)
                .collect { progress ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(progress?.first, progress?.second))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Android 15's time limit for the service type: stop rather than be stopped. */
    override fun onTimeout(startId: Int, fgsType: Int) = stop()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Calls the platform API directly: ServiceCompat's API 34 path drops type bits it doesn't
     * know, turning `mediaProcessing` (API 35) into "none", which Android rejects with a crash.
     * Returns false if the system refuses; the export then continues without foreground status.
     */
    private fun startInForeground(notification: Notification): Boolean = try {
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: RuntimeException) {
        // E.g. ForegroundServiceStartNotAllowedException or a type the device rejects.
        Log.w(TAG, "Export continues without a foreground service: ${e.javaClass.simpleName}")
        false
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.ripster_export_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.ripster_export_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(title: String?, progress: Float?): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ripster_ic_notification)
            .setContentTitle(getString(R.string.ripster_export_notification_title))
            .setContentText(title)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
    }

    internal companion object {
        private const val TAG = "ExportService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "exports"
        private const val START_GRACE_MS = 15_000L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed from the background (Android 12+): the export still runs in-process,
                // it's just not protected from being killed. If it is, recovery restarts it.
            }
        }
    }
}
