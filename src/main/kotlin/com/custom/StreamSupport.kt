package com.custom

/** Small, site-agnostic helpers for direct media URLs embedded in player HTML. */
object StreamSupport {
    private val nonStreamDomains = listOf(
        "nitroflare", "rapidgator", "frdl", "dropupload", "turbobit", "katfile",
        "filefactory", "uploaded", "k2s.", "keep2share", "mexashare", "tezfiles",
        "fastclick", "alfafile", "ddownload", "rosefile", "uploadgig", "daofile",
        "userscloud", "filespace", "hexload", "depositfiles", "filedot", "worldbytez",
        "deleted", "speedporn.net", "theporndude", "google.com", "porntorrent.com.br", "limetorrents"
    )

    private val mediaUrlPattern = Regex(
        """(?i)((?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)"""
    )
    private val playerValuePattern = Regex(
        """(?i)(?:file|source|src|hls|video_url)\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4)[^"']*)["']"""
    )

    fun extractMediaUrls(raw: String): List<String> {
        val urls = LinkedHashSet<String>()
        val normalized = raw
            .replace("&amp;", "&", ignoreCase = true)
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)

        playerValuePattern.findAll(normalized).forEach { match ->
            normaliseMediaUrl(match.groupValues[1])?.let { u ->
                if (isValidStreamUrl(u)) urls.add(u)
            }
        }
        mediaUrlPattern.findAll(normalized).forEach { match ->
            normaliseMediaUrl(match.groupValues[1])?.let { u ->
                if (isValidStreamUrl(u)) urls.add(u)
            }
        }
        return urls.toList()
    }

    private fun isValidStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return !nonStreamDomains.any { lower.contains(it) }
    }

    fun isDirectMediaUrl(url: String): Boolean {
        if (!isValidStreamUrl(url)) return false
        return url.contains(
            Regex("""(?i)\.(?:m3u8|mp4)(?:[?#].*)?$""")
        )
    }

    fun normaliseMediaUrl(value: String): String? {
        var clean = value.trim()
            .replace("&amp;", "&", ignoreCase = true)
            .replace("\\/", "/")
            .trimEnd(',', ';', ')', ']', '"', '\'')
        if (clean.startsWith("[")) {
            val endBracket = clean.indexOf(']')
            if (endBracket >= 0 && endBracket < clean.length - 1) {
                clean = clean.substring(endBracket + 1).trim()
            }
        }
        val httpIdx = clean.indexOf("http://").takeIf { it >= 0 }
            ?: clean.indexOf("https://").takeIf { it >= 0 }
            ?: clean.indexOf("//").takeIf { it >= 0 }
        val candidate = if (httpIdx != null && httpIdx >= 0) clean.substring(httpIdx) else clean
        return when {
            candidate.startsWith("https://", ignoreCase = true) || candidate.startsWith("http://", ignoreCase = true) -> candidate
            candidate.startsWith("//") -> "https:$candidate"
            else -> null
        }
    }
}
