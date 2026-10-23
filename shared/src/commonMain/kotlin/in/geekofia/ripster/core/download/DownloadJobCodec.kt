package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.NetworkProblem
import `in`.geekofia.ripster.core.source.UrlProblem

/**
 * Stores the queue as one string without a JSON library, like MorseKit's history: jobs are
 * separated by [RECORD], fields by [FIELD], and sub-fields (lists, the error) by [SUB]. Text is
 * escaped so any URL or title survives. A job that doesn't parse is skipped, so corrupt data
 * loses at most that job, never the app.
 */
internal object DownloadJobCodec {
    private const val VERSION = "q1"
    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'
    private const val SUB = '\u001D'
    private const val FIELDS = 23

    fun encode(jobs: List<DownloadJob>): String = buildString {
        append(VERSION)
        jobs.forEach { job ->
            append(RECORD)
            append(fields(job).joinToString(FIELD.toString()) { escape(it) })
        }
    }

    fun decode(encoded: String?): List<DownloadJob> {
        val records = encoded?.split(RECORD) ?: return emptyList()
        if (records.firstOrNull() != VERSION) return emptyList()
        return records.drop(1).mapNotNull { record ->
            val f = record.split(FIELD).map { unescape(it) ?: return@mapNotNull null }
            if (f.size != FIELDS) return@mapNotNull null
            runCatching { job(f) }.getOrNull()
        }
    }

    private fun fields(j: DownloadJob): List<String> = listOf(
        j.id,
        j.title,
        j.sourceUrl,
        j.manifestUrl,
        bool(j.isSample),
        j.selection.variantUrl.orEmpty(),
        j.selection.audioUrl.orEmpty(),
        j.selection.bandwidth?.toString().orEmpty(),
        j.selection.resolution.orEmpty(),
        j.selection.codecs.joinToString(SUB.toString()),
        j.durationSeconds?.toString().orEmpty(),
        j.state.name,
        j.progress?.toString().orEmpty(),
        j.bytesDownloaded.toString(),
        j.error?.let(::encodeError).orEmpty(),
        j.output?.uri.orEmpty(),
        j.output?.displayName.orEmpty(),
        j.output?.sizeBytes?.toString().orEmpty(),
        j.output?.location.orEmpty(),
        j.attempt.toString(),
        j.createdAtMillis.toString(),
        j.updatedAtMillis.toString(),
        "", // Reserved for request configuration (headers) in a later version.
    )

    private fun job(f: List<String>): DownloadJob? {
        val state = DownloadState.entries.firstOrNull { it.name == f[11] } ?: return null
        val output = if (f[15].isNotEmpty()) {
            OutputFile(uri = f[15], displayName = f[16], sizeBytes = f[17].toLongOrNull() ?: 0, location = f[18])
        } else {
            null
        }
        return DownloadJob(
            id = f[0].takeIf { it.isNotEmpty() } ?: return null,
            title = f[1],
            sourceUrl = f[2],
            manifestUrl = f[3],
            isSample = f[4] == "1",
            selection = JobSelection(
                variantUrl = f[5].ifEmpty { null },
                audioUrl = f[6].ifEmpty { null },
                bandwidth = f[7].toLongOrNull(),
                resolution = f[8].ifEmpty { null },
                codecs = if (f[9].isEmpty()) emptyList() else f[9].split(SUB),
            ),
            durationSeconds = f[10].toDoubleOrNull(),
            state = state,
            progress = f[12].toFloatOrNull(),
            bytesDownloaded = f[13].toLongOrNull() ?: 0,
            error = if (f[14].isEmpty()) null else decodeError(f[14]),
            output = output,
            attempt = f[19].toIntOrNull() ?: 1,
            createdAtMillis = f[20].toLongOrNull() ?: return null,
            updatedAtMillis = f[21].toLongOrNull() ?: return null,
        )
    }

    internal fun encodeError(e: MediaError): String = when (e) {
        is MediaError.InvalidUrl -> listOf("url", e.problem.name)
        MediaError.WebPage -> listOf("webpage")
        is MediaError.NotHls -> listOf("nothls", e.contentType.orEmpty())
        MediaError.LiveStream -> listOf("live")
        MediaError.DrmProtected -> listOf("drm")
        MediaError.SampleAesEncrypted -> listOf("sampleaes")
        is MediaError.HttpStatus -> listOf("http", e.code.toString(), bool(e.signedUrl))
        is MediaError.Network -> listOf("net", e.problem.name)
        is MediaError.Parse -> listOf("parse", e.detail)
        is MediaError.InsufficientStorage ->
            listOf("storage", e.requiredBytes?.toString().orEmpty(), e.availableBytes?.toString().orEmpty())
        is MediaError.OutputUnavailable -> listOf("output", e.detail)
        is MediaError.ExportUnsupported -> listOf("exportunsupported", e.detail)
        is MediaError.ExportFailed -> listOf("exportfailed", e.detail)
        is MediaError.Unknown -> listOf("unknown", e.detail)
    }.joinToString(SUB.toString())

    /** Unknown or malformed errors decode as [MediaError.Unknown] rather than dropping the job. */
    internal fun decodeError(encoded: String): MediaError {
        val p = encoded.split(SUB)
        fun arg(i: Int) = p.getOrElse(i) { "" }
        return when (p[0]) {
            "url" -> UrlProblem.entries.firstOrNull { it.name == arg(1) }?.let(MediaError::InvalidUrl)
            "webpage" -> MediaError.WebPage
            "nothls" -> MediaError.NotHls(arg(1).ifEmpty { null })
            "live" -> MediaError.LiveStream
            "drm" -> MediaError.DrmProtected
            "sampleaes" -> MediaError.SampleAesEncrypted
            "http" -> arg(1).toIntOrNull()?.let { MediaError.HttpStatus(it, arg(2) == "1") }
            "net" -> NetworkProblem.entries.firstOrNull { it.name == arg(1) }?.let(MediaError::Network)
            "parse" -> MediaError.Parse(arg(1))
            "storage" -> MediaError.InsufficientStorage(arg(1).toLongOrNull(), arg(2).toLongOrNull())
            "output" -> MediaError.OutputUnavailable(arg(1))
            "exportunsupported" -> MediaError.ExportUnsupported(arg(1))
            "exportfailed" -> MediaError.ExportFailed(arg(1))
            "unknown" -> MediaError.Unknown(arg(1))
            else -> null
        } ?: MediaError.Unknown("Unrecognised error")
    }

    private fun bool(value: Boolean) = if (value) "1" else "0"

    private fun escape(text: String): String = buildString {
        text.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                RECORD -> append("\\r")
                FIELD -> append("\\f")
                else -> append(c)
            }
        }
    }

    /** `null` if [text] has a broken escape. [SUB] needs no escape: it never appears in user text. */
    private fun unescape(text: String): String? = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\') {
                append(c)
                i++
                continue
            }
            when (text.getOrNull(i + 1)) {
                '\\' -> append('\\')
                'r' -> append(RECORD)
                'f' -> append(FIELD)
                else -> return null
            }
            i += 2
        }
    }
}
