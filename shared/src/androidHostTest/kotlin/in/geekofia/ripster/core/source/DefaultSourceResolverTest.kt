package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.platform.HttpClient
import `in`.geekofia.ripster.platform.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class DefaultSourceResolverTest {
    private class FakeHttp : HttpClient {
        val responses = mutableMapOf<String, HttpResponse>()
        val requested = mutableListOf<String>()
        val probed = mutableListOf<String>()

        fun respond(url: String, body: ByteArray, type: String, status: Int = 200) {
            responses[url] = HttpResponse(url, status, type, body, totalBytes = body.size.toLong())
        }

        fun respond(url: String, body: String, type: String, status: Int = 200) = respond(url, body.encodeToByteArray(), type, status)

        private fun response(url: String) = responses[url] ?: HttpResponse(url, 404, "text/plain", ByteArray(0))

        override suspend fun get(url: String, maxBytes: Int): HttpResponse {
            requested += url
            return response(url)
        }

        override suspend fun probe(url: String, bytes: Int): HttpResponse {
            probed += url
            val full = response(url)
            return HttpResponse(full.finalUrl, full.statusCode, full.contentType, full.body.take(bytes).toByteArray(), full.totalBytes)
        }
    }

    private val http = FakeHttp()
    private val resolver = DefaultSourceResolver(http, HlsSourceResolver(http))

    private val pageUrl = "https://videos.example/watch/42"
    private val vod = "#EXTM3U\n#EXTINF:10,\na.ts\n#EXT-X-ENDLIST\n"
    private val html = "text/html; charset=utf-8"
    private val m3u8 = "application/vnd.apple.mpegurl"

    /** Just the signature bytes a real file starts with, padded; enough for sniffing. */
    private fun mp4Bytes(size: Int = 1_000) = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".encodeToByteArray() + ByteArray(size)
    private fun mkvBytes(docType: String) =
        byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x82.toByte(), 0x88.toByte()) +
            docType.encodeToByteArray() + ByteArray(500)

    private fun resolve(url: String = pageUrl) = runBlocking { resolver.resolve(url) }

    @Test
    fun aDirectMp4IsRecognisedByItsBytesWithoutDownloadingIt() {
        // A generic content type and no extension: only the bytes tell.
        http.respond("https://cdn.example/download?id=7", mp4Bytes(5_000_000), "application/octet-stream")
        val source = assertIs<SourceResolution.Supported>(resolve("https://cdn.example/download?id=7")).source
        assertEquals(ManifestKind.DirectFile, source.kind)
        assertEquals(DirectFileInfo(FileContainer.Mp4, 5_000_012), source.file)
        assertEquals(null, source.details.durationSeconds)
        assertEquals(emptyList(), http.requested, "probed only, never fetched in full")
    }

    @Test
    fun matroskaAndWebmAreToldApartByDocType() {
        http.respond("https://cdn.example/a.mkv", mkvBytes("matroska"), "video/x-matroska")
        http.respond("https://cdn.example/b.webm", mkvBytes("webm"), "video/webm")
        assertEquals(FileContainer.Matroska, assertIs<SourceResolution.Supported>(resolve("https://cdn.example/a.mkv")).source.file?.container)
        assertEquals(FileContainer.WebM, assertIs<SourceResolution.Supported>(resolve("https://cdn.example/b.webm")).source.file?.container)
    }

    @Test
    fun aFileNamedLikeAVideoThatIsNotOneIsNotAccepted() {
        http.respond("https://cdn.example/fake.mp4", "just text", "video/mp4")
        assertEquals(MediaError.NotHls("video/mp4"), assertIs<SourceResolution.Rejected>(resolve("https://cdn.example/fake.mp4")).error)
    }

    @Test
    fun directManifestsStillWork() {
        http.respond("https://cdn.example/a.m3u8", vod, m3u8)
        val source = assertIs<SourceResolution.Supported>(resolve("https://cdn.example/a.m3u8")).source
        assertEquals(ManifestKind.Media, source.kind)
        assertEquals(null, source.foundOnPage)
    }

    @Test
    fun aFailingProbeIsReportedOnce() {
        http.respond("https://cdn.example/gone", "", "text/plain", status = 404)
        assertEquals(MediaError.HttpStatus(404), assertIs<SourceResolution.Rejected>(resolve("https://cdn.example/gone")).error)
        assertEquals(emptyList(), http.requested)
    }

    @Test
    fun aPageThatDeclaresAManifestResolvesToIt() {
        http.respond(
            pageUrl,
            "<meta property=\"og:title\" content=\"Clip 42\"><meta property=\"og:image\" content=\"https://cdn.example/t.jpg\">" +
                "<video src=\"https://cdn.example/42.m3u8\"></video>",
            html,
        )
        http.respond("https://cdn.example/42.m3u8", vod, m3u8)
        val source = assertIs<SourceResolution.Supported>(resolve()).source
        // The page stays the input, so a retry re-reads it for a fresh link.
        assertEquals(pageUrl, source.inputUrl)
        assertEquals("https://cdn.example/42.m3u8", source.manifestUrl)
        assertEquals(PageOrigin("Clip 42", MediaDeclaration.VideoTag), source.foundOnPage)
        assertEquals("https://cdn.example/t.jpg", source.thumbnailUrl, "the page thumbnail is carried onto the source")
    }

    @Test
    fun aPageThatDeclaresAFileResolvesToIt() {
        http.respond(pageUrl, "<title>Clip</title><video><source src=\"/media/clip.mkv\" type=\"video/x-matroska\"></video>", html)
        http.respond("https://videos.example/media/clip.mkv", mkvBytes("matroska"), "video/x-matroska")
        val source = assertIs<SourceResolution.Supported>(resolve()).source
        assertEquals(ManifestKind.DirectFile, source.kind)
        assertEquals(pageUrl, source.inputUrl)
        assertEquals("https://videos.example/media/clip.mkv", source.manifestUrl)
        assertEquals(PageOrigin("Clip", MediaDeclaration.VideoTag), source.foundOnPage)
    }

    @Test
    fun hlsLinksArePreferredOverFilesOnAPage() {
        http.respond(
            pageUrl,
            "<video src=\"https://cdn.example/clip.mp4\"></video><meta property=\"og:video\" content=\"https://cdn.example/clip.m3u8\">",
            html,
        )
        http.respond("https://cdn.example/clip.mp4", mp4Bytes(), "video/mp4")
        http.respond("https://cdn.example/clip.m3u8", vod, m3u8)
        assertEquals("https://cdn.example/clip.m3u8", assertIs<SourceResolution.Supported>(resolve()).source.manifestUrl)
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
    fun aPageWithOnlyOtherFormatsIsUnsupported() {
        http.respond(pageUrl, "<video src=\"https://cdn.example/clip.avi\"></video>", html)
        assertEquals(MediaError.UnsupportedPageMedia, assertIs<SourceResolution.Rejected>(resolve()).error)
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
        assertEquals(DefaultSourceResolver.MAX_CANDIDATES, http.requested.count { it.endsWith(".m3u8") })
    }

    @Test
    fun invalidInputIsRejected() {
        assertEquals(MediaError.InvalidUrl(UrlProblem.Insecure), assertIs<SourceResolution.Rejected>(resolve("http://videos.example/x")).error)
        assertEquals(emptyList(), http.probed)
    }
}
