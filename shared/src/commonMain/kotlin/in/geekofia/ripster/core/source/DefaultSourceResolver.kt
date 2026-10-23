package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.core.source.site.SiteAdapter
import `in`.geekofia.ripster.core.source.site.SiteMatch
import `in`.geekofia.ripster.core.source.site.SiteResolution
import `in`.geekofia.ripster.platform.HttpClient
import kotlinx.coroutines.CancellationException

/**
 * The app's resolver: turns a pasted URL into a downloadable source, trying in order
 *
 * 0. a URL on a supported site goes to that site's adapter only ([adapters]);
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
    /** Consulted first: a URL on an adapter's site is handled only by that adapter. */
    private val adapters: List<SiteAdapter> = emptyList(),
) : MediaSourceResolver {

    override suspend fun resolve(input: String): SourceResolution {
        for (adapter in adapters) {
            when (val match = adapter.match(input.trim())) {
                null -> continue
                is SiteMatch.OtherPage -> return SourceResolution.Rejected(MediaError.Site(match.site, SiteOutcome.Unsupported, match.reason))
                is SiteMatch.Page -> return adapter.resolve(match, ::validateDeclared).toSourceResolution(adapter.site)
            }
        }
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
        val (page, thumbnailUrl) = try {
            val response = http.get(pageUrl, MAX_PAGE_BYTES)
            if (response.statusCode !in 200..299) {
                return SourceResolution.Rejected(MediaError.HttpStatus(response.statusCode, SourceUrl.looksSigned(pageUrl)))
            }
            val html = response.body.decodeToString()
            PageMediaExtractor.extract(html, response.finalUrl) to PageMediaExtractor.metadata(html, response.finalUrl).thumbnailUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            return SourceResolution.Rejected(e.error)
        }
        return when (val result = validateDeclared(pageUrl, page.title, page.media)) {
            is SourceResolution.Supported -> SourceResolution.Supported(result.source.copy(thumbnailUrl = thumbnailUrl))
            is SourceResolution.Rejected -> result
        }
    }

    /**
     * The one place that decides whether declared media is downloadable: HLS links first (they
     * offer quality choices), then direct files, each through the same checks as a pasted link.
     * Used for generic pages and as the [DeclaredMediaValidator] given to site adapters.
     */
    private suspend fun validateDeclared(pageUrl: String, pageTitle: String?, media: List<DeclaredMedia>): SourceResolution {
        val page = PageMedia(pageTitle, media)
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

    /** Only [SiteResolution.Resolved] becomes a downloadable source; everything else is a typed rejection. */
    private fun SiteResolution.toSourceResolution(site: String): SourceResolution = when (this) {
        is SiteResolution.Resolved -> SourceResolution.Supported(
            source.copy(
                foundOnPage = source.foundOnPage?.copy(title = metadata.title ?: source.foundOnPage.title),
                thumbnailUrl = metadata.thumbnailUrl,
            ),
        )
        is SiteResolution.MetadataOnly -> SourceResolution.Rejected(MediaError.Site(site, SiteOutcome.MetadataOnly, reason), metadata)
        is SiteResolution.Unsupported -> SourceResolution.Rejected(MediaError.Site(site, SiteOutcome.Unsupported, reason), metadata)
        is SiteResolution.Restricted -> SourceResolution.Rejected(MediaError.Site(site, SiteOutcome.Restricted, reason), metadata)
        is SiteResolution.Unavailable -> SourceResolution.Rejected(MediaError.Site(site, SiteOutcome.Unavailable, reason))
        is SiteResolution.NetworkError -> SourceResolution.Rejected(error)
    }

    internal companion object {
        /** Enough for real pages; the tags that matter are near the top. */
        const val MAX_PAGE_BYTES = 2 * 1024 * 1024
        const val MAX_CANDIDATES = 3
    }
}
