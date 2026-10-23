package `in`.geekofia.ripster.core.source

/** A short title and an explanation with a next step, for each [MediaError]. */
data class ErrorMessage(val title: String, val detail: String)

fun MediaError.message(): ErrorMessage = when (this) {
    is MediaError.InvalidUrl -> when (problem) {
        UrlProblem.Empty -> ErrorMessage("Enter a URL", "Paste a direct link to an HLS manifest (.m3u8).")
        UrlProblem.Malformed -> ErrorMessage("Not a valid URL", "Check the link: it should look like https://host/path/playlist.m3u8.")
        UrlProblem.UnsupportedScheme -> ErrorMessage("Unsupported link", "Only https:// manifest links are supported.")
        UrlProblem.Insecure -> ErrorMessage("Insecure link", "Plain http:// isn't supported. Use the https:// address of the manifest.")
        UrlProblem.EmbeddedCredentials -> ErrorMessage("Link contains credentials", "URLs with a user name or password aren't supported.")
    }
    MediaError.WebPage -> ErrorMessage(
        "This is a web page",
        "Ripster needs a direct HLS manifest link (.m3u8), not the page that plays the video.",
    )
    MediaError.NoMediaOnPage -> ErrorMessage(
        "No downloadable media on this page",
        "The page doesn't publish a media link in a standard way (video element, Open Graph or schema.org). " +
            "Ripster doesn't run page scripts or work around a site's player. Paste a direct media link instead.",
    )
    MediaError.UnsupportedPageMedia -> ErrorMessage(
        "Unsupported media format",
        "The page declares media, but not in a format Ripster can download (HLS, MP4, MKV or WebM).",
    )
    is MediaError.NotHls -> ErrorMessage(
        "Not an HLS manifest",
        "The server returned ${contentType?.let { "\"$it\"" } ?: "something"} instead of an M3U8 playlist.",
    )
    MediaError.LiveStream -> ErrorMessage("Live stream", "Live HLS streams can't be downloaded. Only complete (VOD) playlists are supported.")
    MediaError.DrmProtected -> ErrorMessage("DRM-protected", "This stream uses DRM. Ripster doesn't download protected media.")
    MediaError.SampleAesEncrypted -> ErrorMessage("Unsupported encryption", "SAMPLE-AES encrypted streams can't be exported.")
    is MediaError.HttpStatus -> when (category) {
        ErrorCategory.Expired -> ErrorMessage("Link expired", "The server refused the link (HTTP $code). Get a fresh link and try again.")
        ErrorCategory.Unauthorized -> ErrorMessage("Access denied", "The server refused access (HTTP $code). Ripster can only download media you're allowed to access.")
        else -> when (code) {
            404 -> ErrorMessage("Not found", "The server has nothing at this address (HTTP 404). The stream may have been removed.")
            429 -> ErrorMessage("Too many requests", "The server is rate limiting (HTTP 429). Wait a while, then retry.")
            in 500..599 -> ErrorMessage("Server error", "The server failed (HTTP $code). It may be temporary, so retry later.")
            else -> ErrorMessage("Request failed", "The server answered HTTP $code.")
        }
    }
    is MediaError.Network -> when (problem) {
        NetworkProblem.Offline -> ErrorMessage("No connection", "Couldn't reach the server. Check your connection and retry.")
        NetworkProblem.Timeout -> ErrorMessage("Timed out", "The server took too long to respond. Retry in a moment.")
        NetworkProblem.Tls -> ErrorMessage("Secure connection failed", "The server's certificate couldn't be verified.")
        NetworkProblem.TooManyRedirects -> ErrorMessage("Too many redirects", "The link redirects too many times.")
        NetworkProblem.InsecureRedirect -> ErrorMessage("Unsafe redirect", "The link redirects to a non-https address, which Ripster doesn't follow.")
        NetworkProblem.ResponseTooLarge -> ErrorMessage("Response too large", "This is too large to be a manifest.")
        NetworkProblem.Other -> ErrorMessage("Network error", "The download was interrupted. Retry when your connection is stable.")
    }
    is MediaError.Parse -> ErrorMessage("Invalid manifest", "The playlist couldn't be read: $detail.")
    is MediaError.InsufficientStorage -> ErrorMessage(
        "Not enough storage",
        if (requiredBytes != null && availableBytes != null) {
            "Needs about ${formatBytes(requiredBytes)}, but only ${formatBytes(availableBytes)} is free."
        } else {
            "The device ran out of space. Free up storage, then retry."
        },
    )
    is MediaError.OutputUnavailable -> ErrorMessage("Can't save to the folder", "$detail Choose another output folder, then retry.")
    is MediaError.ExportUnsupported -> ErrorMessage("Can't export to MP4", detail)
    is MediaError.ExportFailed -> ErrorMessage("Export failed", detail)
    is MediaError.Unknown -> ErrorMessage("Something went wrong", detail)
}

/** `1.5 GB`, `340 MB`, `12 KB`: binary units, one decimal above MB. */
fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "${oneDecimal(bytes / gb)} GB"
        bytes >= mb -> "${oneDecimal(bytes / mb)} MB"
        bytes >= kb -> "${(bytes / kb).toLong()} KB"
        else -> "$bytes B"
    }
}

internal fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
