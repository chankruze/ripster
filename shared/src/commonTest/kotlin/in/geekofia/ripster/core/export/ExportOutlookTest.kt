package `in`.geekofia.ripster.core.export

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportOutlookTest {
    @Test
    fun h264OrHevcWithAacRemuxes() {
        assertEquals(ExportOutlook.Remux, ExportOutlook.forCodecs(listOf("avc1.640020", "mp4a.40.2")))
        assertEquals(ExportOutlook.Remux, ExportOutlook.forCodecs(listOf("hvc1.2.4.L123.B0", "mp4a.40.5")))
        assertEquals(ExportOutlook.Remux, ExportOutlook.forCodecs(listOf("avc1.4d401f")), "video only")
        assertEquals(ExportOutlook.Remux, ExportOutlook.forCodecs(listOf("avc1.4d401f", "mp4a.40.2", "wvtt")), "subtitles are left out")
    }

    @Test
    fun otherCodecsMayNeedTranscoding() {
        assertEquals(ExportOutlook.AudioMayNeedTranscoding, ExportOutlook.forCodecs(listOf("avc1.640020", "ac-3")))
        assertEquals(ExportOutlook.AudioMayNeedTranscoding, ExportOutlook.forCodecs(listOf("avc1.640020", "ec-3")))
        assertEquals(ExportOutlook.VideoMayNeedTranscoding, ExportOutlook.forCodecs(listOf("vp09.00.10.08", "mp4a.40.2")))
        assertEquals(ExportOutlook.VideoMayNeedTranscoding, ExportOutlook.forCodecs(listOf("av01.0.05M.08", "opus")))
    }

    @Test
    fun undeclaredCodecsAreUnknown() {
        assertEquals(ExportOutlook.Unknown, ExportOutlook.forCodecs(emptyList()))
    }

    @Test
    fun labels() {
        assertEquals(listOf("H.264", "AAC", "AC-3", "E-AC-3", "H.265", "xyz9"), listOf("avc1.640020", "mp4a.40.2", "ac-3", "ec-3", "hev1.1", "xyz9").map(::codecLabel))
    }
}

class FileNamesTest {
    @Test
    fun removesPathAndReservedCharacters() {
        assertEquals("a b c d", FileNames.sanitize("a/b\\c:d"))
        assertEquals("evil", FileNames.sanitize("../../evil"))
        assertEquals("name", FileNames.sanitize("  name\u0000\u001F  "))
        assertEquals("video", FileNames.sanitize("///"))
        assertEquals("video", FileNames.sanitize(""))
    }

    @Test
    fun capsTheLength() {
        assertEquals(80, FileNames.sanitize("x".repeat(500)).length)
    }

    @Test
    fun picksTheFirstFreeName() {
        val existing = setOf("Show.mp4", "Show (2).mp4")
        assertEquals("Show (3).mp4", FileNames.unique("Show", "mp4") { it in existing })
        assertEquals("Other.mp4", FileNames.unique("Other", "mp4") { it in existing })
    }
}
