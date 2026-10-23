package `in`.geekofia.ripster

import android.app.Application
import `in`.geekofia.ripster.download.AndroidDownloads
import `in`.geekofia.ripster.download.RipsterAndroidHost
import `in`.geekofia.ripster.platform.ActivityBridge
import `in`.geekofia.ripster.platform.androidPlatformServices

/**
 * Owns the process-wide objects: the Media3 download stack (also reached by the system-started
 * services through [RipsterAndroidHost]) and the [AppContainer], which survives Activity
 * recreation.
 */
class RipsterApplication : Application(), RipsterAndroidHost {
    val activityBridge = ActivityBridge()

    override val androidDownloads: AndroidDownloads by lazy { AndroidDownloads(this) }

    val container: AppContainer by lazy {
        AppContainer(androidPlatformServices(this, androidDownloads, activityBridge))
    }

    override fun onCreate() {
        super.onCreate()
        // Created on the main thread (Media3's DownloadManager requires it), and recovery of the
        // persisted queue starts even when a service, not the UI, started the process.
        container
    }
}
