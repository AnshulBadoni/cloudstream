package com.custom

import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

/**
 * Parsing rules shared by the standalone torrent providers and Himeros.
 *
 * Torrent indexes move their catalogue and download hosts independently, so a
 * search-result URL is not necessarily the page which contains the magnet.
 * Keeping that handling in one place prevents the providers from drifting.
 */
data class TorrentLink(
    val url: String,
    val isMagnet: Boolean
)

data class TorrentSearchEntry(
    val title: String,
    val url: String
)

object TorrentSupport {
    /** Current first-party LimeTorrent catalogue hosts, in preferred order. */
    val limeMirrors = listOf(
        "https://limetorrent.net",
        "https://www.limetorrents.org",
        "https://limetorrent.io"
    )

    private val mediaFilePattern = Regex("""(?i)\.torrent(?:[?#].*)?$""")
    private val limeDetailPattern = Regex("""(?i)-\d+\.html(?:[?#].*)?$""")
    private val encodedMagnetPattern = Regex("""(?i)(?:magnet:|magnet%3a)[^\s"'<>]+""")
    private val queryMagnetPattern = Regex(
        """(?i)(?:[?&](?:m|magnet|url|link|download)=)([^&#\s"']+)"""
    )

    fun limeSearchUrls(mirror: String, query: String): List<String> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val base = mirror.trimEnd('/')
        // Both routes are live on different LimeTorrent mirrors.
        return listOf(
            "$base/search/?catname=&q=$encoded",
            "$base/search.php?catname=&q=$encoded"
        )
    }

    fun absoluteUrl(value: String, baseUrl: String): String {
        val clean = value.trim().replace("\\/", "/")
        return when {
            clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }
                .getOrElse { baseUrl.trimEnd('/') + "/" + clean.trimStart('/') }
        }
    }

    fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}/"
        }.getOrElse { url.substringBefore('/', "") + "/" }
    }

    /**
     * Extract actual detail-page links only. The current Lime search layout
     * uses links ending in '-<numeric id>.html', while its navigation and ads
     * are regular search/category links.
     */
    fun findLimeSearchEntries(document: Document, baseUrl: String): List<TorrentSearchEntry> {
        return document.select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href").trim()
                val title = anchor.text().trim()
                if (title.isBlank() || !limeDetailPattern.containsMatchIn(href)) return@mapNotNull null
                val lowerHref = href.lowercase()
                if (lowerHref.contains("/search") || lowerHref.contains("/blog") || lowerHref.contains("/faq")) {
                    return@mapNotNull null
                }
                TorrentSearchEntry(title, absoluteUrl(href, baseUrl))
            }
            .distinctBy { it.url }
    }

    /**
     * Find a magnet in an element attribute, a URL-encoded download endpoint,
     * a data attribute, or inline script text. Direct magnets are deliberately
     * not URL-decoded: decoding them turns '+' in a display name into spaces.
     */
    fun extractMagnet(document: Document): String? {
        val attributes = listOf("href", "data-magnet", "data-url", "data-href", "data-download", "onclick")
        document.select("[href], [data-magnet], [data-url], [data-href], [data-download], [onclick]")
            .forEach { element ->
                attributes.forEach { attribute ->
                    if (element.hasAttr(attribute)) {
                        extractMagnet(element.attr(attribute))?.let { return it }
                    }
                }
            }

        encodedMagnetPattern.findAll(document.html()).forEach { match ->
            extractMagnet(match.value)?.let { return it }
        }
        return null
    }

    fun extractMagnet(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var candidate = unescape(value)
        repeat(3) {
            directMagnet(candidate)?.let { return it }

            queryMagnetPattern.find(candidate)?.groupValues?.getOrNull(1)?.let { encoded ->
                decode(encoded)?.let { decoded ->
                    directMagnet(unescape(decoded))?.let { return it }
                }
            }

            val decoded = decode(candidate) ?: return null
            if (decoded == candidate) return null
            candidate = unescape(decoded)
        }
        return directMagnet(candidate)
    }

    fun extractTorrent(document: Document): TorrentLink? {
        extractMagnet(document)?.let { return TorrentLink(it, isMagnet = true) }

        // A .torrent URL is still useful when a mirror does not expose a
        // magnet link. CloudStream marks it as a torrent source below.
        document.select("a[href]").firstOrNull { anchor ->
            mediaFilePattern.containsMatchIn(anchor.attr("href").trim())
        }?.let { anchor ->
            return TorrentLink(absoluteUrl(anchor.attr("href"), document.location()), isMagnet = false)
        }
        return null
    }

    /**
     * LimeTorrent's catalogue hosts currently point their Magnet button at a
     * separate detail host (for example limetorrent.store). Return only links
     * labelled as a magnet/download to avoid chasing arbitrary ads.
     */
    fun alternateTorrentDetailUrls(document: Document, baseUrl: String): List<String> {
        return document.select("a[href]")
            .mapNotNull { anchor ->
                val label = "${anchor.text()} ${anchor.attr("title")}".lowercase()
                val href = anchor.attr("href").trim()
                if (href.isBlank() || href.startsWith("magnet:", ignoreCase = true)) return@mapNotNull null
                if (!label.contains("magnet")) return@mapNotNull null
                val absolute = absoluteUrl(href, baseUrl)
                if (!absolute.startsWith("http", ignoreCase = true)) null else absolute
            }
            .distinct()
    }

    fun qualityFromText(text: String): Int = when {
        text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> 2160
        text.contains("1440p", ignoreCase = true) || text.contains("2k", ignoreCase = true) -> 1440
        text.contains("1080p", ignoreCase = true) -> 1080
        text.contains("720p", ignoreCase = true) -> 720
        text.contains("480p", ignoreCase = true) -> 480
        text.contains("360p", ignoreCase = true) -> 360
        else -> 0
    }

    private fun directMagnet(value: String): String? {
        val index = value.indexOf("magnet:", ignoreCase = true)
        if (index < 0) return null
        val magnet = value.substring(index)
            .takeWhile { !it.isWhitespace() && it != '"' && it != '\'' && it != '<' && it != '>' }
            .replace("&amp;", "&", ignoreCase = true)
            .trimEnd(',', ';', ')', ']')
        return magnet.takeIf { it.startsWith("magnet:?", ignoreCase = true) && it.contains("xt=urn:btih:", ignoreCase = true) }
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrNull()

    private fun unescape(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003a", ":", ignoreCase = true)
        .replace("\\/", "/")
}
