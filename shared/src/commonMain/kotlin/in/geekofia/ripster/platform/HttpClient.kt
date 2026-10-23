package `in`.geekofia.ripster.platform

/** A completed HTTP response. [finalUrl] is the address after any redirects. */
class HttpResponse(
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
    /** The resource's full size, from `Content-Range` or `Content-Length`, when the server says. */
    val totalBytes: Long? = null,
)

/**
 * Fetches small text resources (manifests) for inspection. Implementations must:
 * - follow at most a few redirects, each re-validated with `SourceUrl.check` (https only);
 * - keep TLS verification on;
 * - stop reading after [maxBytes] and throw a `MediaException` with `ResponseTooLarge`;
 * - map connection failures to `MediaException(MediaError.Network(…))`;
 * - never log URLs, query strings or headers.
 */
interface HttpClient {
    suspend fun get(url: String, maxBytes: Int): HttpResponse

    /**
     * Reads only the first [bytes] of the resource (a `Range` request; if the server ignores it,
     * reading stops after [bytes] anyway), with the same redirect rules as [get]. Never throws
     * `ResponseTooLarge`: it's how a large media file is identified without downloading it.
     */
    suspend fun probe(url: String, bytes: Int): HttpResponse
}
