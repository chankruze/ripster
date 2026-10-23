package `in`.geekofia.ripster.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceUrlTest {
    private fun problem(input: String) = (SourceUrl.check(input) as? UrlCheck.Invalid)?.problem

    @Test
    fun acceptsHttpsManifests() {
        val check = SourceUrl.check("  https://Test-Streams.example/x/y.m3u8?a=1  ")
        assertEquals(UrlCheck.Valid("https://Test-Streams.example/x/y.m3u8?a=1", "test-streams.example"), check)
    }

    @Test
    fun acceptsPortsAndIpv6Hosts() {
        assertTrue(SourceUrl.check("https://example.com:8443/a.m3u8") is UrlCheck.Valid)
        assertTrue(SourceUrl.check("https://[2001:db8::1]/a.m3u8") is UrlCheck.Valid)
    }

    @Test
    fun rejectsEmptyAndMalformedInput() {
        assertEquals(UrlProblem.Empty, problem("   "))
        assertEquals(UrlProblem.Malformed, problem("not a url"))
        assertEquals(UrlProblem.Malformed, problem("example.com/video.m3u8"))
        assertEquals(UrlProblem.Malformed, problem("https://"))
        assertEquals(UrlProblem.Malformed, problem("https://exa mple.com/a.m3u8"))
    }

    @Test
    fun rejectsOtherSchemes() {
        assertEquals(UrlProblem.Insecure, problem("http://example.com/a.m3u8"))
        assertEquals(UrlProblem.UnsupportedScheme, problem("file:///sdcard/a.m3u8"))
        assertEquals(UrlProblem.UnsupportedScheme, problem("javascript:alert(1)"))
        assertEquals(UrlProblem.UnsupportedScheme, problem("content://media/a"))
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        assertEquals(UrlProblem.EmbeddedCredentials, problem("https://user:secret@example.com/a.m3u8"))
    }

    @Test
    fun detectsSignedUrls() {
        assertTrue(SourceUrl.looksSigned("https://cdn.example/a.m3u8?Expires=123&Signature=abc"))
        assertTrue(SourceUrl.looksSigned("https://cdn.example/a.m3u8?token=xyz"))
        assertFalse(SourceUrl.looksSigned("https://cdn.example/a.m3u8?quality=hd"))
        assertFalse(SourceUrl.looksSigned("https://cdn.example/a.m3u8"))
    }

    @Test
    fun displayFormHidesQueryAndShortensLongPaths() {
        assertEquals("cdn.example/a/b.m3u8", SourceUrl.displayForm("https://cdn.example/a/b.m3u8?token=secret#frag"))
        assertEquals(
            "cdn.example/…/master.m3u8",
            SourceUrl.displayForm("https://cdn.example/very/long/path/that/goes/on/and/on/forever/master.m3u8"),
        )
    }

    @Test
    fun resolvesRelativeReferences() {
        val base = "https://cdn.example/videos/show/master.m3u8?sig=1"
        assertEquals("https://cdn.example/videos/show/v5/prog.m3u8", SourceUrl.resolve(base, "v5/prog.m3u8"))
        assertEquals("https://cdn.example/root.m3u8", SourceUrl.resolve(base, "/root.m3u8"))
        assertEquals("https://cdn.example/videos/other/a.m3u8", SourceUrl.resolve(base, "../other/a.m3u8"))
        assertEquals("https://other.example/a.m3u8", SourceUrl.resolve(base, "//other.example/a.m3u8"))
        assertEquals("https://x.example/a.m3u8", SourceUrl.resolve(base, "https://x.example/a.m3u8"))
        assertEquals("https://cdn.example/videos/show/a.m3u8?k=v", SourceUrl.resolve(base, "./a.m3u8?k=v"))
        assertEquals("https://cdn.example/videos/show/master.m3u8?q=2", SourceUrl.resolve(base, "?q=2"))
    }

    @Test
    fun splitsPathSegments() {
        assertEquals(listOf("x36xhzz", "x36xhzz.m3u8"), SourceUrl.pathSegments("https://h.example/x36xhzz/x36xhzz.m3u8?a=b"))
        assertEquals(emptyList(), SourceUrl.pathSegments("https://h.example"))
    }
}
