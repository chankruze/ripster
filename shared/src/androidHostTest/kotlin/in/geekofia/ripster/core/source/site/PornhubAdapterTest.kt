package `in`.geekofia.ripster.core.source.site

import `in`.geekofia.ripster.core.download.DownloadCoordinator
import `in`.geekofia.ripster.core.download.DownloadRepository
import `in`.geekofia.ripster.core.download.DownloadState
import `in`.geekofia.ripster.core.download.FakeEngine
import `in`.geekofia.ripster.core.download.FakeExporter
import `in`.geekofia.ripster.core.download.eventually
import `in`.geekofia.ripster.core.download.testJob
import `in`.geekofia.ripster.core.download.DownloadJobCodec
import `in`.geekofia.ripster.core.source.DefaultSourceResolver
import `in`.geekofia.ripster.core.source.ErrorCategory
import `in`.geekofia.ripster.core.source.HlsSourceResolver
import `in`.geekofia.ripster.core.source.ManifestKind
import `in`.geekofia.ripster.core.source.MediaDeclaration
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.core.source.NetworkProblem
import `in`.geekofia.ripster.core.source.SiteOutcome
import `in`.geekofia.ripster.core.source.SourceResolution
import `in`.geekofia.ripster.platform.HttpClient
import `in`.geekofia.ripster.platform.HttpResponse
import `in`.geekofia.ripster.platform.InMemoryTextStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The adapter through the app's real resolver, with a fake network. Pages are small synthetic
 * fixtures written for these tests: only the structure (meta tags, JSON-LD) mirrors real pages.
 */
class PornhubAdapterTest {
    private class FakeHttp : HttpClient {
        val responses = mutableMapOf<String, () -> HttpResponse>()
        val requested = mutableListOf<String>()

        fun page(url: String, html: String, status: Int = 200, finalUrl: String = url, type: String = "text/html; charset=UTF-8") {
            responses[url] = { HttpResponse(finalUrl, status, type, html.encodeToByteArray()) }
        }

        fun manifest(url: String, body: String) {
            responses[url] = { HttpResponse(url, 200, "application/vnd.apple.mpegurl", body.encodeToByteArray()) }
        }

        override suspend fun get(url: String, maxBytes: Int): HttpResponse {
            requested += url
            return responses[url]?.invoke() ?: HttpResponse(url, 404, "text/plain", ByteArray(0))
        }

        override suspend fun probe(url: String, bytes: Int): HttpResponse {
            val full = get(url, bytes)
            return HttpResponse(full.finalUrl, full.statusCode, full.contentType, full.body.take(bytes).toByteArray(), full.body.size.toLong())
        }
    }

    private val http = FakeHttp()
    private val resolver = DefaultSourceResolver(http, HlsSourceResolver(http), adapters = listOf(PornhubAdapter(http)))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private val watchUrl = "https://www.pornhub.org/view_video.php?viewkey=6892418cc3800"
    private val vod = "#EXTM3U\n#EXTINF:10,\na.ts\n#EXT-X-ENDLIST\n"

    /** A video page with public metadata and no openly declared media link. */
    private val metadataOnlyPage = """
        <html><head>
        <meta property="og:type" content="video.other">
        <meta property="og:title" content="Synthetic Fixture Title">
        <meta property="og:image" content="https://cdn.example/thumb.jpg">
        <meta property="og:video:duration" content="615">
        <link rel="canonical" href="https://www.pornhub.org/view_video.php?viewkey=6892418cc3800">
        <title>Synthetic Fixture Title - Site</title>
        </head><body><div id="player"></div><script>var player = { config: "opaque" };</script></body></html>
    """.trimIndent()

    private fun resolve(url: String = watchUrl) = runBlocking { resolver.resolve(url) }

    private fun rejection(url: String = watchUrl) = assertIs<SourceResolution.Rejected>(resolve(url))

    private fun siteError(url: String = watchUrl) = assertIs<MediaError.Site>(rejection(url).error)

    @Test
    fun theSuppliedUrlIsMetadataOnlyWithItsPublicMetadata() {
        http.page(watchUrl, metadataOnlyPage)
        val result = rejection()
        val error = assertIs<MediaError.Site>(result.error)
        assertEquals(SiteOutcome.MetadataOnly, error.outcome)
        assertEquals("Pornhub", error.site)
        assertEquals(ErrorCategory.Unsupported, error.category)
        assertEquals(false, error.isRetryable)
        val page = result.page!!
        assertEquals("Synthetic Fixture Title", page.title)
        assertEquals("https://cdn.example/thumb.jpg", page.thumbnailUrl)
        assertEquals(615.0, page.durationSeconds)
        assertEquals(watchUrl, page.canonicalUrl)
        // Only the page itself was fetched: no manifest URL was guessed or fabricated.
        assertEquals(listOf(watchUrl), http.requested)
    }

    @Test
    fun anOpenlyDeclaredAndValidatedSourceIsResolved() {
        http.page(
            watchUrl,
            metadataOnlyPage.replace("</head>", "<meta property=\"og:video:secure_url\" content=\"https://cdn.example/v/master.m3u8\"></head>"),
        )
        http.manifest("https://cdn.example/v/master.m3u8", vod)
        val source = assertIs<SourceResolution.Supported>(resolve()).source
        assertEquals(ManifestKind.Media, source.kind)
        assertEquals(watchUrl, source.inputUrl, "a retry re-reads the page")
        assertEquals("https://cdn.example/v/master.m3u8", source.manifestUrl)
        assertEquals("Synthetic Fixture Title", source.foundOnPage?.title)
        assertEquals(MediaDeclaration.OpenGraph, source.foundOnPage?.declaration)
        assertEquals("https://cdn.example/thumb.jpg", source.thumbnailUrl)
    }

