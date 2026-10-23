package `in`.geekofia.ripster.feature.home

import `in`.geekofia.ripster.core.download.EnqueueResult
import `in`.geekofia.ripster.core.export.ExportOutlook
import `in`.geekofia.ripster.core.export.codecLabel
import `in`.geekofia.ripster.core.hls.HlsVariant
import `in`.geekofia.ripster.core.hls.SegmentContainer
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaSelection
import `in`.geekofia.ripster.core.source.ResolvedSource
import `in`.geekofia.ripster.core.source.SampleSource
import `in`.geekofia.ripster.core.source.oneDecimal

sealed interface Inspection {
    data object Idle : Inspection

    /** [url] is the input being inspected; editing it cancels the inspection. */
    data class Inspecting(val url: String) : Inspection
    data class Ready(val source: ResolvedSource, val selectedIndex: Int?) : Inspection {
        val selectedVariant: HlsVariant? get() = selectedIndex?.let(source.variants::getOrNull)
        val selection: MediaSelection get() = source.selectionFor(selectedVariant)
    }

    data class Failed(val error: MediaError) : Inspection
}

data class HomeUiState(
    val url: String = "",
    /** The sample whose URL is in the field, so samples are always labelled as such. */
    val sample: SampleSource? = null,
    val inspection: Inspection = Inspection.Idle,
    val isEnqueueing: Boolean = false,
    val lastResult: EnqueueResult? = null,
) {
    val canInspect: Boolean get() = url.isNotBlank() && inspection !is Inspection.Inspecting
    val canDownload: Boolean get() = inspection is Inspection.Ready && !isEnqueueing
}

/** `1920×1080 · 60 fps · 8.0 Mbps`, from what the playlist declares. */
fun HlsVariant.qualityLabel(): String = listOfNotNull(
    resolution?.toString(),
    frameRate?.takeIf { it > 0 }?.let { "${kotlin.math.round(it).toInt()} fps" },
    formatBitrate(averageBandwidth ?: bandwidth),
).joinToString(" · ")

fun HlsVariant.codecSummary(): String =
    codecs.map(::codecLabel).distinct().joinToString(" + ").ifEmpty { "Codecs not declared" }

fun formatBitrate(bitsPerSecond: Long): String = when {
    bitsPerSecond >= 1_000_000 -> "${oneDecimal(bitsPerSecond / 1_000_000.0)} Mbps"
    else -> "${bitsPerSecond / 1000} kbps"
}

fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

fun SegmentContainer.label(): String = when (this) {
    SegmentContainer.MpegTs -> "MPEG-TS segments"
    SegmentContainer.FragmentedMp4 -> "Fragmented MP4 segments"
    SegmentContainer.Other -> "Other segment format"
    SegmentContainer.Unknown -> "Segment format not declared"
}

/** What the output indicator says about the MP4 export, without promising success. */
fun ExportOutlook.description(): String = when (this) {
    ExportOutlook.Remux -> "Remux to MP4, without re-encoding."
    ExportOutlook.AudioMayNeedTranscoding ->
        "The audio codec usually can't be copied into MP4. Export needs a decoder on this device and may fail."
    ExportOutlook.VideoMayNeedTranscoding ->
        "The video codec may need re-encoding for MP4. Export can be slow or fail on this device."
    ExportOutlook.Unknown -> "The playlist doesn't declare codecs, so export compatibility is only known after download."
}
