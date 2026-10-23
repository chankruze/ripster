package `in`.geekofia.ripster.core.hls

import `in`.geekofia.ripster.core.source.SourceUrl

/**
 * A small, portable reader for the parts of RFC 8216 that inspection needs: variants, audio
 * renditions, segment count and duration, completeness and encryption. It doesn't replace
 * Media3's parser, which still drives the actual Android download.
 */
object HlsPlaylistParser {
    private const val HEADER = "#EXTM3U"

    /** True when [text] starts like an M3U8 playlist (ignoring a BOM and leading whitespace). */
    fun looksLikePlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\t', '\r', '\n').startsWith(HEADER)

    fun parse(text: String, baseUrl: String): HlsParseResult {
        if (!looksLikePlaylist(text)) return HlsParseResult.Failure("Missing #EXTM3U header")
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val isMultivariant = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        val isMedia = lines.any { it.startsWith("#EXTINF") }
        return when {
            isMultivariant && isMedia -> HlsParseResult.Failure("Playlist mixes variants and segments")
            isMultivariant -> parseMultivariant(lines, baseUrl)
            isMedia -> parseMedia(lines)
            lines.any { it.startsWith("#EXT-X-ENDLIST") } -> HlsParseResult.Failure("Playlist has no segments")
            else -> HlsParseResult.Failure("Playlist has no variants or segments")
        }
    }

    private fun parseMultivariant(lines: List<String>, baseUrl: String): HlsParseResult {
        val variants = mutableListOf<HlsVariant>()
        val audios = mutableListOf<HlsAudioRendition>()
        var sessionEncryption = HlsEncryption.None
        var pending: Map<String, String>? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(line.substringAfter(':'))
                    if (a["TYPE"] == "AUDIO") {
                        val groupId = a["GROUP-ID"] ?: continue
                        audios += HlsAudioRendition(
                            groupId = groupId,
                            name = a["NAME"] ?: groupId,
                            language = a["LANGUAGE"],
                            channels = a["CHANNELS"],
                            url = a["URI"]?.let { SourceUrl.resolve(baseUrl, it) },
                            isDefault = a["DEFAULT"] == "YES",
                        )
                    }
                }
                line.startsWith("#EXT-X-SESSION-KEY:") ->
                    sessionEncryption = maxOf(sessionEncryption, encryptionOf(attributes(line.substringAfter(':'))))
                line.startsWith("#") -> Unit
                else -> {
                    val a = pending ?: continue
                    pending = null
                    val bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: continue
                    val url = SourceUrl.resolve(baseUrl, line)
                    // Same URL twice (e.g. alternate pathways) is the same media for download purposes.
                    if (variants.any { it.url == url }) continue
                    variants += HlsVariant(
                        url = url,
                        bandwidth = bandwidth,
                        averageBandwidth = a["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                        resolution = a["RESOLUTION"]?.let(::resolution),
                        frameRate = a["FRAME-RATE"]?.toDoubleOrNull(),
                        codecs = a["CODECS"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                        audioGroupId = a["AUDIO"],
                    )
                }
            }
        }
        if (variants.isEmpty()) return HlsParseResult.Failure("No usable variants")
        return HlsParseResult.Success(HlsPlaylist.Multivariant(variants, audios, sessionEncryption))
    }

    private fun parseMedia(lines: List<String>): HlsParseResult {
        var segments = 0
        var duration = 0.0
        var complete = false
        var encryption = HlsEncryption.None
        var hasMap = false
        var firstSegment: String? = null
        var awaitingUri = false

        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> {
                    val seconds = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                        ?: return HlsParseResult.Failure("Malformed #EXTINF")
                    duration += seconds
                    segments++
                    awaitingUri = true
                }
                line.startsWith("#EXT-X-ENDLIST") -> complete = true
                line == "#EXT-X-PLAYLIST-TYPE:VOD" -> complete = true
                line.startsWith("#EXT-X-MAP:") -> hasMap = true
                line.startsWith("#EXT-X-KEY:") ->
                    encryption = maxOf(encryption, encryptionOf(attributes(line.substringAfter(':'))))
                line.startsWith("#") -> Unit
                awaitingUri -> {
                    if (firstSegment == null) firstSegment = line
                    awaitingUri = false
                }
            }
        }
        val extension = firstSegment?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase()
        val container = when {
            hasMap -> SegmentContainer.FragmentedMp4
            extension == "ts" -> SegmentContainer.MpegTs
            extension in setOf("m4s", "mp4", "cmfv", "cmfa") -> SegmentContainer.FragmentedMp4
            extension.isNullOrEmpty() -> SegmentContainer.Unknown
            else -> SegmentContainer.Other
        }
        return HlsParseResult.Success(HlsPlaylist.Media(segments, duration, complete, encryption, container))
    }

    private fun encryptionOf(a: Map<String, String>): HlsEncryption {
        val format = a["KEYFORMAT"]
        return when {
            format != null && format != "identity" -> HlsEncryption.Drm
            a["METHOD"] == "AES-128" -> HlsEncryption.Aes128
            a["METHOD"]?.startsWith("SAMPLE-AES") == true -> HlsEncryption.SampleAes
            else -> HlsEncryption.None
        }
    }

    private fun resolution(value: String): Resolution? {
        val (w, h) = value.split('x', 'X').takeIf { it.size == 2 } ?: return null
        return Resolution(w.toIntOrNull() ?: return null, h.toIntOrNull() ?: return null)
    }

    /** Parses an attribute list: `KEY=value,KEY="quoted, with commas"`. Quotes are removed. */
    internal fun attributes(list: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        while (i < list.length) {
            val eq = list.indexOf('=', i).takeIf { it >= 0 } ?: break
            val key = list.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < list.length && list[j] == '"') {
                val close = list.indexOf('"', j + 1).takeIf { it >= 0 } ?: list.length
                value = list.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = list.indexOf(',', j).takeIf { it >= 0 } ?: list.length
                value = list.substring(j, comma).trim()
                j = comma
            }
            // Remote text is untrusted: control characters are dropped before anything stores it.
            if (key.isNotEmpty()) result[key] = value.filter { it >= ' ' }
            i = list.indexOf(',', j).takeIf { it >= 0 }?.plus(1) ?: list.length
        }
        return result
    }
}
