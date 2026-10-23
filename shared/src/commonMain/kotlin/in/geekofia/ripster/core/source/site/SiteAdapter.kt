package `in`.geekofia.ripster.core.source.site

import `in`.geekofia.ripster.core.source.DeclaredMedia
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.PageMetadata
import `in`.geekofia.ripster.core.source.ResolvedSource
import `in`.geekofia.ripster.core.source.SourceResolution

/** How a site adapter classifies a URL it was asked about. */
sealed interface SiteMatch {
    /** A supported page on the site, with its validated identifier and a normalised URL. */
    data class Page(val site: String, val id: String, val url: String) : SiteMatch

    /** The site's own domain, but not a page format the adapter supports (search, profile, …). */
    data class OtherPage(val site: String, val reason: String) : SiteMatch
}

/**
 * What an adapter found. Only [Resolved] carries a source; every other outcome explains why
 * there is nothing to download, so it can never start one.
 */
sealed interface SiteResolution {
    /** A source the page openly provides, validated by Ripster's own HLS or direct-file checks. */
    data class Resolved(val metadata: PageMetadata, val source: ResolvedSource) : SiteResolution

    /** The page and its metadata are available, but it doesn't openly provide a downloadable source. */
    data class MetadataOnly(val metadata: PageMetadata, val reason: String) : SiteResolution

    data class Unsupported(val reason: String, val metadata: PageMetadata? = null) : SiteResolution

    /** Not reachable through supported means (sign-in, age verification, access denied). Never bypassed. */
    data class Restricted(val reason: String, val metadata: PageMetadata? = null) : SiteResolution

    /** The page or video no longer exists. */
    data class Unavailable(val reason: String) : SiteResolution

    /** A recoverable network failure (offline, timeout, server error): retrying can help. */
    data class NetworkError(val reason: String, val error: MediaError) : SiteResolution
}

/**
 * Validates media links a page openly declares, with the same checks as a pasted link. Returns
 * [SourceResolution.Supported] only for a source that actually passed.
 */
fun interface DeclaredMediaValidator {
    suspend fun validate(pageUrl: String, pageTitle: String?, media: List<DeclaredMedia>): SourceResolution
}

/**
 * Site-specific page handling, isolated per site. An adapter recognises its own URLs strictly
 * (exact hosts, validated identifiers) and reads only what the page publicly declares; it never
 * runs page scripts, reads a player's private configuration or works around access controls.
 */
interface SiteAdapter {
    val site: String

    /** Null when the URL isn't on this site at all (the generic resolvers then handle it). */
    fun match(url: String): SiteMatch?

    suspend fun resolve(page: SiteMatch.Page, validator: DeclaredMediaValidator): SiteResolution
}
