package `in`.geekofia.ripster.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageMediaExtractorTest {
    private val page = "https://videos.example/watch/clip-42"

    private fun extract(html: String) = PageMediaExtractor.extract(html, page)

    @Test
    fun findsVideoElementsAndTheirSources() {
        val media = extract(
            """
            <video controls src="/media/clip-42/master.m3u8"></video>
            <video><source src='https://cdn.example/clip.mp4' type='video/mp4'><source src=stream/index type="application/x-mpegURL"></video>
            """.trimIndent(),
        ).media
        assertEquals(
            listOf(
                DeclaredMedia("https://videos.example/media/clip-42/master.m3u8", DeclaredMediaKind.Hls, MediaDeclaration.VideoTag),
                DeclaredMedia("https://cdn.example/clip.mp4", DeclaredMediaKind.File, MediaDeclaration.VideoTag),
                DeclaredMedia("https://videos.example/watch/stream/index", DeclaredMediaKind.Hls, MediaDeclaration.VideoTag),
            ),
            media,
        )
    }

    @Test
    fun findsOpenGraphVideoAndTitle() {
        val result = extract(
            """
            <head>
              <meta property="og:title" content="A Clip &amp; Its Making">
              <meta property="og:video:secure_url" content="https://cdn.example/hls/clip.m3u8?token=abc&amp;exp=1">
              <meta property="og:video:type" content="application/vnd.apple.mpegurl">
              <title>Ignored when og:title exists</title>
            </head>
            """.trimIndent(),
        )
        assertEquals("A Clip & Its Making", result.title)
        assertEquals(
            listOf(DeclaredMedia("https://cdn.example/hls/clip.m3u8?token=abc&exp=1", DeclaredMediaKind.Hls, MediaDeclaration.OpenGraph)),
            result.media,
        )
    }

    @Test
    fun findsSchemaOrgVideoObjects() {
        val result = extract(
            """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"VideoObject","name":"Clip",
             "contentUrl":"https:\/\/cdn.example\/v\/master.m3u8?a=1&b=2"}
            </script>
            <script type="application/ld+json">{"@type":"Organization","contentUrl":"https://cdn.example/logo.m3u8"}</script>
            """.trimIndent(),
        )
        assertEquals(
            listOf(DeclaredMedia("https://cdn.example/v/master.m3u8?a=1&b=2", DeclaredMediaKind.Hls, MediaDeclaration.StructuredData)),
            result.media,
            "only VideoObject blocks count",
        )
    }

    @Test
    fun usesTheTitleTagWithoutOpenGraph() {
        assertEquals("Plain title", extract("<html><title>  Plain title </title></html>").title)
        assertNull(extract("<html><body>no title</body></html>").title)
    }

    @Test
    fun dropsInsecureUnsafeAndDuplicateLinks() {
        val media = extract(
            """
            <video src="http://insecure.example/a.m3u8"></video>
            <video src="javascript:alert(1)"></video>
            <video src="https://user:pw@cdn.example/a.m3u8"></video>
            <video src="https://cdn.example/same.m3u8"></video>
            <meta property="og:video" content="https://cdn.example/same.m3u8">
            <video src=""></video>
            """.trimIndent(),
        ).media
        assertEquals(listOf("https://cdn.example/same.m3u8"), media.map { it.url })
    }

    @Test
    fun ignoresScriptsAndPlayerConfiguration() {
        // Stream URLs built or hidden in scripts are deliberately not looked for.
        val result = extract(
            """
            <script>var player = { hls: "https://cdn.example/hidden/master.m3u8" };</script>
            <div data-src="https://cdn.example/also-hidden.m3u8"></div>
            """.trimIndent(),
        )
        assertEquals(emptyList(), result.media)
    }

    @Test
    fun capsTheNumberOfLinks() {
        val html = (1..50).joinToString("\n") { "<video src=\"https://cdn.example/$it.m3u8\"></video>" }
        assertEquals(20, extract(html).media.size)
    }

    @Test
    fun classifiesFilesByTypeOrNameAndTheRestAsOther() {
        val media = extract(
            """
            <video src="https://cdn.example/a.webm"></video>
            <video src="https://cdn.example/b.MKV?x=1"></video>
            <video><source src="https://cdn.example/c" type="video/x-matroska"></video>
            <video src="https://cdn.example/d.avi"></video>
            """.trimIndent(),
        ).media
        assertEquals(
            listOf(DeclaredMediaKind.File, DeclaredMediaKind.File, DeclaredMediaKind.File, DeclaredMediaKind.Other),
            media.map { it.kind },
        )
    }
}
