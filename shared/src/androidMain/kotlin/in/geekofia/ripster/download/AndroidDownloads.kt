package `in`.geekofia.ripster.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import `in`.geekofia.ripster.export.ExportActivity
import `in`.geekofia.ripster.export.OutputPublisher
import `in`.geekofia.ripster.export.TransformerExporter
import `in`.geekofia.ripster.platform.AndroidHttpClient
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers

/**
 * Process-wide Media3 objects. The cache and download index must exist once per process (a
 * [SimpleCache] folder can only be opened once), and the system-started services need them, so
 * the Application owns this and exposes it via [RipsterAndroidHost]. Create on the main thread.
 */
@OptIn(UnstableApi::class)
class AndroidDownloads(context: Context) {
    private val appContext = context.applicationContext
    internal val ioDispatcher = Dispatchers.IO
    internal val httpClient = AndroidHttpClient(ioDispatcher)

    private val databaseProvider = StandaloneDatabaseProvider(appContext)

    /**
     * Downloaded segments. In no-backup storage (not the cache dir, which the system may clear
     * mid-download) and never evicted: Media3 removes a download's data when it's removed.
     */
    private val cache = SimpleCache(File(appContext.noBackupFilesDir, "media-downloads"), NoOpCacheEvictor(), databaseProvider)

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(AndroidHttpClient.USER_AGENT)
        .setConnectTimeoutMs(AndroidHttpClient.TIMEOUT_MS)
        .setReadTimeoutMs(AndroidHttpClient.TIMEOUT_MS)
        // An https → http redirect fails instead of silently downgrading.
        .setAllowCrossProtocolRedirects(false)

    internal val downloadManager = DownloadManager(
        appContext,
        databaseProvider,
        cache,
        httpDataSourceFactory,
        Executors.newFixedThreadPool(DOWNLOAD_THREADS),
    ).apply {
        maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
        // Bounded: transient errors are retried, but a broken or expired source fails instead of looping.
        minRetryCount = MIN_RETRY_COUNT
        requirements = Requirements(Requirements.NETWORK)
    }

    internal val engine = Media3DownloadEngine(appContext, downloadManager, DownloadRequestFactory(httpClient), ioDispatcher)
        .also { it.start() }

    /** Reads only what was downloaded: no upstream, so a missing segment fails instead of re-fetching. */
    private val offlineDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)

    internal val exportActivity = ExportActivity()

    internal val exporter = TransformerExporter(
        appContext,
        downloadManager,
        offlineDataSourceFactory,
        OutputPublisher(appContext),
        exportActivity,
        ioDispatcher,
    )

    private companion object {
        const val DOWNLOAD_THREADS = 4
        const val MAX_PARALLEL_DOWNLOADS = 2
        const val MIN_RETRY_COUNT = 3
    }
}

/** Implemented by the Application, so services started by the system can reach [AndroidDownloads]. */
interface RipsterAndroidHost {
    val androidDownloads: AndroidDownloads
}
