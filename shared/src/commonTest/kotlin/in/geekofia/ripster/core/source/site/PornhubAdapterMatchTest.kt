package `in`.geekofia.ripster.core.source.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PornhubAdapterMatchTest {
    private val adapter = PornhubAdapter(http = NoNetwork)

    private fun page(url: String) = assertIs<SiteMatch.Page>(adapter.match(url), url)
    private fun other(url: String) = assertIs<SiteMatch.OtherPage>(adapter.match(url), url)

    @Test
    fun recognisesTheSuppliedWatchUrl() {
        val match = page("https://www.pornhub.org/view_video.php?viewkey=6892418cc3800")
        assertEquals("6892418cc3800", match.id)
        assertEquals("https://www.pornhub.org/view_video.php?viewkey=6892418cc3800", match.url)
        assertEquals("Pornhub", match.site)
    }

    @Test
    fun acceptsTheSiteDomainsAndSingleLabelSubdomains() {
        for (host in listOf("pornhub.com", "www.pornhub.com", "m.pornhub.com", "de.pornhub.com", "pornhub.org", "WWW.PornHub.org")) {
            assertEquals("abc123def", page("https://$host/view_video.php?viewkey=abc123def").id, host)
        }
    }

    @Test
    fun normalisesAwayOtherQueryParametersAndFragments() {
        val match = page("https://www.pornhub.com/view_video.php?utm_source=x&viewkey=abc123def&t=30#comments")
        assertEquals("https://www.pornhub.com/view_video.php?viewkey=abc123def", match.url)
    }

    @Test
    fun ignoresLookalikeAndUnrelatedDomains() {
        for (url in listOf(
            "https://pornhub.com.evil.example/view_video.php?viewkey=abc123def",
            "https://evilpornhub.com/view_video.php?viewkey=abc123def",
            "https://pornhub.co/view_video.php?viewkey=abc123def",
            "https://pornhub.net/view_video.php?viewkey=abc123def",
            "https://a.b.pornhub.com/view_video.php?viewkey=abc123def",
            "https://www-pornhub.com/view_video.php?viewkey=abc123def",
            "https://videos.example/view_video.php?viewkey=abc123def",
        )) {
            assertNull(adapter.match(url), url)
        }
    }

    @Test
    fun rejectsUnsafeOrMalformedUrls() {
        // Not handled by the adapter at all: Ripster's URL rules reject these before any request.
        assertNull(adapter.match("http://www.pornhub.com/view_video.php?viewkey=abc123def"))
        assertNull(adapter.match("https://user:pw@www.pornhub.com/view_video.php?viewkey=abc123def"))
        assertNull(adapter.match("www.pornhub.com/view_video.php?viewkey=abc123def"))
        assertNull(adapter.match(""))
    }

    @Test
    fun otherPagesOnTheSiteAreUnsupportedRatherThanGuessed() {
        other("https://www.pornhub.com/")
        other("https://www.pornhub.com/video/search?search=x")
        other("https://www.pornhub.com/embed/abc123def")
        other("https://www.pornhub.com/view_video.php")
        other("https://www.pornhub.com/view_video.php?viewkey=")
        other("https://www.pornhub.com/view_video.php?viewkey=abc")
        other("https://www.pornhub.com/view_video.php?viewkey=../../etc")
        other("https://www.pornhub.com/view_video.php?viewkey=abc%20123def")
        other("https://www.pornhub.com/view_video.php?viewkey=abc123def&viewkey=xyz789abc")
        other("https://www.pornhub.com/videos/view_video.php?viewkey=abc123def")
    }
}

/** For tests that only match URLs: any request is a test failure. */
private object NoNetwork : `in`.geekofia.ripster.platform.HttpClient {
    override suspend fun get(url: String, maxBytes: Int) = error("unexpected request")
    override suspend fun probe(url: String, bytes: Int) = error("unexpected request")
}
