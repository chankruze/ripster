package `in`.geekofia.ripster.core.export

/** What exporting a variant to MP4 is expected to involve, judged from its declared codecs. */
enum class ExportOutlook {
    /** H.264/H.265 with AAC (or no audio): the segments can be copied into MP4 without re-encoding. */
    Remux,

    /** Audio such as AC-3 / E-AC-3: MP4 export needs a decoder for it on the device, and may fail. */
    AudioMayNeedTranscoding,

    /** Video codecs other than H.264/H.265 (e.g. VP9, AV1): export may need re-encoding or fail. */
    VideoMayNeedTranscoding,

    /** The playlist doesn't declare codecs; only the export itself can tell. */
    Unknown;

    companion object {
        private val remuxVideo = listOf("avc1", "avc3", "hvc1", "hev1")
        private val remuxAudio = listOf("mp4a")

        fun forCodecs(codecs: List<String>): ExportOutlook {
            if (codecs.isEmpty()) return Unknown
            val families = codecs.map { it.substringBefore('.').lowercase() }.filter { it !in textCodecs }
            val video = families.filter { it in remuxVideo || it in otherVideo }
            val audio = families - video.toSet()
            return when {
                video.any { it !in remuxVideo } -> VideoMayNeedTranscoding
                audio.any { it !in remuxAudio } -> AudioMayNeedTranscoding
                else -> Remux
            }
        }

        private val otherVideo = listOf("vp09", "vp8", "av01", "dvh1", "dvhe", "mp4v")

        /** Subtitle codecs, which the MP4 export leaves out. */
        private val textCodecs = listOf("stpp", "wvtt")
    }
}

/** A short, human label for codec strings: `avc1.640020` → `H.264`, `mp4a.40.2` → `AAC`. */
fun codecLabel(codec: String): String = when (codec.substringBefore('.').lowercase()) {
    "avc1", "avc3" -> "H.264"
    "hvc1", "hev1" -> "H.265"
    "dvh1", "dvhe" -> "Dolby Vision"
    "vp09" -> "VP9"
    "av01" -> "AV1"
    "mp4a" -> "AAC"
    "ac-3" -> "AC-3"
    "ec-3" -> "E-AC-3"
    "opus" -> "Opus"
    "flac" -> "FLAC"
    else -> codec
}
