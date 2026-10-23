package `in`.geekofia.ripster.download

import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.transformer.ExportException
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.core.source.NetworkProblem
import `in`.geekofia.ripster.platform.toNetworkProblem

/** Maps a Media3 download failure to a [MediaError] by walking the cause chain. */
@OptIn(UnstableApi::class)
internal fun downloadError(e: Throwable?, signedUrl: Boolean): MediaError {
    if (e == null) return MediaError.Unknown("The download failed.")
    for (cause in e.causes()) {
        when (cause) {
            is MediaException -> return cause.error
            is HttpDataSource.InvalidResponseCodeException -> return MediaError.HttpStatus(cause.responseCode, signedUrl)
            is ParserException -> return MediaError.Parse("a playlist or segment couldn't be read")
        }
        if (cause.isOutOfSpace()) return MediaError.InsufficientStorage(null, null)
    }
    e.causes().map { it.toNetworkProblem() }.firstOrNull { it != NetworkProblem.Other }?.let {
        return MediaError.Network(it)
    }
    return if (e.causes().any { it is HttpDataSource.HttpDataSourceException }) {
        MediaError.Network(NetworkProblem.Other)
    } else {
        MediaError.Unknown("The download failed (${e.javaClass.simpleName}).")
    }
}

/** Maps a Transformer failure to a typed export error, separating "unsupported" from "failed". */
@OptIn(UnstableApi::class)
internal fun exportError(e: ExportException): MediaError {
    if (e.causes().any { it.isOutOfSpace() }) return MediaError.InsufficientStorage(null, null)
    val codec = e.codecInfo?.let { if (it.isVideo) " (video)" else " (audio)" }.orEmpty()
    return when (e.errorCode) {
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> MediaError.ExportUnsupported("This device can't decode a track of this stream$codec, which MP4 export would need.")
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        -> MediaError.ExportUnsupported("A track would need re-encoding$codec, which this device doesn't support.")
        ExportException.ERROR_CODE_MUXING_FAILED,
        ExportException.ERROR_CODE_MUXING_APPEND,
        -> MediaError.ExportUnsupported("A track's format can't be written into an MP4 file$codec.")
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        ExportException.ERROR_CODE_IO_UNSPECIFIED,
        -> MediaError.DownloadedDataMissing
        else -> MediaError.ExportFailed("Export stopped: ${e.errorCodeName}.")
    }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { c -> c !== it } }.take(16)

private fun Throwable.isOutOfSpace(): Boolean =
    (this is ErrnoException && errno == OsConstants.ENOSPC) || message?.contains("ENOSPC") == true ||
        message?.contains("No space left", ignoreCase = true) == true
