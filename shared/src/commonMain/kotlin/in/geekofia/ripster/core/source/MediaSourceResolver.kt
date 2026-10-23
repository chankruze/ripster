package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.core.export.ExportOutlook
import `in`.geekofia.ripster.core.hls.HlsAudioRendition
import `in`.geekofia.ripster.core.hls.HlsEncryption
import `in`.geekofia.ripster.core.hls.HlsVariant
import `in`.geekofia.ripster.core.hls.SegmentContainer

enum class ManifestKind {
    /** A master playlist listing quality variants. */
    Multivariant,

    /** A single media playlist: one quality, listed segment by segment. */
    Media,
}

/** What inspection learned from the media playlist that would be downloaded. */
data class MediaDetails(
    val durationSeconds: Double,
    val segmentCount: Int,
    val container: SegmentContainer,
    val encryption: HlsEncryption,
)

/** One choice for the download: a variant (if the manifest has several) and its audio rendition. */
data class MediaSelection(
    /** Null for a single media playlist. */
    val variant: HlsVariant?,
    /** Null when audio is muxed into the variant or the variant has no separate audio. */
    val audio: HlsAudioRendition?,
) {
    val outlook: ExportOutlook get() = ExportOutlook.forCodecs(variant?.codecs.orEmpty())
}

/** A direct HLS source that passed inspection. URLs may hold tokens: don't display them in full. */
data class ResolvedSource(
    val inputUrl: String,
    /** The manifest's address after redirects; downloads start here. */
    val manifestUrl: String,
    val kind: ManifestKind,
    /** Best first (highest resolution, then bandwidth). Empty for a [ManifestKind.Media] playlist. */
    val variants: List<HlsVariant>,
    val audioRenditions: List<HlsAudioRendition>,
    val defaultVariantIndex: Int?,
    val details: MediaDetails,
) {
    val defaultSelection: MediaSelection get() = selectionFor(defaultVariantIndex?.let(variants::getOrNull))

    fun selectionFor(variant: HlsVariant?): MediaSelection = MediaSelection(variant, audioFor(variant))

    /** The rendition the player would pick: the group's default, else its first with a URI. */
    private fun audioFor(variant: HlsVariant?): HlsAudioRendition? {
        val group = variant?.audioGroupId ?: return null
        val candidates = audioRenditions.filter { it.groupId == group && it.url != null }
        return candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull()
    }

    /**
     * The variant matching a previous choice: the same URL, else (when URLs carry fresh tokens)
     * the same resolution, codecs and bandwidth. Null if the stream changed beyond recognition.
     */
    fun findVariant(url: String?, bandwidth: Long?, resolution: String?, codecs: List<String>): HlsVariant? {
        if (url == null) return null
        return variants.firstOrNull { it.url == url }
            ?: variants.firstOrNull {
                it.bandwidth == bandwidth && it.resolution?.toString() == resolution && it.codecs == codecs
            }
    }
}

sealed interface SourceResolution {
    data class Supported(val source: ResolvedSource) : SourceResolution

    /** See [MediaError.category] for the kind of rejection (user action, unsupported, expired, …). */
    data class Rejected(val error: MediaError) : SourceResolution
}

/**
 * Turns user input into a downloadable source. This phase supports direct HLS manifests only;
 * other permitted sources can be added as further implementations. No web page scraping.
 */
interface MediaSourceResolver {
    suspend fun resolve(input: String): SourceResolution
}
