package `in`.geekofia.ripster.core.export

import `in`.geekofia.ripster.core.download.DownloadJob
import `in`.geekofia.ripster.core.download.OutputFile

/**
 * Writes a downloaded job to a standalone MP4. Separate from the download: it reads the
 * engine's completed data and is restarted from scratch if interrupted (MP4 muxing can't resume).
 *
 * Implementations must delete partial output on failure or cancellation, never overwrite an
 * existing file, validate the result before returning it, and throw `MediaException` with a
 * typed error (e.g. `ExportUnsupported` for codecs the device can't handle).
 */
interface MediaExporter {
    /**
     * Writes [job] to [destination] (null: the app's own storage). [onProgress] gets 0..1, or null
     * while progress is unavailable. Cancellable.
     */
    suspend fun export(job: DownloadJob, destination: OutputFolder?, onProgress: (Float?) -> Unit): OutputFile

    /** Deletes an output that was produced but is no longer wanted (e.g. cancelled at the last moment). */
    suspend fun discard(output: OutputFile)
}

/** A user-chosen output folder. [uri] is platform-specific (a SAF tree URI on Android). */
data class OutputFolder(val uri: String, val name: String)

/** File names from remote data are untrusted: keep them short and free of path or reserved characters. */
object FileNames {
    private const val MAX_LENGTH = 80
    private val reserved = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun sanitize(name: String, fallback: String = "video"): String {
        val cleaned = name
            .map { c -> if (c.code < 0x20 || c in reserved) ' ' else c }
            .joinToString("")
            .split(Regex("\\s+"))
            // Dot-only parts are path traversal remnants ("../"), never part of a real title.
            .filter { part -> part.isNotEmpty() && part.any { it != '.' } }
            .joinToString(" ")
            .trim('.')
            .trim()
            .take(MAX_LENGTH)
            .trim()
        return cleaned.ifEmpty { fallback }
    }

    /** `name.mp4`, `name (2).mp4`, … : the first candidate for which [exists] is false. */
    fun unique(baseName: String, extension: String, exists: (String) -> Boolean): String {
        val first = "$baseName.$extension"
        if (!exists(first)) return first
        var n = 2
        while (true) {
            val candidate = "$baseName ($n).$extension"
            if (!exists(candidate)) return candidate
            n++
        }
    }
}
