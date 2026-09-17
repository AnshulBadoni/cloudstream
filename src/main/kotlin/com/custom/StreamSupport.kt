package com.custom

/** Small, site-agnostic helpers for direct media URLs embedded in player HTML. */
object StreamSupport {
    private val mediaUrlPattern = Regex(
        """(?i)((?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)"""
    )
    private val playerValuePattern = Regex(
        """(?i)(?:file|source|src|hls|video_url)\s*[:=]\s*["']((?:https?:)?//[^"']+?(?:\.m3u8|\.mp4)[^"']*)["']"""
    )

    fun extractMediaUrls(raw: String): List<String> {
        val urls = LinkedHashSet<String>()
        val normalized = raw
            .replace("&amp;", "&", ignoreCase = true)
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)

        playerValuePattern.findAll(normalized).forEach { match ->
            normaliseMediaUrl(match.groupValues[1])?.let(urls::add)
        }
        mediaUrlPattern.findAll(normalized).forEach { match ->
            normaliseMediaUrl(match.groupValues[1])?.let(urls::add)
        }
        return urls.toList()
    }

    fun isDirectMediaUrl(url: String): Boolean = url.contains(
        Regex("""(?i)\.(?:m3u8|mp4)(?:[?#].*)?$""")
    )

    fun normaliseMediaUrl(value: String): String? {
        val clean = value.trim()
            .replace("&amp;", "&", ignoreCase = true)
            .replace("\\/", "/")
            .trimEnd(',', ';', ')', ']', '"', '\'')
        return when {
            clean.startsWith("https://", ignoreCase = true) || clean.startsWith("http://", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> null
        }
    }
}
