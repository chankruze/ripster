package `in`.geekofia.ripster.download

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.DownloadRequest
import `in`.geekofia.ripster.core.download.DownloadJob
import `in`.geekofia.ripster.core.source.HlsSourceResolver
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.core.source.SourceUrl
import `in`.geekofia.ripster.platform.HttpClient

/**
 * Builds the Media3 request for a job: the manifest plus stream keys for exactly the chosen
 * variant and its audio rendition, so only that quality is downloaded.
 *
 * The keys are indices into Media3's own parse of the playlist (it orders and de-duplicates
 * differently from Ripster's portable parser), so the manifest is parsed again here with
 * [HlsPlaylistParser] rather than trusting indices from inspection.
 */
@OptIn(UnstableApi::class)
internal class DownloadRequestFactory(private val http: HttpClient) {

    suspend fun build(job: DownloadJob): DownloadRequest {
        val builder = DownloadRequest.Builder(job.id, Uri.parse(job.manifestUrl)).setMimeType(MimeTypes.APPLICATION_M3U8)
        val variantUrl = job.selection.variantUrl ?: return builder.build() // A single media playlist.

        val response = http.get(job.manifestUrl, HlsSourceResolver.MAX_MANIFEST_BYTES)
        if (response.statusCode !in 200..299) {
            throw MediaException(MediaError.HttpStatus(response.statusCode, SourceUrl.looksSigned(job.manifestUrl)))
        }
        val playlist = try {
            HlsPlaylistParser().parse(Uri.parse(response.finalUrl), response.body.inputStream())
        } catch (e: Exception) {
            throw MediaException(MediaError.Parse("the manifest couldn't be read"), e)
        }
        if (playlist !is HlsMultivariantPlaylist) throw MediaException(MediaError.Parse("the manifest no longer lists variants"))

        val variantIndex = playlist.variants.indexOfFirst { it.url.toString() == variantUrl }.takeIf { it >= 0 }
            ?: playlist.variants.indexOfFirst { variant ->
                val format = variant.format
                format.peakBitrate.toLong() == job.selection.bandwidth &&
                    "${format.width}×${format.height}" == job.selection.resolution
            }.takeIf { it >= 0 }
            ?: throw MediaException(MediaError.Parse("the selected quality is no longer offered"))

        val keys = mutableListOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, variantIndex))
        job.selection.audioUrl?.let { audioUrl ->
            val audioIndex = playlist.audios.indexOfFirst { it.url?.toString() == audioUrl }
            if (audioIndex >= 0) keys += StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, audioIndex)
        }
        // The request points at the playlist actually parsed, so the keys match what Media3 loads.
        return DownloadRequest.Builder(job.id, Uri.parse(response.finalUrl))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setStreamKeys(keys)
            .build()
    }
}
