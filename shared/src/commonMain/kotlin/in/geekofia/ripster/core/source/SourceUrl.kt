package `in`.geekofia.ripster.core.source

/** Why a pasted URL can't be used, before anything is fetched. */
enum class UrlProblem {
    Empty,
    Malformed,

    /** Not `http(s)`, e.g. `file:`, `content:` or `javascript:`. */
    UnsupportedScheme,

    /** Plain `http`: Android blocks cleartext traffic, and redirects must not downgrade. */
    Insecure,

    /** `https://user:password@host/…`: credentials are never stored or sent from a pasted URL. */
    EmbeddedCredentials,
}

sealed interface UrlCheck {
    data class Valid(val url: String, val host: String) : UrlCheck
    data class Invalid(val problem: UrlProblem) : UrlCheck
}

/**
 * Validation and RFC 3986 helpers for remote URLs, without platform URL classes, so the same
 * rules apply to pasted input, redirects and playlist entries on every target.
 */
object SourceUrl {
    private val schemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** Query keys that mark a time-limited or signed URL (CDN tokens, S3/CloudFront signatures). */
    private val signedQueryKeys = setOf(
        "expires", "exp", "expiry", "token", "sig", "signature", "policy", "key-pair-id",
        "x-amz-signature", "x-amz-expires", "x-goog-signature", "hdnts", "hdnea", "st", "e",
    )

    fun check(input: String): UrlCheck {
        val url = input.trim()
        if (url.isEmpty()) return UrlCheck.Invalid(UrlProblem.Empty)
        if (url.any { it.isWhitespace() || it.code < 0x20 }) return UrlCheck.Invalid(UrlProblem.Malformed)
        val scheme = schemeRegex.find(url)?.groupValues?.get(1)?.lowercase()
            ?: return UrlCheck.Invalid(UrlProblem.Malformed)
        when (scheme) {
            "https" -> Unit
            "http" -> return UrlCheck.Invalid(UrlProblem.Insecure)
            else -> return UrlCheck.Invalid(UrlProblem.UnsupportedScheme)
        }
        val parts = split(url) ?: return UrlCheck.Invalid(UrlProblem.Malformed)
        val authority = parts.authority ?: return UrlCheck.Invalid(UrlProblem.Malformed)
        if ('@' in authority) return UrlCheck.Invalid(UrlProblem.EmbeddedCredentials)
        val host = authority.substringBeforeLast(':').takeIf { !authority.startsWith('[') } ?: authority
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it in ".-[]:" }) {
            return UrlCheck.Invalid(UrlProblem.Malformed)
        }
        return UrlCheck.Valid(url, host.lowercase())
    }

    /** True when the query looks like a signature or expiry, so a 403 most likely means "expired". */
    fun looksSigned(url: String): Boolean {
        val query = split(url)?.query ?: return false
        return query.split('&').any { pair ->
            pair.substringBefore('=').lowercase() in signedQueryKeys
        }
    }

    /**
     * A short form for display: host and path, never the query or fragment (which can hold tokens).
     * Long paths keep their last segment, e.g. `example.com/…/master.m3u8`.
     */
    fun displayForm(url: String): String {
        val parts = split(url) ?: return "(invalid URL)"
        val host = parts.authority?.substringAfterLast('@').orEmpty()
        val path = parts.path
        val shortPath = if (path.length <= 40) path else "/…/" + path.substringAfterLast('/')
        return host + shortPath
    }

    /** Non-empty path segments, e.g. `[videos, clip, master.m3u8]`. */
    fun pathSegments(url: String): List<String> =
        split(url)?.path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()

    /** Resolves [reference] (as found in a playlist) against [base], per RFC 3986 section 5.2. */
    fun resolve(base: String, reference: String): String {
        val ref = split(reference) ?: return reference
        if (ref.scheme != null) return reference
        val b = split(base) ?: return reference
        val (authority, path, query) = when {
            ref.authority != null -> Triple(ref.authority, removeDotSegments(ref.path), ref.query)
            ref.path.isEmpty() -> Triple(b.authority, b.path, ref.query ?: b.query)
            ref.path.startsWith('/') -> Triple(b.authority, removeDotSegments(ref.path), ref.query)
            else -> {
                val merged = if (b.authority != null && b.path.isEmpty()) {
                    "/" + ref.path
                } else {
                    b.path.substringBeforeLast('/', "") + "/" + ref.path
                }
                Triple(b.authority, removeDotSegments(merged), ref.query)
            }
        }
        return buildString {
            append(b.scheme).append(':')
            if (authority != null) append("//").append(authority)
            append(path)
            if (query != null) append('?').append(query)
            if (ref.fragment != null) append('#').append(ref.fragment)
        }
    }

    private data class Parts(
        val scheme: String?,
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    )

    /** RFC 3986 appendix B. */
    private val uriRegex = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$")

    private fun split(url: String): Parts? {
        val m = uriRegex.matchEntire(url) ?: return null
        val g = m.groups
        return Parts(
            scheme = g[2]?.value,
            authority = g[4]?.value,
            path = g[5]?.value.orEmpty(),
            query = g[7]?.value,
            fragment = g[9]?.value,
        )
    }

    private fun removeDotSegments(path: String): String {
        val output = ArrayList<String>()
        val segments = path.split('/')
        segments.forEachIndexed { index, segment ->
            when (segment) {
                "." -> if (index == segments.lastIndex) output.add("")
                ".." -> {
                    if (output.size > 1) output.removeAt(output.lastIndex)
                    if (index == segments.lastIndex) output.add("")
                }
                else -> output.add(segment)
            }
        }
        return output.joinToString("/").let { if (path.startsWith('/') && !it.startsWith('/')) "/$it" else it }
    }
}
