package `in`.geekofia.ripster.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloaderFactory
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

    private val downloadExecutor = Executors.newFixedThreadPool(DOWNLOAD_THREADS)

    /**
     * What Media3's convenience constructor builds, except that each job's cache entries are
     * keyed by its id (see [jobCacheKeys]): two jobs for the same URL never share data, so
     * removing one after its export can't delete the other's.
     */
    internal val downloadManager = DownloadManager(
        appContext,
        DefaultDownloadIndex(databaseProvider),
        DownloaderFactory { request ->
            DefaultDownloaderFactory(jobCacheSource(request.id).setUpstreamDataSourceFactory(httpDataSourceFactory), downloadExecutor)
                .createDownloader(request)
        },
    ).apply {
        maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
        // Bounded: transient errors are retried, but a broken or expired source fails instead of looping.
        minRetryCount = MIN_RETRY_COUNT
        requirements = Requirements(Requirements.NETWORK)
    }

    internal val engine = Media3DownloadEngine(appContext, downloadManager, DownloadRequestFactory(httpClient), ioDispatcher)
        .also { it.start() }

    private fun jobCacheSource(jobId: String) = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(jobCacheKeys(jobId))

    /** Reads only what [jobId] downloaded: no upstream, so a missing segment fails instead of re-fetching. */
    private fun offlineDataSource(jobId: String) = jobCacheSource(jobId)
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)

    internal val exportActivity = ExportActivity()

    internal val exporter = TransformerExporter(
        appContext,
        downloadManager,
        ::offlineDataSource,
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

/** Media3's usual key (the custom key, else the URL), namespaced by the job that downloaded it. */
internal fun jobCacheKeys(jobId: String) = CacheKeyFactory { spec -> "$jobId|${CacheKeyFactory.DEFAULT.buildCacheKey(spec)}" }

/** Implemented by the Application, so services started by the system can reach [AndroidDownloads]. */
interface RipsterAndroidHost {
    val androidDownloads: AndroidDownloads
}
