package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.download.DownloadState.Cancelled
import `in`.geekofia.ripster.core.download.DownloadState.Completed
import `in`.geekofia.ripster.core.download.DownloadState.Downloading
import `in`.geekofia.ripster.core.download.DownloadState.Exporting
import `in`.geekofia.ripster.core.download.DownloadState.Failed
import `in`.geekofia.ripster.core.download.DownloadState.Queued
import `in`.geekofia.ripster.core.download.DownloadState.Resolving
import `in`.geekofia.ripster.core.source.MediaError

/** Something that happened to a job. Applied with [DownloadJob.apply]. */
sealed interface JobEvent {
    data object StartResolving : JobEvent
    data class Resolved(val manifestUrl: String, val selection: JobSelection, val durationSeconds: Double?) : JobEvent
    data object StartDownloading : JobEvent
    data class DownloadProgress(val progress: Float?, val bytes: Long, val waitingForNetwork: Boolean) : JobEvent
    data object StartExporting : JobEvent
    data class ExportProgress(val progress: Float?) : JobEvent
    data class Complete(val output: OutputFile) : JobEvent
    data class Fail(val error: MediaError) : JobEvent
    data object Cancel : JobEvent
    data object Retry : JobEvent
}

/**
 * The queue's state machine, as a pure function so every rule is testable:
 *
 * ```
 * Queued → Resolving → Downloading → Exporting → Completed
 *    ╰──────────┴───────────┴────────────┴──→ Failed / Cancelled
 * Failed / Cancelled ──Retry──→ Queued
 * ```
 *
 * Returns null when [event] doesn't apply in the current state. That is how races resolve: once
 * a job is terminal, a late progress update or completion from the engine is simply ignored,
 * so a cancelled job can't come back as Completed.
 */
fun DownloadJob.apply(event: JobEvent, nowMillis: Long): DownloadJob? {
    val next = when (event) {
        JobEvent.StartResolving ->
            if (state == Queued) copy(state = Resolving, progress = null, error = null) else null
        is JobEvent.Resolved ->
            if (state == Resolving) {
                copy(manifestUrl = event.manifestUrl, selection = event.selection, durationSeconds = event.durationSeconds)
            } else {
                null
            }
        JobEvent.StartDownloading ->
            if (state == Resolving || state == Queued) copy(state = Downloading, progress = null) else null
        is JobEvent.DownloadProgress ->
            if (state == Downloading) {
                copy(
                    // Progress never goes backwards within one attempt (Media3 can report 0 while restarting).
                    progress = event.progress?.coerceIn(0f, 1f)?.let { p -> maxOf(p, progress ?: 0f) } ?: progress,
                    bytesDownloaded = maxOf(event.bytes, bytesDownloaded),
                    waitingForNetwork = event.waitingForNetwork,
                )
            } else {
                null
            }
        JobEvent.StartExporting ->
            if (state == Downloading || state == Exporting) {
                copy(state = Exporting, progress = null, waitingForNetwork = false)
            } else {
                null
            }
        is JobEvent.ExportProgress ->
            if (state == Exporting) copy(progress = event.progress?.coerceIn(0f, 1f)) else null
        is JobEvent.Complete ->
            if (state == Exporting) copy(state = Completed, progress = 1f, output = event.output, error = null) else null
        is JobEvent.Fail ->
            if (state.isActive) copy(state = Failed, error = event.error, waitingForNetwork = false) else null
        JobEvent.Cancel ->
            if (state.isActive) copy(state = Cancelled, waitingForNetwork = false) else null
        JobEvent.Retry ->
            if (state == Failed || state == Cancelled) {
                copy(
                    state = Queued,
                    progress = null,
                    bytesDownloaded = 0,
                    error = null,
                    attempt = attempt + 1,
                )
            } else {
                null
            }
    }
    // The timestamp marks state changes, not progress ticks (which would otherwise be persisted).
    val isProgress = event is JobEvent.DownloadProgress || event is JobEvent.ExportProgress
    return if (isProgress) next else next?.copy(updatedAtMillis = nowMillis)
}
