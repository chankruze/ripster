package `in`.geekofia.ripster.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageMetadataTest {
    private val page = "https://videos.example/watch/1"

    private fun metadata(html: String) = PageMediaExtractor.metadata(html, page)

    @Test
    fun readsOpenGraphFirst() {
        val m = metadata(
            """
            <meta property="og:type" content="video.other">
            <meta property="og:title" content="Sample Title &amp; More">
            <meta property="og:image" content="/thumbs/1.jpg">
            <meta property="og:video:duration" content="754">
            <meta property="og:url" content="https://videos.example/watch/1?ref=og">
            <title>Fallback title</title>
            """.trimIndent(),
        )
        assertEquals(PageMetadata("Sample Title & More", "https://videos.example/thumbs/1.jpg", 754.0, "https://videos.example/watch/1?ref=og", true), m)
    }

    @Test
    fun fallsBackToStructuredDataThenHtml() {
        val m = metadata(
            """
            <link rel="canonical" href="https://videos.example/watch/1">
            <script type="application/ld+json">
            {"@type":"VideoObject","name":"From JSON-LD","thumbnailUrl":"https:\/\/cdn.example\/t.jpg","duration":"PT12M34S"}
            </script>
            """.trimIndent(),
        )
        assertEquals("From JSON-LD", m.title)
        assertEquals("https://cdn.example/t.jpg", m.thumbnailUrl)
        assertEquals(754.0, m.durationSeconds)
        assertEquals("https://videos.example/watch/1", m.canonicalUrl, "the canonical link wins over og:url")
        assertTrue(m.isVideoPage)
    }

    @Test
    fun missingOrMalformedMetadataIsNotFatal() {
        val m = metadata(
            """
            <meta property="og:title" content="   ">
            <meta property="og:image" content="javascript:alert(1)">
            <meta property="og:video:duration" content="soon">
            <meta itemprop="duration" content="not-a-duration">
            <link rel="canonical" href="http://insecure.example/x">
            <title>Plain</title>
            """.trimIndent(),
        )
        assertEquals(PageMetadata("Plain", null, null, null, isVideoPage = false), m)
        assertEquals(PageMetadata(null, null, null, null, false), metadata(""))
    }

    @Test
    fun parsesIsoDurations() {
        assertEquals(754.0, PageMediaExtractor.parseIsoDuration("PT12M34S"))
        assertEquals(3_720.0, PageMediaExtractor.parseIsoDuration("PT1H2M"))
        assertEquals(307.5, PageMediaExtractor.parseIsoDuration("P0DT0H5M7.5S"))
        assertEquals(86_400.0, PageMediaExtractor.parseIsoDuration("P1D"))
        assertNull(PageMediaExtractor.parseIsoDuration("PT"))
        assertNull(PageMediaExtractor.parseIsoDuration("12:34"))
        assertNull(PageMediaExtractor.parseIsoDuration("PT0S"))
    }

    @Test
    fun aPageWithoutVideoMarkersIsNotAVideoPage() {
        assertFalse(metadata("<meta property=\"og:type\" content=\"website\"><title>Home</title>").isVideoPage)
    }
}
