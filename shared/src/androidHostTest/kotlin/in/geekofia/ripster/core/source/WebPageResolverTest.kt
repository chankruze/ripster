package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.platform.HttpClient
import `in`.geekofia.ripster.platform.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class WebPageResolverTest {
    private class FakeHttp : HttpClient {
        val responses = mutableMapOf<String, HttpResponse>()
        val requested = mutableListOf<String>()

        fun respond(url: String, body: String, type: String, status: Int = 200) {
            responses[url] = HttpResponse(url, status, type, body.encodeToByteArray())
        }

        override suspend fun get(url: String, maxBytes: Int): HttpResponse {
            requested += url
            return responses[url] ?: HttpResponse(url, 404, "text/plain", ByteArray(0))
        }
    }

    private val http = FakeHttp()
    private val resolver = WebPageResolver(http, HlsSourceResolver(http))

    private val pageUrl = "https://videos.example/watch/42"
    private val vod = "#EXTM3U\n#EXTINF:10,\na.ts\n#EXT-X-ENDLIST\n"
    private val html = "text/html; charset=utf-8"
    private val m3u8 = "application/vnd.apple.mpegurl"

    private fun resolve(url: String = pageUrl) = runBlocking { resolver.resolve(url) }

    @Test
    fun directManifestsAreUnchanged() {
        http.respond("https://cdn.example/a.m3u8", vod, m3u8)
        val source = assertIs<SourceResolution.Supported>(resolve("https://cdn.example/a.m3u8")).source
        assertEquals(null, source.foundOnPage)
        assertEquals(listOf("https://cdn.example/a.m3u8"), http.requested)
    }

    @Test
    fun aPageThatDeclaresAManifestResolvesToIt() {
        http.respond(pageUrl, "<meta property=\"og:title\" content=\"Clip 42\"><video src=\"https://cdn.example/42.m3u8\"></video>", html)
        http.respond("https://cdn.example/42.m3u8", vod, m3u8)
        val source = assertIs<SourceResolution.Supported>(resolve()).source
        // The page stays the input, so a retry re-reads it for a fresh link.
        assertEquals(pageUrl, source.inputUrl)
        assertEquals("https://cdn.example/42.m3u8", source.manifestUrl)
        assertEquals(PageOrigin("Clip 42", MediaDeclaration.VideoTag), source.foundOnPage)
    }

    @Test
    fun theFirstWorkingCandidateWins() {
        http.respond(
            pageUrl,
            "<video src=\"https://cdn.example/live.m3u8\"></video><meta property=\"og:video\" content=\"https://cdn.example/vod.m3u8\">",
            html,
        )
        http.respond("https://cdn.example/live.m3u8", "#EXTM3U\n#EXTINF:6,\na.ts\n", m3u8)
        http.respond("https://cdn.example/vod.m3u8", vod, m3u8)
        val source = assertIs<SourceResolution.Supported>(resolve()).source
        assertEquals("https://cdn.example/vod.m3u8", source.manifestUrl)
        assertEquals(MediaDeclaration.OpenGraph, source.foundOnPage?.declaration)
    }

    @Test
    fun aPageWithNothingDeclaredSaysSo() {
        http.respond(pageUrl, "<html><script>loadPlayer('x')</script></html>", html)
        val error = assertIs<SourceResolution.Rejected>(resolve()).error
        assertEquals(MediaError.NoMediaOnPage, error)
        assertEquals(ErrorCategory.NeedsUserAction, error.category)
    }

    @Test
    fun aPageWithOnlyMp4IsUnsupportedForNow() {
        http.respond(pageUrl, "<video src=\"https://cdn.example/clip.mp4\"></video>", html)
        assertEquals(MediaError.PageMediaNotHls(hasMp4 = true), assertIs<SourceResolution.Rejected>(resolve()).error)
    }

    @Test
    fun aDeclaredStreamThatFailsReportsWhy() {
        http.respond(pageUrl, "<video src=\"https://cdn.example/drm.m3u8\"></video>", html)
        http.respond(
            "https://cdn.example/drm.m3u8",
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST",
            m3u8,
        )
        assertEquals(MediaError.DrmProtected, assertIs<SourceResolution.Rejected>(resolve()).error)
    }

    @Test
    fun onlyAFewCandidatesAreTried() {
        http.respond(pageUrl, (1..10).joinToString("") { "<video src=\"https://cdn.example/$it.m3u8\"></video>" }, html)
        assertIs<SourceResolution.Rejected>(resolve())
        assertEquals(WebPageResolver.MAX_CANDIDATES, http.requested.count { it.endsWith(".m3u8") })
    }

    @Test
    fun otherRejectionsArePassedThrough() {
        assertEquals(MediaError.InvalidUrl(UrlProblem.Insecure), assertIs<SourceResolution.Rejected>(resolve("http://videos.example/x")).error)
        http.respond("https://cdn.example/a.mp4", "\u0000\u0000", "video/mp4")
        assertEquals(MediaError.NotHls("video/mp4"), assertIs<SourceResolution.Rejected>(resolve("https://cdn.example/a.mp4")).error)
    }
}
