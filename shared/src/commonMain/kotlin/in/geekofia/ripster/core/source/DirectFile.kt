package `in`.geekofia.ripster.core.source

/** A directly downloadable media file. Kept in its own container: it's saved as is, not converted. */
enum class FileContainer(val label: String, val extension: String, val mimeType: String) {
    Mp4("MP4", "mp4", "video/mp4"),
    Matroska("MKV", "mkv", "video/x-matroska"),
    WebM("WebM", "webm", "video/webm"),
}

/** What a probe learned about a direct file: its container (from its bytes) and size, if the server says. */
data class DirectFileInfo(val container: FileContainer, val sizeBytes: Long?)

/**
 * Identifies a file from its first bytes, never from its name or the server's content type
 * (both are often wrong or generic, like `application/octet-stream`).
 *
 * - MP4 / ISO BMFF: a box at offset 0 whose type (bytes 4..7) is `ftyp` (or, rarely, `moov`).
 * - Matroska / WebM: the EBML signature `1A 45 DF A3`; the header's DocType says which.
 */
object ContainerSniffer {
    /** Enough for the EBML header with its DocType. */
    const val SNIFF_BYTES = 64

    private val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    fun sniff(bytes: ByteArray): FileContainer? {
        if (bytes.size >= 8) {
            val boxType = bytes.copyOfRange(4, 8).decodeToString()
            if (boxType == "ftyp" || boxType == "moov") return FileContainer.Mp4
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(ebml)) {
            val header = bytes.copyOfRange(0, minOf(bytes.size, SNIFF_BYTES)).decodeToString()
            return if ("webm" in header) FileContainer.WebM else FileContainer.Matroska
        }
        return null
    }
}
