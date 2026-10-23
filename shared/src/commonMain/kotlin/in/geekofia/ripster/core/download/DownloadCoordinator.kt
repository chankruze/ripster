package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.export.FileNames
import `in`.geekofia.ripster.core.export.MediaExporter
import `in`.geekofia.ripster.core.export.OutputFolder
import `in`.geekofia.ripster.core.source.ManifestKind
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.core.source.MediaSelection
import `in`.geekofia.ripster.core.source.MediaSourceResolver
import `in`.geekofia.ripster.core.source.ResolvedSource
import `in`.geekofia.ripster.core.source.SampleSources
import `in`.geekofia.ripster.core.source.SourceResolution
import `in`.geekofia.ripster.core.source.SourceUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface EnqueueResult {
    data class Added(val job: DownloadJob) : EnqueueResult

    /** The same source and quality is already in the queue and not finished. */
    data class AlreadyQueued(val job: DownloadJob) : EnqueueResult
}

/**
 * Drives jobs through Queued → Resolving → Downloading → Exporting → Completed, on top of a
 * platform [engine] (durable download) and [exporter] (MP4). Portable: all platform work is
 * behind those interfaces.
 *
 * It works by reconciliation: whenever the engine's snapshot or the queue changes, each job is
 * compared with what the engine reports and moved on. The same code path handles a fresh
 * download, a slow collector and recovery after process death. Races are settled by the pure
 * transition rules ([apply]): a terminal job ignores late engine updates.
 *
 * Exports run one at a time; they're CPU and I/O heavy and Media3's muxer is fastest alone.
 */
