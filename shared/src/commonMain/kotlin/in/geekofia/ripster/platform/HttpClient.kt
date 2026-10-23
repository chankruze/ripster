package `in`.geekofia.ripster.platform

/** A completed HTTP response. [finalUrl] is the address after any redirects. */
class HttpResponse(
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
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
}
