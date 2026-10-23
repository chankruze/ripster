package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.source.MediaError
import kotlinx.coroutines.flow.StateFlow

/** The platform downloader's view of one job's media download. */
sealed interface EngineStatus {
    data class Queued(val waitingForNetwork: Boolean) : EngineStatus

    /** [progress] is 0..1, or null while the engine can't tell yet. */
    data class Downloading(val progress: Float?, val bytes: Long, val waitingForNetwork: Boolean = false) : EngineStatus
    data class Completed(val bytes: Long) : EngineStatus
    data class Failed(val error: MediaError) : EngineStatus
}

/**
 * Everything the engine knows, as state rather than events, so nothing is lost if the collector
 * is slow or starts late (e.g. after process death). [loaded] is false until the engine has read
 * its persisted index; until then an absent job means "not known yet", not "gone".
 *
 * [version] grows with every change. A StateFlow collector skips a value equal to the last one it
 * saw, so without it a quick A → B → A change could go unnoticed and leave a job unreconciled.
 */
data class EngineSnapshot(val loaded: Boolean, val downloads: Map<String, EngineStatus>, val version: Long = 0) {
    /** The next version with [transform] applied to the downloads. */
    fun next(loaded: Boolean = this.loaded, transform: (Map<String, EngineStatus>) -> Map<String, EngineStatus>) =
        EngineSnapshot(loaded, transform(downloads), version + 1)

    companion object {
        val NotLoaded = EngineSnapshot(loaded = false, downloads = emptyMap())
    }
}

/**
 * Durable, resumable media download (Media3 `DownloadManager` on Android). The engine owns the
 * downloaded segments; the MP4 export reads them back by job id.
 */
interface DownloadEngine {
    /** Every change must produce a new [EngineSnapshot.version] (use [EngineSnapshot.next]). */
    val snapshot: StateFlow<EngineSnapshot>

    /**
     * Starts or resumes the job's download. Calling it again for a known id resumes, keeping data
     * already downloaded. Must make the job visible in [snapshot] (at least as Queued) on return.
     */
    suspend fun enqueue(job: DownloadJob)

    /** Stops the download if running and deletes its downloaded data. Safe for unknown ids. */
    suspend fun remove(jobId: String)
}

/** Free space where downloads and exports are written; null when the platform can't tell. */
fun interface StorageInfo {
    fun availableBytes(): Long?
}
