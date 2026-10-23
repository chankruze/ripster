package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.download.DownloadState.Cancelled
import `in`.geekofia.ripster.core.download.DownloadState.Completed
import `in`.geekofia.ripster.core.download.DownloadState.Downloading
import `in`.geekofia.ripster.core.download.DownloadState.Exporting
import `in`.geekofia.ripster.core.download.DownloadState.Failed
import `in`.geekofia.ripster.core.export.OutputFolder
import `in`.geekofia.ripster.core.source.ErrorCategory
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.core.source.SourceResolution
import `in`.geekofia.ripster.platform.InMemoryTextStore
import `in`.geekofia.ripster.platform.TextStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class DownloadCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeEngine()
    private val exporter = FakeExporter()
    private val resolver = FakeResolver(SourceResolution.Supported(testSource))
    private var freeBytes: Long? = 100L * 1024 * 1024 * 1024
    private var folder: OutputFolder? = null
    private val ids = AtomicInteger()

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun coordinator(store: TextStore = InMemoryTextStore(), engine: DownloadEngine = this.engine) = DownloadCoordinator(
        repository = DownloadRepository(store, scope, Dispatchers.IO),
        resolver = resolver,
        engine = engine,
        exporter = exporter,
        storage = { freeBytes },
        outputFolder = { folder },
        scope = scope,
        nowMillis = { 1_000L + ids.get() },
        newId = { "job-${ids.incrementAndGet()}" },
    ).also { it.start() }

    private suspend fun DownloadCoordinator.add(): DownloadJob {
        eventually(value = { jobs.value }) { true } // start() is asynchronous; enqueue works regardless.
        return assertIs<EnqueueResult.Added>(enqueue(testSource, testSource.defaultSelection)).job
    }

    private suspend fun DownloadCoordinator.await(id: String, predicate: (DownloadJob) -> Boolean): DownloadJob =
        eventually(value = { jobs.value.firstOrNull { it.id == id } }) { it != null && predicate(it) }!!

    private suspend fun DownloadCoordinator.awaitState(id: String, state: DownloadState) = await(id) { it.state == state }

    @Test
    fun aJobGoesFromQueueToExportedMp4() = runBlocking<Unit> {
        val downloads = coordinator()
        val job = downloads.add()
        assertEquals("show 540p", job.title, "the meaningful path segment, plus the quality")
        assertEquals(DownloadState.Queued, job.state)

        downloads.awaitState(job.id, Downloading)
        assertEquals(listOf(job.id), engine.enqueued)
        assertEquals(listOf(testSource.inputUrl), resolver.resolved)

        engine.set(job.id, EngineStatus.Downloading(0.25f, 1_000))
        assertEquals(1_000, downloads.await(job.id) { it.progress == 0.25f }.bytesDownloaded)

        engine.set(job.id, EngineStatus.Completed(4_000))
        val done = downloads.awaitState(job.id, Completed)
        assertEquals(testOutput, done.output)
        assertEquals(listOf(job.id), exporter.exported)
        // The MP4 is self-contained, so the segment cache is released.
        eventually<List<String>>(value = { engine.removed.toList() }) { job.id in it }
    }

    @Test
    fun theChosenFolderIsUsedForTheExport() = runBlocking<Unit> {
        folder = OutputFolder("content://tree/movies", "Movies")
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Completed(1))
        downloads.awaitState(job.id, Completed)
        assertEquals(listOf<OutputFolder?>(folder), exporter.destinations)
    }

    @Test
    fun duplicateSubmissionsReturnTheExistingJob() = runBlocking<Unit> {
        val downloads = coordinator()
        val first = downloads.add()
        val second = downloads.enqueue(testSource, testSource.defaultSelection)
        assertEquals(first.id, assertIs<EnqueueResult.AlreadyQueued>(second).job.id)
        assertEquals(1, downloads.jobs.value.size)
    }

    @Test
    fun anExpiredLinkFailsWhileResolvingAndIsNotDownloaded() = runBlocking<Unit> {
        resolver.result = SourceResolution.Rejected(MediaError.HttpStatus(403, signedUrl = true))
        val downloads = coordinator()
        val job = downloads.add()
        val failed = downloads.awaitState(job.id, Failed)
        assertEquals(ErrorCategory.Expired, failed.error?.category)
        assertEquals(emptyList(), engine.enqueued)
    }

    @Test
    fun notEnoughStorageFailsBeforeDownloading() = runBlocking<Unit> {
        freeBytes = 10L * 1024 * 1024
        val downloads = coordinator()
        val job = downloads.add()
        val error = assertIs<MediaError.InsufficientStorage>(downloads.awaitState(job.id, Failed).error)
        assertEquals(10L * 1024 * 1024, error.availableBytes)
        assertTrue(error.requiredBytes!! > error.availableBytes!!)
        assertEquals(emptyList(), engine.enqueued)
    }

    @Test
    fun unknownFreeSpaceDoesNotBlockTheDownload() = runBlocking<Unit> {
        freeBytes = null
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
    }

    @Test
    fun cancellingADownloadDeletesItsDataAndALateCompletionIsIgnored() = runBlocking<Unit> {
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Downloading(0.5f, 10))

        downloads.cancel(job.id)
        downloads.awaitState(job.id, Cancelled)
        eventually<List<String>>(value = { engine.removed.toList() }) { job.id in it }

        // The engine's last word arrives after the cancellation: it must not resurrect the job.
        engine.set(job.id, EngineStatus.Completed(100))
        delay(100)
        assertEquals(Cancelled, downloads.jobs.value.single().state)
        assertEquals(emptyList(), exporter.exported)
    }

    @Test
    fun cancellingWhileResolvingNeverStartsTheEngine() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        resolver.gate = gate
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, DownloadState.Resolving)
        downloads.cancel(job.id)
        downloads.awaitState(job.id, Cancelled)
        gate.complete(Unit)
        delay(100)
        assertEquals(emptyList(), engine.enqueued)
    }

    @Test
    fun cancellingAnExportStopsItAndKeepsNoOutput() = runBlocking<Unit> {
        val exportStarted = CompletableDeferred<Unit>()
        val exportCancelled = CompletableDeferred<Unit>()
        exporter.behaviour = { _, _ ->
            exportStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                exportCancelled.complete(Unit)
            }
        }
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Completed(1))
        exportStarted.await()
        downloads.awaitState(job.id, Exporting)

        downloads.cancel(job.id)
        exportCancelled.await()
        assertEquals(Cancelled, downloads.awaitState(job.id, Cancelled).state)
        assertEquals(null, downloads.jobs.value.single().output)
    }

    @Test
    fun anOutputThatArrivesAfterCancellationIsDiscarded() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val downloads = coordinator()
        exporter.behaviour = { job, _ ->
            // An exporter that finishes its (non-cancellable) work even though the job was cancelled.
            withContext(NonCancellable) {
                release.await()
                downloads.cancel(job.id)
                downloads.awaitState(job.id, Cancelled)
                testOutput
            }
        }
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Completed(1))
        downloads.awaitState(job.id, Exporting)
        release.complete(Unit)
        eventually<List<OutputFile>>(value = { exporter.discarded.toList() }) { it == listOf(testOutput) }
        assertEquals(Cancelled, downloads.jobs.value.single().state)
    }

    @Test
    fun anEngineFailureFailsTheJobAndRetryResumesTheDownload() = runBlocking<Unit> {
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Failed(networkError))
        assertEquals(networkError, downloads.awaitState(job.id, Failed).error)

        downloads.retry(job.id)
        val retried = downloads.await(job.id) { it.state == Downloading && it.attempt == 2 }
        assertEquals(null, retried.error)
        // Inspected again (an expired link would fail fast) and handed back to the engine to resume.
        assertEquals(2, resolver.resolved.size)
        assertEquals(listOf(job.id, job.id), engine.enqueued)
    }

    @Test
    fun retryAfterAFailedExportReusesTheDownload() = runBlocking<Unit> {
        var attempts = 0
        exporter.behaviour = { _, _ ->
            if (attempts++ == 0) throw MediaException(MediaError.ExportFailed("disk hiccup")) else testOutput
        }
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Completed(1))
        assertEquals(MediaError.ExportFailed("disk hiccup"), downloads.awaitState(job.id, Failed).error)

        downloads.retry(job.id)
        downloads.awaitState(job.id, Completed)
        assertEquals(1, resolver.resolved.size, "no re-inspection: the media is already on the device")
        assertEquals(listOf(job.id), engine.enqueued)
    }

    @Test
    fun unsupportedExportsAreNotRetried() = runBlocking<Unit> {
        exporter.behaviour = { _, _ -> throw MediaException(MediaError.ExportUnsupported("AC-3")) }
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Completed(1))
        downloads.awaitState(job.id, Failed)
        downloads.retry(job.id)
        delay(100)
        assertEquals(Failed, downloads.jobs.value.single().state)
        assertEquals(1, exporter.exported.size)
    }

    @Test
    fun removingAFinishedJobKeepsItsFileAndFreesEngineData() = runBlocking<Unit> {
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Failed(unavailable))
        downloads.awaitState(job.id, Failed)

        downloads.remove(job.id)
        eventually(value = { downloads.jobs.value }) { it.isEmpty() }
        eventually<List<String>>(value = { engine.removed.toList() }) { job.id in it }
        assertEquals(emptyList(), exporter.discarded)
    }

    @Test
    fun activeJobsCanNotBeRemoved() = runBlocking<Unit> {
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        downloads.remove(job.id)
        delay(100)
        assertEquals(1, downloads.jobs.value.size)
    }

    @Test
    fun recoveryAfterProcessDeathContinuesEveryJob() = runBlocking<Unit> {
        val store = InMemoryTextStore(
            DownloadJobCodec.encode(
                listOf(
                    testJob(id = "was-downloading", state = Downloading, createdAt = 3),
                    testJob(id = "was-exporting", state = Exporting, createdAt = 2),
                    testJob(id = "was-queued", state = DownloadState.Queued, createdAt = 1, sourceUrl = "https://cdn.example/other.m3u8"),
                    testJob(id = "done", state = Completed, createdAt = 0).copy(output = testOutput),
                ),
            ),
        )
        // Media3's own index survived too: one download finished while the process was gone.
        engine.set("was-downloading", EngineStatus.Completed(10))
        engine.set("was-exporting", EngineStatus.Completed(10))
        engine.set("done", EngineStatus.Completed(10)) // Its export finished, but the cache wasn't freed.

        val downloads = coordinator(store)
        downloads.awaitState("was-downloading", Completed)
        downloads.awaitState("was-exporting", Completed)
        downloads.awaitState("was-queued", Downloading)
        assertEquals(Completed, downloads.jobs.value.first { it.id == "done" }.state)
        assertTrue(exporter.exported.containsAll(listOf("was-downloading", "was-exporting")))
        assertEquals(listOf("was-queued"), engine.enqueued)
        eventually<List<String>>(value = { engine.removed.toList() }) { "done" in it }
    }

    @Test
    fun recoveryWaitsForTheEngineIndexBeforeJudgingDownloads() = runBlocking<Unit> {
        val store = InMemoryTextStore(DownloadJobCodec.encode(listOf(testJob(id = "a", state = Downloading))))
        val slowEngine = FakeEngine(loaded = false)
        val downloads = coordinator(store, slowEngine)
        eventually(value = { downloads.jobs.value }) { it.isNotEmpty() }
        delay(100)
        assertEquals(emptyList(), slowEngine.enqueued, "not re-submitted while the index is still loading")

        slowEngine.state.value = slowEngine.state.value.next(loaded = true) { mapOf("a" to EngineStatus.Downloading(0.4f, 40)) }
        assertEquals(0.4f, downloads.await("a") { it.progress != null }.progress)
        assertEquals(emptyList(), slowEngine.enqueued)
    }

    @Test
    fun aDownloadTheEngineLostIsResubmittedOnceThenFails() = runBlocking<Unit> {
        val store = InMemoryTextStore(DownloadJobCodec.encode(listOf(testJob(id = "lost", state = Downloading))))
        val downloads = coordinator(store)
        eventually(value = { engine.enqueued.toList() }) { it == listOf("lost") }

        engine.remove("lost") // Lost again.
        val failed = downloads.awaitState("lost", Failed)
        assertIs<MediaError.Unknown>(failed.error)
    }

    @Test
    fun waitingForNetworkIsReported() = runBlocking<Unit> {
        val downloads = coordinator()
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Queued(waitingForNetwork = true))
        downloads.await(job.id) { it.waitingForNetwork }
        engine.set(job.id, EngineStatus.Downloading(0.1f, 1))
        downloads.await(job.id) { !it.waitingForNetwork && it.progress == 0.1f }
    }

    @Test
    fun theQueueIsPersistedWithoutTransientProgress() = runBlocking<Unit> {
        val store = InMemoryTextStore()
        val downloads = coordinator(store)
        val job = downloads.add()
        downloads.awaitState(job.id, Downloading)
        engine.set(job.id, EngineStatus.Downloading(0.5f, 500))
        downloads.await(job.id) { it.progress == 0.5f }
        val stored = eventually(value = { DownloadJobCodec.decode(store.read()) }) { it.singleOrNull()?.state == Downloading }
        assertEquals(null, stored.single().progress)
        assertEquals(0, stored.single().bytesDownloaded)
    }
}
