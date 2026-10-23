package `in`.geekofia.ripster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import `in`.geekofia.ripster.download.RipsterDownloadService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val application = application as RipsterApplication
        application.activityBridge.attach(this)
        setContent {
            App(application.container)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        // Resumes unfinished downloads in the service (allowed: the app is in the foreground).
        // The service stops itself straight away when there is nothing to do.
        DownloadService.start(this, RipsterDownloadService::class.java)
    }
}
