package `in`.geekofia.ripster.core.hls

data class Resolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}×$height"
}

/** One `#EXT-X-STREAM-INF` entry of a multivariant (master) playlist. URLs are absolute. */
data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val averageBandwidth: Long? = null,
    val resolution: Resolution? = null,
    val frameRate: Double? = null,
    /** RFC 6381 codec strings, e.g. `avc1.640020`, `mp4a.40.2`. Empty when the playlist omits them. */
    val codecs: List<String> = emptyList(),
    /** The `AUDIO` group whose renditions carry this variant's audio, if it's not muxed in. */
    val audioGroupId: String? = null,
)

/** An `#EXT-X-MEDIA:TYPE=AUDIO` rendition. [url] is null when the audio is muxed into the variant. */
data class HlsAudioRendition(
    val groupId: String,
    val name: String,
    val language: String? = null,
    val channels: String? = null,
    val url: String? = null,
    val isDefault: Boolean = false,
)

enum class HlsEncryption {
    None,

    /** Whole-segment AES-128 with a key from the playlist: a clear key, which Media3 handles. */
    Aes128,

    /** Sample-level encryption: not supported for download and export. */
    SampleAes,

    /** A key format other than `identity` (FairPlay, Widevine, PlayReady, …). Never bypassed. */
    Drm,
}

enum class SegmentContainer { MpegTs, FragmentedMp4, Other, Unknown }

sealed interface HlsPlaylist {
    data class Multivariant(
        val variants: List<HlsVariant>,
        val audioRenditions: List<HlsAudioRendition>,
        /** From `#EXT-X-SESSION-KEY`: DRM declared up front for the whole presentation. */
        val sessionEncryption: HlsEncryption,
    ) : HlsPlaylist

    data class Media(
        val segmentCount: Int,
        val durationSeconds: Double,
        /** `#EXT-X-ENDLIST` present (or a VOD playlist type): the full list of segments is known. */
        val isComplete: Boolean,
        val encryption: HlsEncryption,
        val container: SegmentContainer,
    ) : HlsPlaylist {
        val isLive: Boolean get() = !isComplete
    }
}

sealed interface HlsParseResult {
    data class Success(val playlist: HlsPlaylist) : HlsParseResult
    data class Failure(val detail: String) : HlsParseResult
}
