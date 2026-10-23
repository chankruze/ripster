package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.core.export.ExportOutlook
import `in`.geekofia.ripster.core.hls.HlsEncryption
import `in`.geekofia.ripster.core.hls.HlsParseResult
import `in`.geekofia.ripster.core.hls.HlsPlaylist
import `in`.geekofia.ripster.core.hls.HlsPlaylistParser
import `in`.geekofia.ripster.core.hls.HlsVariant
import `in`.geekofia.ripster.platform.HttpClient
import kotlinx.coroutines.CancellationException

/**
 * Resolves direct HLS manifest URLs. The response is inspected (status, body and type), never
 * assumed: an https URL is only accepted when it actually serves a complete, unprotected playlist.
 *
 * For a multivariant playlist the best variant's media playlist is fetched too, because that's
 * where duration, completeness (VOD vs live) and encryption are declared.
 */
class HlsSourceResolver(private val http: HttpClient) : MediaSourceResolver {

    override suspend fun resolve(input: String): SourceResolution {
        val url = when (val check = SourceUrl.check(input)) {
            is UrlCheck.Invalid -> return reject(MediaError.InvalidUrl(check.problem))
            is UrlCheck.Valid -> check.url
        }
        return try {
            val (finalUrl, playlist) = fetchPlaylist(url)
            when (playlist) {
                is HlsPlaylist.Media -> supported(url, finalUrl, ManifestKind.Media, emptyList(), playlist, null)
                is HlsPlaylist.Multivariant -> resolveMultivariant(url, finalUrl, playlist)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            reject(e.error)
        }
    }

    private suspend fun resolveMultivariant(
        inputUrl: String,
        manifestUrl: String,
        playlist: HlsPlaylist.Multivariant,
    ): SourceResolution {
        if (playlist.sessionEncryption == HlsEncryption.Drm) return reject(MediaError.DrmProtected)
        val variants = playlist.variants.sortedWith(
            compareByDescending<HlsVariant> { it.resolution?.let { r -> r.width.toLong() * r.height } ?: 0L }
                .thenByDescending { it.bandwidth },
        )
        val defaultIndex = defaultVariantIndex(variants)
        val (_, media) = fetchPlaylist(variants[defaultIndex].url)
        if (media !is HlsPlaylist.Media) return reject(MediaError.Parse("variant is not a media playlist"))
        val source = ResolvedSource(
            inputUrl = inputUrl,
            manifestUrl = manifestUrl,
            kind = ManifestKind.Multivariant,
            variants = variants,
            audioRenditions = playlist.audioRenditions,
            defaultVariantIndex = defaultIndex,
            details = media.details(),
        )
        return validated(source, media)
    }

    private fun supported(
        inputUrl: String,
        manifestUrl: String,
        kind: ManifestKind,
        variants: List<HlsVariant>,
        media: HlsPlaylist.Media,
        defaultIndex: Int?,
    ): SourceResolution = validated(
        ResolvedSource(inputUrl, manifestUrl, kind, variants, emptyList(), defaultIndex, media.details()),
        media,
    )

    private fun validated(source: ResolvedSource, media: HlsPlaylist.Media): SourceResolution = when {
        media.isLive -> reject(MediaError.LiveStream)
        media.encryption == HlsEncryption.Drm -> reject(MediaError.DrmProtected)
        media.encryption == HlsEncryption.SampleAes -> reject(MediaError.SampleAesEncrypted)
        media.segmentCount == 0 -> reject(MediaError.Parse("playlist has no segments"))
        else -> SourceResolution.Supported(source)
    }

    /** Fetches and parses one playlist, mapping every failure to a [MediaException]. */
    private suspend fun fetchPlaylist(url: String): Pair<String, HlsPlaylist> {
        val response = http.get(url, MAX_MANIFEST_BYTES)
        if (response.statusCode !in 200..299) {
            throw MediaException(MediaError.HttpStatus(response.statusCode, SourceUrl.looksSigned(url)))
        }
        val text = response.body.decodeToString()
        if (!HlsPlaylistParser.looksLikePlaylist(text)) {
            val type = response.contentType?.substringBefore(';')?.trim()?.lowercase()
            val isHtml = type == "text/html" || type == "application/xhtml+xml" ||
                text.trimStart().take(256).lowercase().let { it.startsWith("<!doctype html") || it.startsWith("<html") }
            throw MediaException(if (isHtml) MediaError.WebPage else MediaError.NotHls(type))
        }
        return when (val parsed = HlsPlaylistParser.parse(text, response.finalUrl)) {
            is HlsParseResult.Failure -> throw MediaException(MediaError.Parse(parsed.detail))
            is HlsParseResult.Success -> response.finalUrl to parsed.playlist
        }
    }

    private fun reject(error: MediaError) = SourceResolution.Rejected(error)

    private fun HlsPlaylist.Media.details() = MediaDetails(durationSeconds, segmentCount, container, encryption)

    internal companion object {
        /** Manifests are text; anything bigger is not something to parse in memory. */
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

        /** Prefers the best variant that should remux cleanly; otherwise the best overall. */
        fun defaultVariantIndex(sortedVariants: List<HlsVariant>): Int =
            sortedVariants.indexOfFirst { ExportOutlook.forCodecs(it.codecs) == ExportOutlook.Remux }
                .takeIf { it >= 0 } ?: 0
    }
}
