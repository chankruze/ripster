package `in`.geekofia.ripster.core.source

/** How a [MediaError] should be presented and whether trying again can help. */
enum class ErrorCategory {
    /** The input is valid but needs the user to do something (e.g. paste the manifest, not a page). */
    NeedsUserAction,
    Unsupported,

    /** Temporarily or permanently unavailable (404, 5xx, rate limited). */
    Unavailable,

    /** A signed or time-limited URL that no longer works. Get a fresh link. */
    Expired,
    Unauthorized,
    Network,
    Parse,
    Storage,
    Export,
    Unknown,
}

enum class NetworkProblem { Offline, Timeout, Tls, TooManyRedirects, InsecureRedirect, ResponseTooLarge, Other }

/**
 * Every failure the app reports, typed so the UI, retries and persistence agree on what happened.
 * Details are short, non-sensitive strings (no URLs, headers or tokens).
 */
sealed class MediaError {
    abstract val category: ErrorCategory

    data class InvalidUrl(val problem: UrlProblem) : MediaError() {
        override val category get() = ErrorCategory.NeedsUserAction
    }

    /** The URL returned an HTML page: Ripster only accepts direct manifest links. */
    data object WebPage : MediaError() {
        override val category get() = ErrorCategory.NeedsUserAction
    }

    /** A web page that declares no media link in a standard place (see `PageMediaExtractor`). */
    data object NoMediaOnPage : MediaError() {
        override val category get() = ErrorCategory.NeedsUserAction
    }

    /** The page declares media, but only in formats Ripster can't download (not HLS, MP4, MKV or WebM). */
    data object UnsupportedPageMedia : MediaError() {
        override val category get() = ErrorCategory.Unsupported
    }

    data class NotHls(val contentType: String?) : MediaError() {
        override val category get() = ErrorCategory.Unsupported
    }

    data object LiveStream : MediaError() {
        override val category get() = ErrorCategory.Unsupported
    }

    data object DrmProtected : MediaError() {
        override val category get() = ErrorCategory.Unsupported
    }

    data object SampleAesEncrypted : MediaError() {
        override val category get() = ErrorCategory.Unsupported
    }

    /** [signedUrl]: the URL carries an expiry or signature, so a 403 most likely means it expired. */
    data class HttpStatus(val code: Int, val signedUrl: Boolean = false) : MediaError() {
        override val category
            get() = when (code) {
                401 -> ErrorCategory.Unauthorized
                403 -> if (signedUrl) ErrorCategory.Expired else ErrorCategory.Unauthorized
                410 -> ErrorCategory.Expired
                else -> ErrorCategory.Unavailable
            }
    }

    data class Network(val problem: NetworkProblem) : MediaError() {
        override val category get() = ErrorCategory.Network
    }

    data class Parse(val detail: String) : MediaError() {
        override val category get() = ErrorCategory.Parse
    }

    /** Sizes are null when unknown (e.g. the disk filled up mid-download). */
    data class InsufficientStorage(val requiredBytes: Long?, val availableBytes: Long?) : MediaError() {
        override val category get() = ErrorCategory.Storage
    }

    /** The chosen output folder can't be written (removed, or its permission was revoked). */
    data class OutputUnavailable(val detail: String) : MediaError() {
        override val category get() = ErrorCategory.Storage
    }

    /** The stream's codecs or packaging can't be written to MP4 on this device. */
    data class ExportUnsupported(val detail: String) : MediaError() {
        override val category get() = ErrorCategory.Export
    }

    /** The finished download's data is gone or unreadable. Retry downloads it again. */
    data object DownloadedDataMissing : MediaError() {
        override val category get() = ErrorCategory.Storage
    }

    data class ExportFailed(val detail: String) : MediaError() {
        override val category get() = ErrorCategory.Export
    }

    data class Unknown(val detail: String) : MediaError() {
        override val category get() = ErrorCategory.Unknown
    }

    /**
     * Whether offering Retry makes sense. Unsupported input fails the same way every time; an
     * expired link is retryable only because retry re-inspects the URL (it's never retried
     * automatically).
     */
    val isRetryable: Boolean
        get() = when (category) {
            ErrorCategory.NeedsUserAction, ErrorCategory.Unsupported -> false
            else -> this !is ExportUnsupported
        }
}

/** Thrown by platform code to carry a [MediaError] across suspend boundaries. */
class MediaException(val error: MediaError, cause: Throwable? = null) : Exception(error.toString(), cause)
