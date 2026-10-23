package `in`.geekofia.ripster.core.source

import `in`.geekofia.ripster.core.hls.SegmentContainer
import `in`.geekofia.ripster.platform.HttpClient
import `in`.geekofia.ripster.platform.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HlsSourceResolverTest {
    private class FakeHttp : HttpClient {
        val responses = mutableMapOf<String, () -> HttpResponse>()
        val requested = mutableListOf<String>()

        fun text(url: String, body: String, status: Int = 200, type: String? = "application/vnd.apple.mpegurl", finalUrl: String = url) {
            responses[url] = { HttpResponse(finalUrl, status, type, body.encodeToByteArray()) }
        }

        override suspend fun get(url: String, maxBytes: Int): HttpResponse {
            requested += url
            return responses[url]?.invoke() ?: HttpResponse(url, 404, "text/plain", ByteArray(0))
        }

        override suspend fun probe(url: String, bytes: Int): HttpResponse = get(url, bytes)
    }

    private val http = FakeHttp()
    private val resolver = HlsSourceResolver(http)

    private fun resolve(url: String) = runBlocking { resolver.resolve(url) }
    private fun rejection(url: String) = assertIs<SourceResolution.Rejected>(resolve(url)).error

    private val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\ns0.ts\n#EXTINF:5,\ns1.ts\n#EXT-X-ENDLIST\n"

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=2400000,CODECS="avc1.640020,ac-3",RESOLUTION=1920x1080,AUDIO="ac3"
        hd-ac3.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.640020,mp4a.40.2",RESOLUTION=1920x1080,AUDIO="aac"
        hd.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.64001e,mp4a.40.2",RESOLUTION=640x360,AUDIO="aac"
        sd.m3u8
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Stereo",DEFAULT=NO,URI="aac-a.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Stereo (main)",DEFAULT=YES,URI="aac-b.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="ac3",NAME="Surround",DEFAULT=YES,URI="ac3.m3u8"
    """.trimIndent()

    @Test
    fun aMediaPlaylistIsSupported() {
        http.text("https://cdn.example/v/index.m3u8", vod)
        val source = assertIs<SourceResolution.Supported>(resolve("https://cdn.example/v/index.m3u8")).source
        assertEquals(ManifestKind.Media, source.kind)
        assertEquals(emptyList(), source.variants)
        assertEquals(15.0, source.details.durationSeconds)
        assertEquals(2, source.details.segmentCount)
        assertEquals(SegmentContainer.MpegTs, source.details.container)
        assertEquals(MediaSelection(null, null), source.defaultSelection)
    }

    @Test
    fun aMasterPlaylistListsVariantsBestFirstAndPrefersOneThatRemuxes() {
        http.text("https://cdn.example/v/master.m3u8", master)
        http.text("https://cdn.example/v/hd.m3u8", vod)
        val source = assertIs<SourceResolution.Supported>(resolve("https://cdn.example/v/master.m3u8")).source
        assertEquals(ManifestKind.Multivariant, source.kind)
        assertEquals(
            listOf("hd-ac3.m3u8", "hd.m3u8", "sd.m3u8"),
            source.variants.map { it.url.substringAfterLast('/') },
        )
        // The 1080p AAC variant, not the higher-bandwidth AC-3 one, and its group's default audio.
        val selection = source.defaultSelection
        assertEquals("https://cdn.example/v/hd.m3u8", selection.variant?.url)
        assertEquals("Stereo (main)", selection.audio?.name)
        // Details come from the default variant's media playlist.
        assertTrue("https://cdn.example/v/hd.m3u8" in http.requested)
        assertEquals(15.0, source.details.durationSeconds)
    }

    @Test
    fun redirectsChangeTheManifestUrlAndRelativeResolution() {
        http.text("https://short.example/x", master.replace("hd.m3u8", "hd.m3u8"), finalUrl = "https://cdn.example/v/master.m3u8")
        http.text("https://cdn.example/v/hd.m3u8", vod)
        val source = assertIs<SourceResolution.Supported>(resolve("https://short.example/x")).source
        assertEquals("https://short.example/x", source.inputUrl)
        assertEquals("https://cdn.example/v/master.m3u8", source.manifestUrl)
        assertEquals("https://cdn.example/v/hd.m3u8", source.defaultSelection.variant?.url)
    }

    @Test
    fun invalidInputIsRejectedWithoutANetworkRequest() {
        assertEquals(MediaError.InvalidUrl(UrlProblem.Insecure), rejection("http://cdn.example/a.m3u8"))
        assertEquals(MediaError.InvalidUrl(UrlProblem.Empty), rejection(""))
        assertEquals(emptyList(), http.requested)
    }

    @Test
    fun aWebPageNeedsTheUserToFindTheManifest() {
        http.text("https://site.example/watch", "<!DOCTYPE html><html>…</html>", type = "text/html; charset=utf-8")
        val error = rejection("https://site.example/watch")
        assertEquals(MediaError.WebPage, error)
        assertEquals(ErrorCategory.NeedsUserAction, error.category)
    }

    @Test
    fun otherContentIsNotHls() {
        http.text("https://cdn.example/a.mp4", "\u0000\u0000\u0000 ftypisom", type = "video/mp4")
        assertEquals(MediaError.NotHls("video/mp4"), rejection("https://cdn.example/a.mp4"))
    }

    @Test
    fun httpErrorsAreTyped() {
        http.text("https://cdn.example/gone.m3u8", "", status = 404)
        assertEquals(ErrorCategory.Unavailable, rejection("https://cdn.example/gone.m3u8").category)
        http.text("https://cdn.example/s.m3u8?Expires=1&Signature=x", "", status = 403)
        assertEquals(ErrorCategory.Expired, rejection("https://cdn.example/s.m3u8?Expires=1&Signature=x").category)
        http.text("https://cdn.example/private.m3u8", "", status = 403)
        assertEquals(ErrorCategory.Unauthorized, rejection("https://cdn.example/private.m3u8").category)
    }

    @Test
    fun aFailingVariantPlaylistRejectsTheSource() {
        http.text("https://cdn.example/v/master.m3u8", master)
        http.text("https://cdn.example/v/hd.m3u8", "", status = 410)
        assertEquals(MediaError.HttpStatus(410), rejection("https://cdn.example/v/master.m3u8"))
    }

    @Test
    fun liveDrmAndSampleAesAreUnsupported() {
        http.text("https://cdn.example/live.m3u8", "#EXTM3U\n#EXTINF:6,\na.ts\n")
        assertEquals(MediaError.LiveStream, rejection("https://cdn.example/live.m3u8"))
        http.text("https://cdn.example/drm.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
        assertEquals(MediaError.DrmProtected, rejection("https://cdn.example/drm.m3u8"))
        http.text("https://cdn.example/saes.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k.bin\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
        assertEquals(MediaError.SampleAesEncrypted, rejection("https://cdn.example/saes.m3u8"))
    }

    @Test
    fun clearKeyAes128IsAccepted() {
        http.text("https://cdn.example/aes.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
        assertIs<SourceResolution.Supported>(resolve("https://cdn.example/aes.m3u8"))
    }

    @Test
    fun networkFailuresAreTyped() {
        http.responses["https://down.example/a.m3u8"] = { throw MediaException(MediaError.Network(NetworkProblem.Offline)) }
        assertEquals(MediaError.Network(NetworkProblem.Offline), rejection("https://down.example/a.m3u8"))
    }

    @Test
    fun malformedPlaylistsAreParseErrors() {
        http.text("https://cdn.example/bad.m3u8", "#EXTM3U\n#EXTINF:oops,\na.ts\n#EXT-X-ENDLIST")
        assertIs<MediaError.Parse>(rejection("https://cdn.example/bad.m3u8"))
    }
}
