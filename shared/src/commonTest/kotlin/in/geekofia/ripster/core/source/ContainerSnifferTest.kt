package `in`.geekofia.ripster.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContainerSnifferTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun recognisesMp4ByItsFirstBox() {
        assertEquals(FileContainer.Mp4, ContainerSniffer.sniff(bytes(0, 0, 0, 0x18) + "ftypmp42".encodeToByteArray()))
        assertEquals(FileContainer.Mp4, ContainerSniffer.sniff(bytes(0, 0, 0x10, 0) + "moov".encodeToByteArray()))
    }

    @Test
    fun recognisesMatroskaAndWebmByDocType() {
        val ebml = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x88)
        assertEquals(FileContainer.Matroska, ContainerSniffer.sniff(ebml + "matroska".encodeToByteArray()))
        assertEquals(FileContainer.WebM, ContainerSniffer.sniff(ebml + "webm".encodeToByteArray()))
    }

    @Test
    fun anythingElseIsNotAFile() {
        assertNull(ContainerSniffer.sniff("#EXTM3U\n#EXTINF:1,".encodeToByteArray()))
        assertNull(ContainerSniffer.sniff("<!doctype html><html>".encodeToByteArray()))
        assertNull(ContainerSniffer.sniff(bytes(0x1A, 0x45)))
        assertNull(ContainerSniffer.sniff(ByteArray(0)))
    }
}
