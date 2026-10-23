package `in`.geekofia.ripster.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import `in`.geekofia.ripster.core.download.DownloadEngine
import `in`.geekofia.ripster.core.download.DownloadJob
import `in`.geekofia.ripster.core.download.EngineSnapshot
import `in`.geekofia.ripster.core.download.EngineStatus
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.SourceUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [DownloadEngine] on Media3's [DownloadManager]: segment downloads with retries, a persistent
 * index (resume after process death) and network requirements. Work runs in
 * [RipsterDownloadService], a foreground service, so it continues in the background.
 *
 * [DownloadManager] must be used on the thread that created it (main), so every call hops there.
 * It doesn't push progress, so while something is downloading the snapshot is refreshed every
 * [PROGRESS_INTERVAL_MS] from its real counters.
 */
@OptIn(UnstableApi::class)
internal class Media3DownloadEngine(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val requests: DownloadRequestFactory,
    private val ioDispatcher: CoroutineDispatcher,
) : DownloadEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow(EngineSnapshot.NotLoaded)
    private var polling: Job? = null

    /** Final exceptions are only reported once, by the listener; kept so later snapshots keep the cause. */
    private val failures = mutableMapOf<String, MediaError>()

    override val snapshot: StateFlow<EngineSnapshot> = state.asStateFlow()

    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
            if (download.state == Download.STATE_FAILED) {
                failures[download.request.id] = downloadError(finalException, SourceUrl.looksSigned(download.request.uri.toString()))
            }
            put(download)
            updatePolling()
        }

        override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
            failures.remove(download.request.id)
            state.update { it.next { downloads -> downloads - download.request.id } }
        }

        override fun onRequirementsStateChanged(manager: DownloadManager, requirements: Requirements, notMetRequirements: Int) {
            downloadManager.currentDownloads.forEach(::put)
        }

        override fun onInitialized(manager: DownloadManager) {
            downloadManager.currentDownloads.forEach(::put)
            updatePolling()
        }
    }

    /** Call on the main thread, right after creating the [DownloadManager]. */
    fun start() {
        downloadManager.addListener(listener)
        scope.launch {
            // The index includes finished and failed downloads, which currentDownloads doesn't.
            val stored = withContext(ioDispatcher) {
                buildList {
                    downloadManager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) add(cursor.download)
                    }
                }
            }
            state.update { current ->
                // Updates that arrived while reading are newer than the index.
                current.next(loaded = true) { downloads -> stored.associate { it.request.id to it.toStatus() } + downloads }
            }
            updatePolling()
        }
    }

    override suspend fun enqueue(job: DownloadJob) {
        val request = requests.build(job)
        withContext(Dispatchers.Main.immediate) {
            failures.remove(job.id)
            // Visible right away, as the contract requires; the listener refines it.
            state.update { it.next { downloads -> downloads + (job.id to EngineStatus.Queued(waitingForNetwork = false)) } }
            try {
                DownloadService.sendAddDownload(context, RipsterDownloadService::class.java, request, /* foreground= */ true)
            } catch (e: IllegalStateException) {
                // Android 12+ forbids starting a foreground service from the background (e.g. a
                // retry during recovery). Run in-process; the service takes over when it can start.
                downloadManager.addDownload(request)
                downloadManager.resumeDownloads()
            }
        }
    }

    override suspend fun remove(jobId: String) {
        withContext(Dispatchers.Main.immediate) {
            failures.remove(jobId)
            state.update { it.next { downloads -> downloads - jobId } }
            try {
                DownloadService.sendRemoveDownload(context, RipsterDownloadService::class.java, jobId, /* foreground= */ false)
            } catch (e: IllegalStateException) {
                downloadManager.removeDownload(jobId)
            }
        }
    }

    private fun put(download: Download) {
        if (download.state == Download.STATE_REMOVING) return
        val status = download.toStatus()
        // Progress polling repeats unchanged values; only real changes make a new version.
        state.update { if (it.downloads[download.request.id] == status) it else it.next { d -> d + (download.request.id to status) } }
    }

    private fun updatePolling() {
        val downloading = downloadManager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }
        if (downloading && polling?.isActive != true) {
            polling = scope.launch {
                while (isActive) {
                    val current = downloadManager.currentDownloads
                    current.forEach(::put)
                    if (current.none { it.state == Download.STATE_DOWNLOADING }) break
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
        }
    }

    private fun Download.toStatus(): EngineStatus {
        val waiting = downloadManager.notMetRequirements != 0
        return when (state) {
            Download.STATE_DOWNLOADING -> EngineStatus.Downloading(
                progress = percentDownloaded.takeIf { it != C.PERCENTAGE_UNSET.toFloat() && it >= 0 }?.div(100f),
                bytes = bytesDownloaded,
            )
            Download.STATE_COMPLETED -> EngineStatus.Completed(bytesDownloaded)
            Download.STATE_FAILED -> EngineStatus.Failed(
                failures[request.id] ?: MediaError.Unknown("The download failed. Retry to resume it."),
            )
            // Queued, restarting, or stopped: not transferring right now.
            else -> EngineStatus.Queued(waitingForNetwork = waiting)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
