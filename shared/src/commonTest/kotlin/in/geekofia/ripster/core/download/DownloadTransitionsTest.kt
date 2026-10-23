package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.download.DownloadState.Cancelled
import `in`.geekofia.ripster.core.download.DownloadState.Completed
import `in`.geekofia.ripster.core.download.DownloadState.Downloading
import `in`.geekofia.ripster.core.download.DownloadState.Exporting
import `in`.geekofia.ripster.core.download.DownloadState.Failed
import `in`.geekofia.ripster.core.download.DownloadState.Queued
import `in`.geekofia.ripster.core.download.DownloadState.Resolving
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadTransitionsTest {
    private fun DownloadJob.on(event: JobEvent) = apply(event, nowMillis = 5_000)

    @Test
    fun theHappyPath() {
        val queued = testJob()
        val resolving = queued.on(JobEvent.StartResolving)!!
        assertEquals(Resolving, resolving.state)
        val downloading = resolving.on(JobEvent.StartDownloading)!!
        assertEquals(Downloading, downloading.state)
        val progressed = downloading.on(JobEvent.DownloadProgress(0.5f, 1_000, waitingForNetwork = false))!!
        assertEquals(0.5f, progressed.progress)
        assertEquals(1_000, progressed.bytesDownloaded)
        val exporting = progressed.on(JobEvent.StartExporting)!!
        assertEquals(Exporting, exporting.state)
        assertNull(exporting.progress, "export progress starts unknown, not at the download's value")
        val completed = exporting.on(JobEvent.ExportProgress(0.9f))!!.on(JobEvent.Complete(testOutput))!!
        assertEquals(Completed, completed.state)
        assertEquals(testOutput, completed.output)
        assertEquals(5_000, completed.updatedAtMillis)
    }

    @Test
    fun terminalJobsIgnoreLateEngineUpdates() {
        for (state in listOf(Completed, Failed, Cancelled)) {
            val job = testJob(state = state)
            assertNull(job.on(JobEvent.DownloadProgress(0.7f, 10, false)), "$state")
            assertNull(job.on(JobEvent.StartExporting), "$state")
            assertNull(job.on(JobEvent.ExportProgress(0.5f)), "$state")
            assertNull(job.on(JobEvent.Complete(testOutput)), "$state")
            assertNull(job.on(JobEvent.Fail(networkError)), "$state")
            assertNull(job.on(JobEvent.Cancel), "$state")
        }
    }

    @Test
    fun aCancelledExportCanNotComplete() {
        val cancelled = testJob(state = Exporting).on(JobEvent.Cancel)!!
        assertEquals(Cancelled, cancelled.state)
        assertNull(cancelled.on(JobEvent.Complete(testOutput)))
    }

    @Test
    fun everyActiveStateCanBeCancelledOrFail() {
        for (state in listOf(Queued, Resolving, Downloading, Exporting)) {
            assertEquals(Cancelled, testJob(state = state).on(JobEvent.Cancel)?.state, "$state")
            val failed = testJob(state = state).on(JobEvent.Fail(networkError))!!
            assertEquals(Failed, failed.state, "$state")
            assertEquals(networkError, failed.error)
        }
    }

    @Test
    fun retryOnlyFromFailedOrCancelled() {
        val failed = testJob(state = Downloading)
            .on(JobEvent.DownloadProgress(0.4f, 400, false))!!
            .on(JobEvent.Fail(networkError))!!
        val retried = failed.on(JobEvent.Retry)!!
        assertEquals(Queued, retried.state)
        assertNull(retried.error)
        assertNull(retried.progress)
        assertEquals(2, retried.attempt)
        assertEquals(Queued, testJob(state = Cancelled).on(JobEvent.Retry)?.state)
        for (state in listOf(Queued, Resolving, Downloading, Exporting, Completed)) {
            assertNull(testJob(state = state).on(JobEvent.Retry), "$state")
        }
    }

    @Test
    fun progressIsClampedAndNeverGoesBackwards() {
        val job = testJob(state = Downloading).on(JobEvent.DownloadProgress(0.6f, 600, false))!!
        assertEquals(0.6f, job.on(JobEvent.DownloadProgress(0.2f, 100, false))!!.progress)
        assertEquals(600, job.on(JobEvent.DownloadProgress(0.2f, 100, false))!!.bytesDownloaded)
        assertEquals(1f, job.on(JobEvent.DownloadProgress(7f, 700, false))!!.progress)
        assertEquals(0.6f, job.on(JobEvent.DownloadProgress(null, 700, false))!!.progress, "unknown keeps the last real value")
    }

    @Test
    fun waitingForNetworkFollowsTheEngineAndClearsWhenDone() {
        val waiting = testJob(state = Downloading).on(JobEvent.DownloadProgress(null, 0, waitingForNetwork = true))!!
        assertEquals(true, waiting.waitingForNetwork)
        assertEquals(false, waiting.on(JobEvent.Cancel)!!.waitingForNetwork)
        assertEquals(false, waiting.on(JobEvent.StartExporting)!!.waitingForNetwork)
    }

    @Test
    fun outOfOrderEventsDoNotApply() {
        assertNull(testJob(state = Queued).on(JobEvent.StartExporting))
        assertNull(testJob(state = Queued).on(JobEvent.Complete(testOutput)))
        assertNull(testJob(state = Downloading).on(JobEvent.StartResolving))
        assertNull(testJob(state = Downloading).on(JobEvent.Complete(testOutput)))
        assertNull(testJob(state = Downloading).on(JobEvent.Resolved("u", testJob().selection, 1.0)))
        assertNull(testJob(state = Exporting).on(JobEvent.DownloadProgress(0.1f, 1, false)))
    }
}