class DownloadCoordinator(
    private val repository: DownloadRepository,
    private val resolver: MediaSourceResolver,
    private val engine: DownloadEngine,
    private val exporter: MediaExporter,
    private val storage: StorageInfo,
    /** Read when each export starts, so a folder change applies to the next export. */
    private val outputFolder: () -> OutputFolder?,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val newId: () -> String,
) {
    val jobs: StateFlow<List<DownloadJob>> = repository.jobs

    private val lock = Mutex()
    private val exportLock = Mutex()

    /** Resolve-and-enqueue or export coroutines by job id. Guarded by [lock]. */
    private val work = mutableMapOf<String, Job>()

    /** Engine ids being removed, so reconciliation doesn't ask twice. Guarded by [lock]. */
    private val removing = mutableSetOf<String>()

    /** Jobs re-submitted because the engine lost them; a second loss fails the job. Guarded by [lock]. */
    private val resubmitted = mutableSetOf<String>()

    private var started = false

    /** Loads the persisted queue and starts reconciling. Call once per process. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.load()
            engine.snapshot.collect { reconcile() }
        }
    }

    /**
     * Adds a download for [selection] of an inspected [source]. Duplicate submissions (same
     * source and quality, still active) return the existing job instead of adding another.
     */
    suspend fun enqueue(source: ResolvedSource, selection: MediaSelection): EnqueueResult {
        val result = lock.withLock {
            val wanted = selection.toJobSelection()
            repository.jobs.value.firstOrNull {
                it.state.isActive && it.sourceUrl == source.inputUrl &&
                    it.selection.variantUrl == wanted.variantUrl && it.selection.audioUrl == wanted.audioUrl
            }?.let { return@withLock EnqueueResult.AlreadyQueued(it) }

            val now = nowMillis()
            val job = DownloadJob(
                id = newId(),
                title = titleFor(source, selection),
                sourceUrl = source.inputUrl,
                manifestUrl = source.manifestUrl,
                isSample = SampleSources.find(source.inputUrl) != null,
                file = source.file?.container,
                selection = selection.toJobSelection(),
                durationSeconds = source.details.durationSeconds,
                state = DownloadState.Queued,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
            repository.add(job)
            EnqueueResult.Added(job)
        }
        reconcile()
        return result
    }

    /** Cancels a queued or active job and deletes its downloaded data. Exported files are kept. */
    fun cancel(id: String) {
        scope.launch {
            val cancelled = lock.withLock {
                transition(id, JobEvent.Cancel)?.also { work.remove(id)?.cancel() }
            }
            if (cancelled != null) removeEngineData(id)
        }
    }

    /**
     * Retries a failed or cancelled job. A download that already finished goes straight to
     * export; otherwise the URL is inspected again (so an expired link fails fast) and the
     * download resumes from the data already on the device.
     */
    fun retry(id: String) {
        scope.launch {
            lock.withLock {
                val job = repository.get(id) ?: return@withLock
                if (job.error?.isRetryable == false) return@withLock
                transition(id, JobEvent.Retry)
            }
            reconcile()
        }
    }

    /** Removes a finished job from the queue. Its exported file stays where it was saved. */
    fun remove(id: String) {
        scope.launch {
            val removed = lock.withLock {
                val job = repository.get(id)?.takeIf { it.state.isTerminal } ?: return@withLock false
                repository.remove(job.id)
                true
            }
            if (removed) removeEngineData(id)
        }
    }

    /**
     * The snapshot is read *inside* the lock. A snapshot captured before waiting for it can be
     * stale by the time this runs: e.g. still "Failed" after a retry already re-enqueued the job,
     * which would fail the fresh attempt. Under the lock, in-flight work is still registered (its
     * cleanup needs the lock), so jobs and engine state are seen consistently.
     */
    private suspend fun reconcile() {
        if (!repository.loaded.value) return
        val toRemove = mutableListOf<String>()
        lock.withLock {
            val snapshot = engine.snapshot.value
            val jobs = repository.jobs.value
            for (job in jobs) {
                if (job.id in work) continue
                val status = snapshot.downloads[job.id]
                when (job.state) {
                    DownloadState.Queued, DownloadState.Resolving -> {
                        if (status is EngineStatus.Completed) {
                            // Retry after a failed export: the media is already on the device.
                            transition(job.id, JobEvent.StartDownloading)
                            launchExport(job.id)
                        } else {
                            launchWork(job.id) { resolveAndEnqueue(job.id) }
                        }
                    }
                    DownloadState.Downloading -> if (snapshot.loaded) onDownloadStatus(job, status)
                    DownloadState.Exporting -> launchExport(job.id) // Interrupted by process death.
                    else -> Unit
                }
            }
            if (snapshot.loaded) {
                removing.retainAll(snapshot.downloads.keys)
                for (id in snapshot.downloads.keys) {
                    val job = jobs.firstOrNull { it.id == id }
                    val orphan = job == null || job.state == DownloadState.Completed || job.state == DownloadState.Cancelled
                    if (orphan && removing.add(id)) toRemove += id
                }
            }
        }
        toRemove.forEach { removeEngineData(it) }
    }

    /** Called with [lock] held, for a job the engine should be downloading. */
    private fun onDownloadStatus(job: DownloadJob, status: EngineStatus?) {
        when (status) {
            is EngineStatus.Queued ->
                transition(job.id, JobEvent.DownloadProgress(job.progress, job.bytesDownloaded, status.waitingForNetwork))
            is EngineStatus.Downloading ->
                transition(job.id, JobEvent.DownloadProgress(status.progress, status.bytes, status.waitingForNetwork))
            is EngineStatus.Completed -> {
                transition(job.id, JobEvent.DownloadProgress(1f, status.bytes, waitingForNetwork = false))
                launchExport(job.id)
            }
            is EngineStatus.Failed -> transition(job.id, JobEvent.Fail(status.error))
            null -> if (resubmitted.add(job.id)) {
                launchWork(job.id) { engine.enqueue(job) }
            } else {
                transition(job.id, JobEvent.Fail(MediaError.Unknown("The downloaded data was lost. Retry to download again.")))
            }
        }
    }

    private suspend fun resolveAndEnqueue(id: String) {
        if (repository.get(id)?.state == DownloadState.Queued) transition(id, JobEvent.StartResolving)
        val job = repository.get(id)?.takeIf { it.state == DownloadState.Resolving } ?: return

        val source = when (val resolution = resolver.resolve(job.sourceUrl)) {
            is SourceResolution.Rejected -> {
                transition(id, JobEvent.Fail(resolution.error))
                return
            }
            is SourceResolution.Supported -> resolution.source
        }
        // A retry must find the same kind of media: a job doesn't switch between HLS and a file.
        if (job.file != source.file?.container) {
            transition(id, JobEvent.Fail(MediaError.Parse("the link no longer points to the same media")))
            return
        }
        if (source.kind == ManifestKind.DirectFile) {
            transition(id, JobEvent.Resolved(source.manifestUrl, JobSelection(null, null, null, null, emptyList()), null))
            startDownload(id, estimatedBytes = source.file?.sizeBytes)
            return
        }
        val selection = job.selection
        val variant = if (selection.variantUrl == null) {
            // A media playlist (no variants); if it has become a master since, its default variant.
            source.defaultVariantIndex?.let(source.variants::getOrNull)
        } else {
            source.findVariant(selection.variantUrl, selection.bandwidth, selection.resolution, selection.codecs)
                ?: run {
                    transition(id, JobEvent.Fail(MediaError.Parse("the selected quality is no longer offered")))
                    return
                }
        }
        val resolved = source.selectionFor(variant).toJobSelection()
        transition(id, JobEvent.Resolved(source.manifestUrl, resolved, source.details.durationSeconds))

        val bandwidth = resolved.bandwidth
        val duration = source.details.durationSeconds
        startDownload(id, estimatedBytes = if (bandwidth != null && duration != null) (bandwidth / 8.0 * duration).toLong() else null)
    }

    /** Checks free space for [estimatedBytes] (when known), then hands the resolved job to the engine. */
    private suspend fun startDownload(id: String, estimatedBytes: Long?) {
        storageShortfall(estimatedBytes)?.let { error ->
            transition(id, JobEvent.Fail(error))
            return
        }
        val ready = repository.get(id)?.takeIf { it.state == DownloadState.Resolving } ?: return
        engine.enqueue(ready)
        if (transition(id, JobEvent.StartDownloading) == null) {
            // Cancelled while the engine was starting: don't leave a download running.
            removeEngineData(id)
        }
    }

    private suspend fun export(id: String) = exportLock.withLock {
        val job = transition(id, JobEvent.StartExporting) ?: return@withLock
        val output = try {
            exporter.export(job, outputFolder()) { progress -> transition(id, JobEvent.ExportProgress(progress)) }
        } catch (e: CancellationException) {
            // A cancellation that isn't ours (e.g. from inside the platform exporter) is a failure;
            // otherwise the job would stay Exporting and be retried in a loop.
            if (!currentCoroutineContext().isActive) throw e
            transition(id, JobEvent.Fail(MediaError.ExportFailed("The export was interrupted.")))
            return@withLock
        } catch (e: MediaException) {
            transition(id, JobEvent.Fail(e.error))
            // The engine still lists the download as complete, but its data is gone: forget it, so
            // Retry downloads again instead of failing the same way forever.
            if (e.error == MediaError.DownloadedDataMissing) removeEngineData(id)
            return@withLock
        } catch (e: Exception) {
            transition(id, JobEvent.Fail(MediaError.ExportFailed(e.message ?: "Unexpected error")))
            return@withLock
        }
        if (transition(id, JobEvent.Complete(output)) == null) {
            // Cancelled at the last moment. This coroutine may already be cancelled: finish the cleanup.
            withContext(NonCancellable) { exporter.discard(output) }
        } else {
            removeEngineData(id) // The MP4 is self-contained; free the segment cache.
        }
    }

    /** Called with [lock] held. */
    private fun launchExport(id: String) = launchWork(id) { export(id) }

    /** Called with [lock] held. Failures become a Failed job instead of escaping the scope. */
    private fun launchWork(id: String, block: suspend () -> Unit) {
        if (id in work) return
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaException) {
                transition(id, JobEvent.Fail(e.error))
            } catch (e: Exception) {
                transition(id, JobEvent.Fail(MediaError.Unknown(e.message ?: "Unexpected error")))
            }
        }
        work[id] = job
        job.invokeOnCompletion {
            scope.launch(NonCancellable) {
                lock.withLock { if (work[id] === job) work.remove(id) }
                // Whatever this work left behind (e.g. an engine update it raced with) is picked up now.
                reconcile()
            }
        }
    }

    private suspend fun removeEngineData(id: String) {
        try {
            withContext(NonCancellable) { engine.remove(id) }
        } catch (e: Exception) {
            // Best effort: reconciliation retries removal of orphaned data on the next snapshot.
            lock.withLock { removing.remove(id) }
        }
    }

    private fun transition(id: String, event: JobEvent): DownloadJob? =
        repository.update(id) { it.apply(event, nowMillis()) }

    /**
     * Download and export coexist briefly, so twice the estimated size is needed, plus headroom.
     * Skipped when the size or the free space is unknown; a full disk mid-download is reported by
     * the engine instead.
     */
    private fun storageShortfall(estimatedBytes: Long?): MediaError? {
        val estimate = estimatedBytes ?: return null
        val required = estimate * 2 + STORAGE_HEADROOM_BYTES
        val available = storage.availableBytes() ?: return null
        return if (available < required) MediaError.InsufficientStorage(required, available) else null
    }

    private fun MediaSelection.toJobSelection() = JobSelection(
        variantUrl = variant?.url,
        audioUrl = audio?.url,
        bandwidth = variant?.bandwidth,
        resolution = variant?.resolution?.toString(),
        codecs = variant?.codecs.orEmpty(),
    )

    internal companion object {
        const val STORAGE_HEADROOM_BYTES = 50L * 1024 * 1024

        private val genericNames = setOf("master", "index", "playlist", "prog_index", "manifest", "main", "video", "")

        /** A sample's title, else a meaningful path segment, else the host; plus the quality. */
        fun titleFor(source: ResolvedSource, selection: MediaSelection): String {
            val base = SampleSources.find(source.inputUrl)?.title
                ?: source.foundOnPage?.title
                ?: SourceUrl.pathSegments(source.inputUrl).map { it.substringBeforeLast('.') }
                    .lastOrNull { it.lowercase() !in genericNames }
                ?: SourceUrl.displayForm(source.inputUrl).substringBefore('/')
            val quality = selection.variant?.resolution?.let { " ${it.height}p" }.orEmpty()
            return FileNames.sanitize(base + quality)
        }
    }
}
