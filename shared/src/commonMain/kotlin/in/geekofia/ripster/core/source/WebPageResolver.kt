package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.platform.HttpClient
import kotlinx.coroutines.CancellationException

/**
 * Accepts a web page URL as well as a direct manifest. Direct links go straight to [hls]. When
 * that reports a web page, the page is read once and the HLS links it *declares* in standard
 * places ([PageMediaExtractor]) are inspected in order; the first one that passes is the source.
 *
 * The result keeps the page as its input URL, so a retry re-reads the page and picks up a fresh
 * link instead of reusing one that may have expired. Every candidate goes through the same checks
 * as a pasted manifest (https, redirects, live, DRM), and nothing on the page is executed.
 */
class WebPageResolver(
    private val http: HttpClient,
    private val hls: MediaSourceResolver,
) : MediaSourceResolver {

    override suspend fun resolve(input: String): SourceResolution {
        val direct = hls.resolve(input)
        if (direct !is SourceResolution.Rejected || direct.error != MediaError.WebPage) return direct

        val pageUrl = (SourceUrl.check(input) as? UrlCheck.Valid)?.url ?: return direct
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

        val candidates = page.media.filter { it.kind == DeclaredMediaKind.Hls }.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) {
            return SourceResolution.Rejected(
                if (page.media.isEmpty()) MediaError.NoMediaOnPage
                else MediaError.PageMediaNotHls(hasMp4 = page.media.any { it.kind == DeclaredMediaKind.Mp4 }),
            )
        }

        var firstRejection: MediaError? = null
        for (candidate in candidates) {
            when (val result = hls.resolve(candidate.url)) {
                is SourceResolution.Supported -> return SourceResolution.Supported(
                    result.source.copy(
                        inputUrl = pageUrl,
                        foundOnPage = PageOrigin(page.title, candidate.declaration),
                    ),
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
