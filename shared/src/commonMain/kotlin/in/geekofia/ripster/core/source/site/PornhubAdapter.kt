package `in`.geekofia.ripster.core.source.site

import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.core.source.NetworkProblem
import `in`.geekofia.ripster.core.source.PageMediaExtractor
import `in`.geekofia.ripster.core.source.PageMetadata
import `in`.geekofia.ripster.core.source.SourceResolution
import `in`.geekofia.ripster.core.source.SourceUrl
import `in`.geekofia.ripster.core.source.UrlCheck
import `in`.geekofia.ripster.core.source.message
import `in`.geekofia.ripster.platform.HttpClient
import kotlinx.coroutines.CancellationException

/**
 * Pornhub watch pages (`https://<host>/view_video.php?viewkey=<id>`).
 *
 * The page's public metadata (title, thumbnail, duration, canonical URL) is read from its Open
 * Graph tags, schema.org data and HTML. A download is offered only if the page *openly declares*
 * a media link there and that link passes Ripster's normal HLS or direct-file validation. The
 * site's player builds its stream URLs through site-internal, signed mechanisms; Ripster doesn't
 * read the player's configuration or work around the site's download restrictions, so in that
 * case the result is [SiteResolution.MetadataOnly] with an explanation.
 */
class PornhubAdapter(private val http: HttpClient) : SiteAdapter {
    override val site = "Pornhub"

    override fun match(url: String): SiteMatch? {
        val valid = SourceUrl.check(url) as? UrlCheck.Valid ?: return null
        val host = valid.host.removeSuffix(".")
        if (!isSiteHost(host)) return null
        val path = SourceUrl.pathSegments(valid.url)
        if (path != listOf(WATCH_PATH)) {
            return SiteMatch.OtherPage(site, "Only video pages (…/view_video.php?viewkey=…) are supported, not other pages of the site.")
        }
        val viewKeys = queryValues(valid.url, "viewkey")
        val id = viewKeys.singleOrNull()?.takeIf(viewKeyPattern::matches)
            ?: return SiteMatch.OtherPage(site, "The link has no valid video identifier (viewkey).")
        return SiteMatch.Page(site, id, "https://$host/$WATCH_PATH?viewkey=$id")
    }

    override suspend fun resolve(page: SiteMatch.Page, validator: DeclaredMediaValidator): SiteResolution {
        val response = try {
            http.get(page.url, MAX_PAGE_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            return when (val error = e.error) {
                is MediaError.Network -> when (error.problem) {
                    NetworkProblem.InsecureRedirect, NetworkProblem.TooManyRedirects ->
                        SiteResolution.Restricted("The page redirects somewhere Ripster doesn't follow.")
                    NetworkProblem.ResponseTooLarge -> SiteResolution.Unsupported("The page is too large to read.")
                    else -> SiteResolution.NetworkError(error.message().detail, error)
                }
                else -> SiteResolution.Unsupported(error.message().detail)
            }
        }
        when (val status = response.statusCode) {
            in 200..299 -> Unit
            404, 410 -> return SiteResolution.Unavailable("The video page doesn't exist anymore (HTTP $status).")
            401, 403, 451 -> return SiteResolution.Restricted("The site refused access to the page (HTTP $status). Ripster doesn't bypass access controls.")
            429, in 500..599 -> return SiteResolution.NetworkError(
                "The site is temporarily unavailable (HTTP $status). Try again later.",
                MediaError.HttpStatus(status),
            )
            else -> return SiteResolution.Unsupported("The site answered HTTP $status.")
        }
        // Sent elsewhere (sign-in, age verification, a lookalike host…): not something to work around.
        val landed = match(response.finalUrl)
        if (landed !is SiteMatch.Page || landed.id != page.id) {
            return SiteResolution.Restricted(
                "The site redirected away from the video page (for example to sign-in or age verification), which Ripster doesn't bypass.",
            )
        }
        val type = response.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type != "text/html" && type != "application/xhtml+xml") {
            return SiteResolution.Unsupported("The page isn't HTML (\"$type\").")
        }

        val html = response.body.decodeToString()
        val metadata = PageMediaExtractor.metadata(html, response.finalUrl).let { found ->
            // Only a canonical URL on the same site, for the same video, is kept.
            val canonical = found.canonicalUrl?.takeIf { (match(it) as? SiteMatch.Page)?.id == page.id }
            found.copy(canonicalUrl = canonical ?: page.url)
        }
        if (!metadata.isVideoPage) {
            return SiteResolution.Unavailable("The page doesn't describe a video. It may have been removed or made private.")
        }

        val declared = PageMediaExtractor.extract(html, response.finalUrl).media
        if (declared.isEmpty()) {
            return SiteResolution.MetadataOnly(
                metadata,
                "The page doesn't openly provide a downloadable source. Its player loads streams through " +
                    "site-internal mechanisms, which Ripster doesn't use.",
            )
        }
        return when (val result = validator.validate(page.url, metadata.title, declared)) {
            is SourceResolution.Supported -> SiteResolution.Resolved(metadata, result.source)
            is SourceResolution.Rejected -> SiteResolution.MetadataOnly(
                metadata,
                "The page declares a media link, but it isn't downloadable: ${result.error.message().detail}",
            )
        }
    }

    private fun isSiteHost(host: String): Boolean = SITE_DOMAINS.any { domain ->
        host == domain || (host.endsWith(".$domain") && host.removeSuffix(".$domain").let(subdomainPattern::matches))
    }

    /** Raw values of [key] in the query; a value with escapes or odd characters fails [viewKeyPattern] later. */
    private fun queryValues(url: String, key: String): List<String> =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .filter { it.substringBefore('=') == key }
            .map { it.substringAfter('=', "") }

    internal companion object {
        /** Exact registrable domains; anything else (lookalikes, other TLDs, suffix tricks) doesn't match. */
        val SITE_DOMAINS = listOf("pornhub.com", "pornhub.org")
        const val WATCH_PATH = "view_video.php"
        const val MAX_PAGE_BYTES = 2 * 1024 * 1024

        /** `www`, `m`, or a two-letter language/country label: one label only. */
        private val subdomainPattern = Regex("^(?:www|m|[a-z]{2})$")
        private val viewKeyPattern = Regex("^[A-Za-z0-9]{6,40}$")
    }
}
