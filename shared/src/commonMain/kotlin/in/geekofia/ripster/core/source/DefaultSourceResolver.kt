package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.platform.HttpClient
import kotlinx.coroutines.CancellationException

/**
 * The app's resolver: turns a pasted URL into a downloadable source, trying in order
 *
 * 1. a direct media file (MP4, MKV, WebM), identified by its first bytes ([DirectFileResolver]);
 * 2. an HLS manifest ([hls]);
 * 3. a web page that *declares* media in standard places ([PageMediaExtractor]): its HLS links
 *    first (they offer quality choices), then direct files.
 *
 * A source found on a page keeps the page as its input URL, so a retry re-reads the page and
 * picks up a fresh link instead of reusing one that may have expired. Every candidate gets the
 * same checks as a pasted link (https, redirects, live, DRM), and nothing on the page is run.
 */
class DefaultSourceResolver(
    private val http: HttpClient,
    private val hls: MediaSourceResolver,
    private val files: DirectFileResolver = DirectFileResolver(http),
) : MediaSourceResolver {

    override suspend fun resolve(input: String): SourceResolution {
        when (val probe = files.probe(input)) {
            is DirectFileResolver.Probe.File -> return SourceResolution.Supported(probe.source)
            is DirectFileResolver.Probe.Failed -> return SourceResolution.Rejected(probe.error)
            DirectFileResolver.Probe.NotAFile -> Unit
        }
        val direct = hls.resolve(input)
        if (direct !is SourceResolution.Rejected || direct.error != MediaError.WebPage) return direct
        val pageUrl = (SourceUrl.check(input) as? UrlCheck.Valid)?.url ?: return direct
        return resolvePage(pageUrl)
    }

    private suspend fun resolvePage(pageUrl: String): SourceResolution {
        val page = try {
            val response = http.get(pageUrl, MAX_PAGE_BYTES)
            if (response.statusCode !in 200..299) {
                return SourceResolution.Rejected(MediaError.HttpStatus(response.statusCode, SourceUrl.looksSigned(pageUrl)))
            }
            PageMediaExtractor.extract(response.body.decodeToString(), response.finalUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            return SourceResolution.Rejected(e.error)
        }

        val candidates = (page.media.filter { it.kind == DeclaredMediaKind.Hls } + page.media.filter { it.kind == DeclaredMediaKind.File })
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) {
            return SourceResolution.Rejected(if (page.media.isEmpty()) MediaError.NoMediaOnPage else MediaError.UnsupportedPageMedia)
        }

        var firstRejection: MediaError? = null
        for (candidate in candidates) {
            val result = when (candidate.kind) {
                DeclaredMediaKind.File -> when (val probe = files.probe(candidate.url)) {
                    is DirectFileResolver.Probe.File -> SourceResolution.Supported(probe.source)
                    is DirectFileResolver.Probe.Failed -> SourceResolution.Rejected(probe.error)
                    // Named like a video, but the bytes say otherwise.
                    DirectFileResolver.Probe.NotAFile -> SourceResolution.Rejected(MediaError.UnsupportedPageMedia)
                }
                else -> hls.resolve(candidate.url)
            }
            when (result) {
                is SourceResolution.Supported -> return SourceResolution.Supported(
                    result.source.copy(inputUrl = pageUrl, foundOnPage = PageOrigin(page.title, candidate.declaration)),
                )
                is SourceResolution.Rejected -> if (firstRejection == null) firstRejection = result.error
            }
        }
        // E.g. the declared stream is live, DRM-protected or expired: report what was wrong with it.
        return SourceResolution.Rejected(firstRejection ?: MediaError.NoMediaOnPage)
    }

    internal companion object {
        /** Enough for real pages; the tags that matter are near the top. */
        const val MAX_PAGE_BYTES = 2 * 1024 * 1024
        const val MAX_CANDIDATES = 3
    }
}
