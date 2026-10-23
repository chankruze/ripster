package `in`.geekofia.ripster.export

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import `in`.geekofia.ripster.core.download.DownloadJob
import `in`.geekofia.ripster.core.download.OutputFile
import `in`.geekofia.ripster.core.export.MediaExporter
import `in`.geekofia.ripster.core.export.OutputFolder
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.download.exportError
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * MP4 export with Media3 Transformer, reading the completed download from the cache (never the
 * network). With no edits requested, Transformer copies compatible samples (H.264/H.265, AAC)
 * into MP4 without re-encoding, and only transcodes what MP4 can't hold, which needs device
 * codecs and may fail. That's reported as [MediaError.ExportUnsupported], never hidden.
 *
 * Transformer isn't resumable: an interrupted export starts again from the downloaded data.
 */
@OptIn(UnstableApi::class)
internal class TransformerExporter(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val offlineDataSourceFactory: CacheDataSource.Factory,
    private val publisher: OutputPublisher,
    private val activity: ExportActivity,
    private val ioDispatcher: CoroutineDispatcher,
) : MediaExporter {

    override suspend fun export(job: DownloadJob, destination: OutputFolder?, onProgress: (Float?) -> Unit): OutputFile {
        val download = withContext(ioDispatcher) { downloadManager.downloadIndex.getDownload(job.id) }
        if (download == null || download.state != Download.STATE_COMPLETED) {
            throw MediaException(MediaError.ExportFailed("The downloaded media is missing. Retry to download it again."))
        }
        val partial = withContext(ioDispatcher) { publisher.newPartialFile(job.id) }
        activity.begin(context, job.title)
        try {
            val result = transform(download, partial) { progress ->
                activity.report(progress)
                onProgress(progress)
            }
            return withContext(ioDispatcher) {
                validate(partial, result)
                publisher.publish(partial, job.title, destination)
            }
        } finally {
            withContext(NonCancellable + ioDispatcher) { partial.delete() }
            activity.end()
        }
    }

    override suspend fun discard(output: OutputFile) = withContext(ioDispatcher) { publisher.discard(output) }

    /** Runs on the main looper, which Transformer uses for its callbacks and requires for calls. */
    private suspend fun transform(download: Download, output: File, onProgress: (Float?) -> Unit): ExportResult =
        withContext(Dispatchers.Main.immediate) {
            val assetLoaderFactory = DefaultAssetLoaderFactory(
                context,
                DefaultDecoderFactory.Builder(context).build(),
                Clock.DEFAULT,
                DefaultMediaSourceFactory(offlineDataSourceFactory),
                DataSourceBitmapLoader(context),
            )
            val transformer = Transformer.Builder(context).setAssetLoaderFactory(assetLoaderFactory).build()
            coroutineScope {
                val progressJob = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    suspendCancellableCoroutine { continuation ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                continuation.resume(exportResult)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                continuation.resumeWithException(MediaException(exportError(exportException), exportException))
                            }
                        })
                        // The request keeps its stream keys, so only the downloaded variant is read.
                        transformer.start(download.request.toMediaItem(), output.absolutePath)
                    }
                } catch (e: Throwable) {
                    // Cancelled (or failed): stop Transformer; the caller deletes the partial file.
                    transformer.cancel()
                    throw e
                } finally {
                    progressJob.cancel()
                }
            }
        }

    /** The file must be non-empty, have a positive duration and at least one audio or video track. */
    private fun validate(file: File, result: ExportResult) {
        if (!file.exists() || file.length() == 0L) {
            throw MediaException(MediaError.ExportFailed("The exported file is empty."))
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (!hasVideo && !hasAudio) throw MediaException(MediaError.ExportFailed("The exported file has no audio or video."))
            if (durationMs <= 0 && result.durationMs <= 0) {
                throw MediaException(MediaError.ExportFailed("The exported file has no duration."))
            }
        } catch (e: MediaException) {
            throw e
        } catch (e: Exception) {
            throw MediaException(MediaError.ExportFailed("The exported file can't be read back."), e)
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
