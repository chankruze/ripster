package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.core.hls.HlsEncryption
import `in`.geekofia.ripster.core.hls.SegmentContainer
import `in`.geekofia.ripster.platform.HttpClient
import kotlinx.coroutines.CancellationException

/**
 * Recognises direct media files (MP4, MKV, WebM) by probing their first bytes, so a multi-GB
 * file is identified without downloading it. Anything else is left to the next resolver.
 */
class DirectFileResolver(private val http: HttpClient) {

    sealed interface Probe {
        data class File(val source: ResolvedSource) : Probe

        /** Reachable, but not a recognised media file (e.g. a playlist or a page). */
        data object NotAFile : Probe

        /** The request itself failed; no point asking again in another way. */
        data class Failed(val error: MediaError) : Probe
    }

    suspend fun probe(input: String): Probe {
        val url = (SourceUrl.check(input) as? UrlCheck.Valid)?.url ?: return Probe.NotAFile
        val response = try {
            http.probe(url, ContainerSniffer.SNIFF_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            return Probe.Failed(e.error)
        }
        if (response.statusCode !in 200..299) {
            return Probe.Failed(MediaError.HttpStatus(response.statusCode, SourceUrl.looksSigned(url)))
        }
        val container = ContainerSniffer.sniff(response.body) ?: return Probe.NotAFile
        return Probe.File(
            ResolvedSource(
                inputUrl = url,
                manifestUrl = response.finalUrl,
                kind = ManifestKind.DirectFile,
                variants = emptyList(),
                audioRenditions = emptyList(),
                defaultVariantIndex = null,
                details = MediaDetails(durationSeconds = null, segmentCount = null, SegmentContainer.Other, HlsEncryption.None),
                file = DirectFileInfo(container, response.totalBytes),
            ),
        )
    }
}