    @Test
    fun aDeclaredLinkThatFailsValidationIsNotResolved() {
        http.page(watchUrl, metadataOnlyPage.replace("</head>", "<meta property=\"og:video\" content=\"https://cdn.example/broken.m3u8\"></head>"))
        // No response registered for the manifest: the fake answers 404.
        val result = rejection()
        assertEquals(SiteOutcome.MetadataOnly, assertIs<MediaError.Site>(result.error).outcome)
        assertEquals("Synthetic Fixture Title", result.page?.title)
    }

    @Test
    fun aMissingPageIsUnavailable() {
        http.page(watchUrl, "", status = 404)
        assertEquals(SiteOutcome.Unavailable, siteError().outcome)
    }

    @Test
    fun aPageThatNoLongerDescribesAVideoIsUnavailable() {
        http.page(watchUrl, "<html><head><meta property=\"og:type\" content=\"website\"><title>Not found</title></head></html>")
        assertEquals(SiteOutcome.Unavailable, siteError().outcome)
    }

    @Test
    fun refusedAccessIsRestricted() {
        http.page(watchUrl, "", status = 403)
        val error = siteError()
        assertEquals(SiteOutcome.Restricted, error.outcome)
        assertEquals(ErrorCategory.Unauthorized, error.category)
    }

    @Test
    fun aRedirectAwayFromTheVideoIsRestricted() {
        http.page(watchUrl, "<html>sign in</html>", finalUrl = "https://www.pornhub.org/login")
        assertEquals(SiteOutcome.Restricted, siteError().outcome)
    }

    @Test
    fun aRedirectToAnotherSiteDomainForTheSameVideoIsFine() {
        http.page(watchUrl, metadataOnlyPage, finalUrl = "https://www.pornhub.com/view_video.php?viewkey=6892418cc3800")
        assertEquals(SiteOutcome.MetadataOnly, siteError().outcome)
    }

    @Test
    fun anAgeGateServedAtTheWatchUrlIsRestrictedNotUnavailable() {
        // Shaped like a front page behind an age-verification overlay: site-name title, a canonical
        // link to the front page, and no video metadata.
        http.page(
            watchUrl,
            """
            <html><head><title>Site</title><link rel="canonical" href="https://www.pornhub.com/" /></head>
            <body><div id="ageVerificationOverlay"><button>I am 18 or older</button></div></body></html>
            """.trimIndent(),
        )
        val error = siteError()
        assertEquals(SiteOutcome.Restricted, error.outcome)
        assertEquals(ErrorCategory.Unauthorized, error.category)
    }

    @Test
    fun aCanonicalUrlOffTheSiteIsIgnored() {
        http.page(watchUrl, metadataOnlyPage.replace("https://www.pornhub.org/view_video.php?viewkey=6892418cc3800", "https://lookalike.example/x"))
        assertEquals(watchUrl, rejection().page?.canonicalUrl)
    }

    @Test
    fun networkFailuresAreRecoverableErrors() {
        http.responses[watchUrl] = { throw MediaException(MediaError.Network(NetworkProblem.Timeout)) }
        val error = rejection().error
        assertEquals(MediaError.Network(NetworkProblem.Timeout), error)
        assertEquals(true, error.isRetryable)

        http.page(watchUrl, "", status = 503)
        assertEquals(MediaError.HttpStatus(503), rejection().error)
    }

    @Test
    fun aNonHtmlResponseIsUnsupported() {
        http.page(watchUrl, "{}", type = "application/json")
        assertEquals(SiteOutcome.Unsupported, siteError().outcome)
    }

    @Test
    fun otherSitePagesAreUnsupportedWithoutARequest() {
        assertEquals(SiteOutcome.Unsupported, siteError("https://www.pornhub.com/video/search?search=x").outcome)
        assertEquals(emptyList(), http.requested)
    }

    @Test
    fun lookalikeDomainsAreLeftToTheGenericResolvers() {
        http.page("https://pornhub.com.evil.example/view_video.php?viewkey=abc123def", "<html><title>x</title></html>")
        // The generic page path reads it like any page; the adapter never claims it.
        assertEquals(MediaError.NoMediaOnPage, rejection("https://pornhub.com.evil.example/view_video.php?viewkey=abc123def").error)
    }

    @Test
    fun onlyValidatedSourcesReachTheDownloader() = runBlocking<Unit> {
        // A queued job for the watch page (e.g. from before, or retried) that now resolves MetadataOnly.
        http.page(watchUrl, metadataOnlyPage)
        val engine = FakeEngine()
        val store = InMemoryTextStore(DownloadJobCodec.encode(listOf(testJob(id = "site", sourceUrl = watchUrl, variantUrl = null))))
        val downloads = DownloadCoordinator(
            repository = DownloadRepository(store, scope, Dispatchers.IO),
            resolver = resolver,
            engine = engine,
            exporter = FakeExporter(),
            storage = { null },
            outputFolder = { null },
            scope = scope,
            nowMillis = { 1L },
            newId = { "unused" },
        ).also { it.start() }
        val failed = eventually(value = { downloads.jobs.value.firstOrNull() }) { it?.state == DownloadState.Failed }!!
        assertEquals(SiteOutcome.MetadataOnly, assertIs<MediaError.Site>(failed.error).outcome)
        delay(50)
        assertEquals(emptyList(), engine.enqueued, "nothing was handed to the download engine")
    }
}
