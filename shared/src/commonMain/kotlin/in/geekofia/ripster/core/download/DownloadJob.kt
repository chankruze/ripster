package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.source.FileContainer
import `in`.geekofia.ripster.core.source.MediaError

enum class DownloadState {
    Queued,
    Resolving,
    Downloading,
    Exporting,
    Completed,
    Failed,
    Cancelled;

    val isTerminal: Boolean get() = this == Completed || this == Failed || this == Cancelled
    val isActive: Boolean get() = !isTerminal
}

/** The variant chosen at enqueue time, kept so a retry can find it again after re-inspection. */
data class JobSelection(
    val variantUrl: String?,
    val audioUrl: String?,
    val bandwidth: Long?,
    /** `1920×1080`, as [in.geekofia.ripster.core.hls.Resolution.toString]. */
    val resolution: String?,
    val codecs: List<String>,
)

/** The exported file. [uri] is platform-specific (a `file:` or `content:` URI on Android). */
data class OutputFile(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    /** Where it was saved, for display (e.g. "App storage" or the chosen folder's name). */
    val location: String,
    val mimeType: String = FileContainer.Mp4.mimeType,
)

/**
 * One queue entry. Persisted as a whole (see [DownloadJobCodec]); [waitingForNetwork] is
 * transient and comes from the engine. URLs may contain tokens and are never displayed in full.
 */
data class DownloadJob(
    val id: String,
    /** A safe display name, also the base of the output file name. */
    val title: String,
    val sourceUrl: String,
    /** The manifest URL after redirects, from the latest inspection. */
    val manifestUrl: String,
    val isSample: Boolean,
    /** Set for a direct file, which is saved in this container as is; null for HLS (exported to MP4). */
    val file: FileContainer? = null,
    val selection: JobSelection,
    val durationSeconds: Double?,
    /** A preview image URL (https) from the source page, if any. */
    val thumbnailUrl: String? = null,
    val state: DownloadState,
    /** 0..1 for the current phase (download or export); null when not known. Never estimated. */
    val progress: Float? = null,
    val bytesDownloaded: Long = 0,
    val error: MediaError? = null,
    val output: OutputFile? = null,
    val attempt: Int = 1,
    val createdAtMillis: Long,
    /** When the state last changed (progress updates don't count). */
    val updatedAtMillis: Long,
    val waitingForNetwork: Boolean = false,
)
