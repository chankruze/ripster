package `in`.geekofia.ripster.core.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsPlaylistParserTest {
    private val base = "https://cdn.example/show/master.m3u8"

    private fun parse(text: String): HlsPlaylist =
        assertIs<HlsParseResult.Success>(HlsPlaylistParser.parse(text, base)).playlist

    private fun failure(text: String): String = assertIs<HlsParseResult.Failure>(HlsPlaylistParser.parse(text, base)).detail

    /** Shaped like Apple's advanced example: demuxed audio groups, I-frame and subtitle entries. */
    private val multivariant = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2168183,BANDWIDTH=2177116,CODECS="avc1.640020,mp4a.40.2",RESOLUTION=960x540,FRAME-RATE=60.000,CLOSED-CAPTIONS="cc1",AUDIO="aud1",SUBTITLES="sub1"
        v5/prog_index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=541052,CODECS="avc1.640015,mp4a.40.2",RESOLUTION=480x270,AUDIO="aud1"
        v2/prog_index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2399619,CODECS="avc1.640020,ac-3",RESOLUTION=960x540,AUDIO="aud2"
        v5/prog_index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2177116,CODECS="avc1.640020,mp4a.40.2",RESOLUTION=960x540,AUDIO="aud1"
        v5/prog_index.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=187492,CODECS="avc1.64002a",RESOLUTION=1920x1080,URI="v7/iframe_index.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud1",LANGUAGE="en",NAME="English",AUTOSELECT=YES,DEFAULT=YES,CHANNELS="2",URI="a1/prog_index.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud2",LANGUAGE="en",NAME="English, 5.1",DEFAULT=NO,CHANNELS="6",URI="a2/prog_index.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub1",NAME="English",URI="s1/en/prog_index.m3u8"
    """.trimIndent()

    @Test
    fun readsVariantsWithAttributes() {
        val playlist = assertIs<HlsPlaylist.Multivariant>(parse(multivariant))
        // v5 with AC-3 audio is its own choice (same video URL, other audio group); the exact repeat
        // of v5 + aud1 is dropped, and the I-frame playlist is not a variant.
        assertEquals(listOf("aud1", "aud1", "aud2"), playlist.variants.map { it.audioGroupId })
        assertEquals(listOf("avc1.640020", "ac-3"), playlist.variants[2].codecs)
        assertEquals(playlist.variants[0].url, playlist.variants[2].url)
        val first = playlist.variants[0]
        assertEquals("https://cdn.example/show/v5/prog_index.m3u8", first.url)
        assertEquals(2177116, first.bandwidth)
        assertEquals(2168183, first.averageBandwidth)
        assertEquals(Resolution(960, 540), first.resolution)
        assertEquals(60.0, first.frameRate)
        assertEquals(listOf("avc1.640020", "mp4a.40.2"), first.codecs)
        assertEquals("aud1", first.audioGroupId)
        assertNull(playlist.variants[1].averageBandwidth)
    }

    @Test
    fun readsAudioRenditionsOnly() {
        val playlist = assertIs<HlsPlaylist.Multivariant>(parse(multivariant))
        assertEquals(listOf("aud1", "aud2"), playlist.audioRenditions.map { it.groupId })
        val english = playlist.audioRenditions[0]
        assertEquals("English", english.name)
        assertEquals("2", english.channels)
        assertEquals("https://cdn.example/show/a1/prog_index.m3u8", english.url)
        assertTrue(english.isDefault)
        assertEquals("English, 5.1", playlist.audioRenditions[1].name)
    }

    @Test
    fun readsATsMediaPlaylist() {
        val media = assertIs<HlsPlaylist.Media>(
            parse(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:10
                #EXTINF:10.0,
                segment0.ts
                #EXTINF:9.5,
                segment1.ts?token=a
                #EXT-X-ENDLIST
                """.trimIndent(),
            ),
        )
        assertEquals(2, media.segmentCount)
        assertEquals(19.5, media.durationSeconds)
        assertTrue(media.isComplete)
        assertEquals(SegmentContainer.MpegTs, media.container)
        assertEquals(HlsEncryption.None, media.encryption)
    }

    @Test
    fun detectsFragmentedMp4BySegmentMap() {
        val media = assertIs<HlsPlaylist.Media>(
            parse("#EXTM3U\n#EXT-X-MAP:URI=\"main.mp4\",BYTERANGE=\"719@0\"\n#EXTINF:6.0,\t\n#EXT-X-BYTERANGE:274201@719\nmain.mp4\n#EXT-X-ENDLIST"),
        )
        assertEquals(SegmentContainer.FragmentedMp4, media.container)
        assertEquals(1, media.segmentCount)
    }

    @Test
    fun aPlaylistWithoutEndListIsLive() {
        val media = assertIs<HlsPlaylist.Media>(parse("#EXTM3U\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts"))
        assertTrue(media.isLive)
    }

    @Test
    fun classifiesEncryption() {
        fun encryption(key: String) = assertIs<HlsPlaylist.Media>(
            parse("#EXTM3U\n$key\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST"),
        ).encryption
        assertEquals(HlsEncryption.Aes128, encryption("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\""))
        assertEquals(HlsEncryption.SampleAes, encryption("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key.bin\""))
        assertEquals(HlsEncryption.Drm, encryption("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\""))
        assertEquals(HlsEncryption.None, encryption("#EXT-X-KEY:METHOD=NONE"))
    }

    @Test
    fun sessionKeysMarkAMultivariantPlaylistAsProtected() {
        val playlist = assertIs<HlsPlaylist.Multivariant>(
            parse("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"data:x\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8"),
        )
        assertEquals(HlsEncryption.Drm, playlist.sessionEncryption)
    }

    @Test
    fun rejectsNonPlaylists() {
        assertEquals("Missing #EXTM3U header", failure("<html><body>video</body></html>"))
        assertEquals("Playlist has no variants or segments", failure("#EXTM3U\n#EXT-X-VERSION:3"))
        assertEquals("Malformed #EXTINF", failure("#EXTM3U\n#EXTINF:abc,\na.ts"))
        assertEquals("No usable variants", failure("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1x1\nv.m3u8"))
    }

    @Test
    fun toleratesABomAndBlankLines() {
        assertTrue(HlsPlaylistParser.looksLikePlaylist("﻿\n  #EXTM3U\n"))
        assertIs<HlsPlaylist.Media>(parse("﻿#EXTM3U\r\n\r\n#EXTINF:1,\r\na.ts\r\n#EXT-X-ENDLIST\r\n"))
    }

    @Test
    fun parsesQuotedAttributesWithCommasAndStripsControlCharacters() {
        val attributes = HlsPlaylistParser.attributes("A=1,CODECS=\"avc1.4d401f,mp4a.40.2\",NAME=\"x\u001Fy\",B=two")
        assertEquals(mapOf("A" to "1", "CODECS" to "avc1.4d401f,mp4a.40.2", "NAME" to "xy", "B" to "two"), attributes)
    }
}
