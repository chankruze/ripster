package `in`.geekofia.ripster.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
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
import `in`.geekofia.ripster.core.source.ContainerSniffer
import `in`.geekofia.ripster.core.source.FileContainer
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.download.exportError
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Turns a completed download into a standalone file, reading only from the cache (never the
 * network). HLS is exported to MP4 with Media3 Transformer; a direct file (MP4, MKV, WebM) is
 * copied out as is.
 *
 * For HLS, with no edits requested, Transformer copies compatible samples (H.264/H.265, AAC)
 * into MP4 without re-encoding, and only transcodes what MP4 can't hold, which needs device
 * codecs and may fail. That's reported as [MediaError.ExportUnsupported], never hidden.
 *
 * Transformer isn't resumable: an interrupted export starts again from the downloaded data.
 */
@OptIn(UnstableApi::class)
internal class TransformerExporter(
    private val context: Context,
    private val downloadManager: DownloadManager,
    /** A job's offline (cache-only) data source; see `AndroidDownloads.jobCacheKeys`. */
    private val offlineDataSource: (jobId: String) -> CacheDataSource.Factory,
    private val publisher: OutputPublisher,
    private val activity: ExportActivity,
    private val ioDispatcher: CoroutineDispatcher,
) : MediaExporter {

    override suspend fun export(job: DownloadJob, destination: OutputFolder?, onProgress: (Float?) -> Unit): OutputFile {
        val download = withContext(ioDispatcher) { downloadManager.downloadIndex.getDownload(job.id) }
        if (download == null || download.state != Download.STATE_COMPLETED) {
            throw MediaException(MediaError.DownloadedDataMissing)
        }
        val container = job.file ?: FileContainer.Mp4
        val partial = withContext(ioDispatcher) { publisher.newPartialFile(job.id, container) }
        activity.begin(context, job.title)
        try {
            val report: (Float?) -> Unit = { progress ->
                activity.report(progress)
                onProgress(progress)
            }
            if (job.file != null) {
                val copied = copyFromCache(download, partial, report)
                withContext(ioDispatcher) { validateCopy(partial, container, copied, download.bytesDownloaded) }
            } else {
                val result = transform(download, partial, report)
                withContext(ioDispatcher) { validate(partial, result) }
            }
            return withContext(ioDispatcher) { publisher.publish(partial, job.title, destination, container) }
        } finally {
            withContext(NonCancellable + ioDispatcher) { partial.delete() }
            activity.end()
        }
    }

    override suspend fun discard(output: OutputFile) = withContext(ioDispatcher) { publisher.discard(output) }

    /**
     * A direct file is saved exactly as downloaded: its bytes are copied out of the offline cache
     * (no network), with progress, and the copy stops promptly when cancelled. Returns the bytes copied.
     */
    private suspend fun copyFromCache(download: Download, output: File, onProgress: (Float?) -> Unit): Long =
        withContext(ioDispatcher) {
            val dataSource = offlineDataSource(download.request.id).createDataSource()
            val spec = DataSpec.Builder().setUri(download.request.uri).setKey(download.request.customCacheKey).build()
            try {
                val length = dataSource.open(spec).takeIf { it != C.LENGTH_UNSET.toLong() } ?: download.contentLength.takeIf { it > 0 }
                output.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copied = 0L
                    var lastReported = 0L
                    while (true) {
                        ensureActive()
                        val read = dataSource.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (length != null && copied - lastReported >= PROGRESS_STEP_BYTES) {
                            onProgress(copied.toFloat() / length)
                            lastReported = copied
                        }
                    }
                    copied
                }
            } catch (e: IOException) {
                throw MediaException(MediaError.DownloadedDataMissing, e)
            } finally {
                dataSource.close()
            }
        }

    /** Runs on the main looper, which Transformer uses for its callbacks and requires for calls. */
    private suspend fun transform(download: Download, output: File, onProgress: (Float?) -> Unit): ExportResult =
        withContext(Dispatchers.Main.immediate) {
            val assetLoaderFactory = DefaultAssetLoaderFactory(
                context,
                DefaultDecoderFactory.Builder(context).build(),
                Clock.DEFAULT,
                DefaultMediaSourceFactory(offlineDataSource(download.request.id)),
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

    /**
     * Checks a copied direct file against what was downloaded, not against metadata the format may
     * not carry: fragmented MP4 (common for web video) often has no duration the platform reports.
     * The copy must be complete, still be the detected container, and have an audio or video track.
     */
    private fun validateCopy(file: File, container: FileContainer, copied: Long, downloaded: Long) {
        if (copied == 0L || (downloaded > 0 && copied != downloaded) || file.length() != copied) {
            throw MediaException(MediaError.ExportFailed("The saved copy is incomplete. Retry to download it again."))
        }
        val header = file.inputStream().use { it.readNBytes(ContainerSniffer.SNIFF_BYTES) }
        if (ContainerSniffer.sniff(header) != container) {
            throw MediaException(MediaError.ExportFailed("The downloaded file isn't a valid ${container.label} file."))
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val hasMedia = (0 until extractor.trackCount).any { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") || mime.startsWith("audio/")
            }
            if (!hasMedia) throw MediaException(MediaError.ExportFailed("The downloaded file has no audio or video."))
        } catch (e: MediaException) {
            throw e
        } catch (e: Exception) {
            // The bytes are intact (checked above); the platform just can't parse this file.
            throw MediaException(MediaError.ExportUnsupported("This device can't read this ${container.label} file."), e)
        } finally {
            extractor.release()
        }
    }

    /** A Transformer output must be non-empty, have a positive duration and an audio or video track. */
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
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
    }
}
