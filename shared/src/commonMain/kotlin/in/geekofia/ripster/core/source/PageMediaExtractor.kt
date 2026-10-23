package `in`.geekofia.ripster.core.source

/** Where on a page a media link was declared. */
enum class MediaDeclaration(val label: String) {
    VideoTag("HTML video element"),
    OpenGraph("Open Graph tag"),
    StructuredData("schema.org VideoObject"),
}

enum class DeclaredMediaKind { Hls, Mp4, Other }

/** A media URL the page itself publishes. [url] is absolute and https (others are dropped). */
data class DeclaredMedia(val url: String, val kind: DeclaredMediaKind, val declaration: MediaDeclaration)

data class PageMedia(
    /** The page's own title (`og:title`, else `<title>`), for naming the download. */
    val title: String?,
    /** In page order within each declaration type: video elements, then Open Graph, then JSON-LD. */
    val media: List<DeclaredMedia>,
)

/**
 * Reads the media links a web page *declares* for others to use, in standard places only:
 * `<video src>` / `<source src type>`, Open Graph `og:video*` tags, and schema.org `VideoObject`
 * `contentUrl` in JSON-LD. Scripts are never run and nothing is reverse-engineered: pages whose
 * player builds stream URLs at runtime simply declare nothing usable here.
 *
 * The HTML is untrusted: it's read with tolerant patterns, sizes and counts are capped, and every
 * URL is resolved against the page and re-validated (https only) before it's returned.
 */
object PageMediaExtractor {
    private const val MAX_MEDIA = 20

    private val videoOrSource = Regex("<(?:video|source)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val titleTag = Regex("<title[^>]*>([^<]{1,300})</title>", RegexOption.IGNORE_CASE)
    private val jsonLd = Regex(
        "<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val contentUrl = Regex("\"contentUrl\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,2048})\"")
    private val attribute = Regex("([a-zA-Z:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")

    fun extract(html: String, pageUrl: String): PageMedia {
        val found = LinkedHashMap<String, DeclaredMedia>()
        fun add(raw: String?, type: String?, declaration: MediaDeclaration) {
            if (raw.isNullOrBlank() || found.size >= MAX_MEDIA) return
            val resolved = SourceUrl.resolve(pageUrl, decodeEntities(raw.trim()))
            val url = (SourceUrl.check(resolved) as? UrlCheck.Valid)?.url ?: return
            if (url !in found) found[url] = DeclaredMedia(url, kindOf(url, type), declaration)
        }

        videoOrSource.findAll(html).forEach { tag ->
            val a = attributes(tag.value)
            add(a["src"], a["type"], MediaDeclaration.VideoTag)
        }

        val metas = meta.findAll(html).map { attributes(it.value) }.toList()
        fun metaContent(name: String) = metas.filter { (it["property"] ?: it["name"])?.lowercase() == name }.mapNotNull { it["content"] }
        val ogType = metaContent("og:video:type").firstOrNull()
        (metaContent("og:video:secure_url") + metaContent("og:video:url") + metaContent("og:video"))
            .forEach { add(it, ogType, MediaDeclaration.OpenGraph) }

        jsonLd.findAll(html).forEach { block ->
            if (!block.groupValues[1].contains("VideoObject")) return@forEach
            contentUrl.findAll(block.groupValues[1]).forEach { add(unescapeJson(it.groupValues[1]), null, MediaDeclaration.StructuredData) }
        }

        val title = metaContent("og:title").firstOrNull()?.takeIf { it.isNotBlank() }
            ?: titleTag.find(html)?.groupValues?.get(1)
        return PageMedia(title?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }, found.values.toList())
    }

    private fun kindOf(url: String, type: String?): DeclaredMediaKind {
        val mime = type?.substringBefore(';')?.trim()?.lowercase()
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            mime == "application/x-mpegurl" || mime == "application/vnd.apple.mpegurl" || path.endsWith(".m3u8") -> DeclaredMediaKind.Hls
            mime == "video/mp4" || path.endsWith(".mp4") -> DeclaredMediaKind.Mp4
            else -> DeclaredMediaKind.Other
        }
    }

    private fun attributes(tag: String): Map<String, String> =
        attribute.findAll(tag).associate { m ->
            m.groupValues[1].lowercase() to (m.groups[2] ?: m.groups[3] ?: m.groups[4])!!.value
        }

    /** The few entities that matter in URLs and titles. */
    internal fun decodeEntities(text: String): String = text
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#x2F;", "/").replace("&#47;", "/")
        .replace("&amp;", "&")

    /** JSON string escapes (`\/`, `\u0026`, …) in a JSON-LD value. */
    internal fun unescapeJson(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    'u' -> {
                        text.substring(i + 2, minOf(i + 6, text.length)).toIntOrNull(16)?.let { append(it.toChar()) }
                        i += 6
                        continue
                    }
                    'n', 'r', 't' -> Unit
                    else -> append(n)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
