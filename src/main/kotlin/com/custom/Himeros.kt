package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

// =====================================================================
//  LOGGING
// =====================================================================
private const val TAG = "Himeros"

internal fun hLog(msg: String) {
    println("[$TAG] $msg")
}

internal fun hLog(msg: String, e: Throwable) {
    println("[$TAG] $msg -> ${e.javaClass.simpleName}: ${e.message}")
}

// =====================================================================
//  URL UTILITIES
// =====================================================================
internal object UrlUtils {

    /** "//host/x" -> "https://host/x"; "http://" left alone; trims junk. */
    fun fixProtocol(url: String): String {
        val u = url.trim().replace("\\/", "/")
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") || u.startsWith("https://") -> u
            else -> u
        }
    }

    /** "https://a.b.com/e/xyz?q=1" -> "https://a.b.com" */
    fun origin(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: url

    /** "https://a.b.com/e/xyz" -> "https://a.b.com/" */
    fun originSlash(url: String): String = origin(url) + "/"

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// =====================================================================
//  DEAN EDWARDS P.A.C.K.E.R UNPACKER (self-contained, base-62 capable)
// =====================================================================
internal object JsPacker {

    private val PACKED_SINGLE = Regex(
        """\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\s*\.\s*split\s*\(\s*'\|'\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val PACKED_DOUBLE = Regex(
        """\}\s*\(\s*"(.*?)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*"(.*?)"\s*\.\s*split\s*\(\s*"\|"\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun looksPacked(source: String): Boolean =
        source.contains("eval(function(p,a,c,k,e,")

    /**
     * Unpacks every packed block found in [source] and returns them joined.
     * Returns "" when nothing could be unpacked.
     */
    fun unpackAll(source: String): String {
        val out = StringBuilder()
        for (regex in listOf(PACKED_SINGLE, PACKED_DOUBLE)) {
            for (m in regex.findAll(source)) {
                val decoded = try {
                    decode(
                        payload = unescape(m.groupValues[1]),
                        base = m.groupValues[2].toIntOrNull() ?: continue,
                        count = m.groupValues[3].toIntOrNull() ?: continue,
                        keys = unescape(m.groupValues[4]).split("|")
                    )
                } catch (e: Exception) {
                    hLog("unpack failed", e); null
                }
                if (!decoded.isNullOrBlank()) out.append(decoded).append('\n')
            }
            if (out.isNotEmpty()) break
        }
        return out.toString()
    }

    /** Unpacks if packed, otherwise returns the original text. Never throws. */
    fun unpackOrOriginal(source: String): String {
        if (!looksPacked(source)) return source
        val un = unpackAll(source)
        return if (un.isBlank()) source else un
    }

    private fun unescape(s: String): String = s
        .replace("\\\\", "\u0000")
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\u0000", "\\")

    /** Mirrors the packer's own base encoder (0-9 a-z A-Z, base up to 62). */
    private fun encodeBase(value: Int, base: Int): String {
        fun digit(d: Int): String =
            if (d > 35) (d + 29).toChar().toString() else d.toString(36)
        return if (value < base) digit(value)
        else encodeBase(value / base, base) + digit(value % base)
    }

    private fun decode(payload: String, base: Int, count: Int, keys: List<String>): String {
        if (base <= 1 || count <= 0) return ""
        val map = HashMap<String, String>(count * 2)
        for (i in 0 until count) {
            val token = encodeBase(i, base)
            val replacement = keys.getOrNull(i)
            map[token] = if (replacement.isNullOrEmpty()) token else replacement
        }
        return Regex("""\b\w+\b""").replace(payload) { m -> map[m.value] ?: m.value }
    }
}

// =====================================================================
//  MEDIA URL FINDER (m3u8 / mp4 / mkv inside html or unpacked js)
// =====================================================================
internal object MediaFinder {

    private val DIRECT = Regex(
        """https?:(?:\\/\\/|//)[^\s"'<>()\[\]]+?\.(?:m3u8|mp4|mkv|webm)(?:\?[^\s"'<>()\[\]]*)?""",
        RegexOption.IGNORE_CASE
    )

    private val KEYED = Regex(
        """["'](?:file|src|source|hls|url|videoUrl|play_url)["']?\s*[:=]\s*["'](https?:[^"']+|//[^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    private val BLOCKED = listOf(
        "test-videos.co.uk", "/sample", "sample.mp4", "googletagmanager",
        "google-analytics", "doubleclick", "/ads/", "advert", "favicon",
        "nitroflare", "rapidgator", "k2s.cc", "keep2share", "fileboom",
        "fboom.me", "tezfiles", "depositfiles", "turbobit", "uploadgig",
        "alfafile", "katfile", "mexashare", "subyshare", "rapidrar"
    )

    fun find(text: String): List<String> {
        val raw = LinkedHashSet<String>()

        DIRECT.findAll(text).forEach { raw.add(it.value) }

        KEYED.findAll(text).forEach { m ->
            val v = m.groupValues[1]
            if (v.contains(".m3u8", true) || v.contains(".mp4", true) ||
                v.contains(".mkv", true) || v.contains("/hls", true)
            ) raw.add(v)
        }

        return raw
            .map { UrlUtils.fixProtocol(it) }
            .filter { url ->
                url.startsWith("http") && BLOCKED.none { url.contains(it, ignoreCase = true) }
            }
            .distinct()
    }

    fun isM3u8(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) || url.contains("/hls", ignoreCase = true)
}

// =====================================================================
//  QUALITY PARSER
// =====================================================================
internal object QualityParser {

    /** Returns quality resolution as plain Int (compatible across all Cloudstream versions) */
    fun fromText(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val t = text.lowercase()
        return when {
            t.contains("2160") || t.contains("4k") || t.contains("uhd") -> 2160
            t.contains("1440") -> 1440
            t.contains("1080") -> 1080
            t.contains("720") -> 720
            t.contains("480") -> 480
            t.contains("360") -> 360
            t.contains("240") -> 240
            else -> 0
        }
    }
}

// =====================================================================
//  HIMEROS MAIN PROVIDER (SpeedPorn Full Movie Streamer)
// =====================================================================
class Himeros : MainAPI() {

    override var mainUrl = "https://speedporn.net"
    override var name = "Himeros"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    private val HOST_MARKERS = listOf(
        "lulustream", "luluvid", "luluvdo",
        "mixdrop", "mxdrop",
        "streamtape", "streamta", "strtape",
        "doodstream", "dood.", "ds2play", "playmogo", "d0000d", "d000d",
        "voe.sx", "voe-unblock", "streamwish", "filelions", "filemoon",
        "vidhide", "dropload", "vidoza", "streamcloud", "upstream"
    )

    private val JUNK_MARKERS = listOf(
        "google", "facebook", "twitter", "whatsapp", "telegram",
        "disqus", "histats", "counter", "wp-content/themes",
        "wp-includes", ".jpg", ".jpeg", ".png", ".gif", ".webp",
        ".css", ".js", ".svg", ".ico"
    )

    private val ITEM_SELECTOR = listOf(
        "article.post", "div.post", "article.item", "div.item",
        ".item-video", "div.video-item", ".video-block", "div.thumb",
        "article", "div.thumb-block"
    ).joinToString(", ")

    // -----------------------------------------------------------------
    // MAIN PAGE CATALOGS
    // -----------------------------------------------------------------
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "category/movies" to "Full Movies",
        "category/hd-porn" to "HD 1080p",
        "category/4k-uhd" to "4K UHD",
        "category/vr-porn" to "VR Porn",
        "trending" to "Trending Now"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = when {
            page <= 1 && path.isEmpty() -> "$mainUrl/"
            page <= 1 -> "$mainUrl/$path/"
            path.isEmpty() -> "$mainUrl/page/$page/"
            else -> "$mainUrl/$path/page/$page/"
        }

        val doc = getPage(url)
        val items = doc.select(ITEM_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = items.size >= 10
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    // -----------------------------------------------------------------
    // SEARCH
    // -----------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val primaryUrl = "$mainUrl/?s=${UrlUtils.encode(cleanQuery)}"

        val primaryDoc = runCatching { getPage(primaryUrl) }.getOrNull()
        val primaryResults = primaryDoc?.select(ITEM_SELECTOR)
            ?.mapNotNull { it.toSearchResult() }
            ?.distinctBy { it.url }
            .orEmpty()

        if (primaryResults.isNotEmpty()) return primaryResults

        // Fallback variants if exact query gave 0 results
        for (variant in searchVariants(cleanQuery)) {
            if (variant.equals(cleanQuery, ignoreCase = true)) continue
            val url = "$mainUrl/?s=${UrlUtils.encode(variant)}"
            val doc = runCatching { getPage(url) }.getOrNull() ?: continue
            val results = doc.select(ITEM_SELECTOR)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    // -----------------------------------------------------------------
    // LOAD MOVIE DETAILS
    // -----------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = getPage(url)

        val title = (doc.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim()
            ?: "SpeedPorn Video").stripSiteSuffix()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("div.no-thumb img, article img, div.entry-content img, img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.ifBlank { null }

        val plot = doc.selectFirst("div.description, div.entry-content p, div.synopsis, p")
            ?.text()?.trim()?.ifBlank { null }

        val tags = doc.select("a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val actors = doc.select("a[href*='/pornstar/'], a[href*='/actor/'], a[href*='/model/']")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            .map { ActorData(Actor(it, null)) }

        val recommendations = doc.select(ITEM_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let { UrlUtils.fixProtocol(it) }
            this.posterHeaders = defaultHeaders
            this.plot = plot
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    // -----------------------------------------------------------------
    // LOAD LINKS (HIGH-SPEED STREAM EXTRACTION)
    // -----------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val doc = getPage(data)
        val rawHtml = doc.html()

        val emitted = AtomicInteger(0)
        val report: (ExtractorLink) -> Unit = { link ->
            callback(link)
            emitted.incrementAndGet()
        }

        // 1. Instant Fast-Path: Direct media found directly on the page
        MediaFinder.find(rawHtml).forEach { streamUrl ->
            emit(
                callback = report,
                sourceName = name,
                displayName = "SpeedPorn Direct",
                url = streamUrl,
                type = if (MediaFinder.isM3u8(streamUrl)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                referer = "$mainUrl/",
                quality = QualityParser.fromText(streamUrl)
            )
        }

        // 2. Discover all embed host candidate URLs
        val candidates = LinkedHashSet<String>()

        doc.select("a[href]").forEach { a ->
            val h = a.attr("href").trim()
            if (h.isNotEmpty() && !h.startsWith("#") && !h.startsWith("javascript")) candidates.add(h)
        }

        doc.select("iframe").forEach { f ->
            listOf("src", "data-src", "data-lazy-src").forEach { attr ->
                f.attr(attr).trim().takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            }
        }

        doc.select("[data-url],[data-src],[data-href],[data-embed],[data-fl-source],[data-fl-url],[data-player]")
            .forEach { el ->
                listOf(
                    "data-fl-source", "data-fl-url", "data-url",
                    "data-src", "data-href", "data-embed", "data-player"
                ).forEach { attr ->
                    val u = el.attr(attr).trim()
                    if (u.isNotEmpty()) candidates.add(u)
                }
            }

        Regex(
            """https?:(?://|\\/\\/)[^\s"'<>\\]*(?:""" +
                    HOST_MARKERS.joinToString("|") { Regex.escape(it) } +
                    """)[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).findAll(rawHtml).forEach { candidates.add(it.value) }

        val embedUrls = candidates
            .map { UrlUtils.fixProtocol(it) }
            .filter { u ->
                u.startsWith("http") &&
                        JUNK_MARKERS.none { u.contains(it, true) } &&
                        HOST_MARKERS.any { u.contains(it, true) }
            }
            .map { normalizeEmbed(it) }
            .distinct()

        // 3. Launch all built-in extractors in parallel
        val extractorJobs = embedUrls.map { url ->
            async {
                try {
                    loadExtractor(url, "$mainUrl/", subtitleCallback, report)
                } catch (e: Exception) {
                    hLog("loadExtractor failed for $url", e)
                }
            }
        }
        extractorJobs.awaitAll()

        // 4. Custom fallbacks ONLY if built-ins produced nothing for those hosts
        if (emitted.get() == 0) {
            val fallbacks = embedUrls.filter { u ->
                listOf("lulustream", "luluvid", "luluvdo").any { u.contains(it, true) }
            }.take(2).map { async { resolveLulu(it, report) } } +
                    embedUrls.filter { u ->
                        u.contains("mixdrop", true) || u.contains("mxdrop", true)
                    }.take(2).map { async { resolveMixDrop(it, report) } }
            fallbacks.awaitAll()
        }

        emitted.get() > 0
    }

    // -----------------------------------------------------------------
    // FALLBACK RESOLVERS
    // -----------------------------------------------------------------
    private suspend fun resolveLulu(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val code = Regex("""/(?:e|f|d)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: return false

        val mirrors = (listOf(embedUrl) + listOf(
            "https://lulustream.com/e/$code",
            "https://luluvdo.com/e/$code",
            "https://luluvid.com/e/$code"
        )).distinct()

        for (mirror in mirrors) {
            val text = getTextOrNull(mirror, "$mainUrl/") ?: continue
            if (text.length < 500) continue

            val searchable = JsPacker.unpackOrOriginal(text) + "\n" + text
            val stream = MediaFinder.find(searchable).firstOrNull() ?: continue

            val origin = UrlUtils.origin(mirror)
            emit(
                callback = callback,
                sourceName = name,
                displayName = "LuluStream",
                url = stream,
                type = if (MediaFinder.isM3u8(stream)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                referer = "$origin/",
                quality = QualityParser.fromText(stream),
                extraHeaders = mapOf("Origin" to origin)
            )
            return true
        }
        return false
    }

    private suspend fun resolveMixDrop(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val code = Regex("""/(?:e|f)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: return false

        val mirrors = (listOf(embedUrl) + listOf(
            "https://mixdrop.ag/e/$code",
            "https://mixdrop.my/e/$code",
            "https://mixdrop.co/e/$code",
            "https://mixdrop.sx/e/$code",
            "https://mxdrop.to/e/$code"
        )).distinct()

        val wurlRegex = Regex("""(?:MDCore\.)?wurl\s*[:=]\s*["']([^"']+)["']""")

        for (mirror in mirrors) {
            val text = getTextOrNull(mirror, "$mainUrl/") ?: continue
            if (text.length < 500) continue

            val unpacked = JsPacker.unpackOrOriginal(text)
            val wurl = wurlRegex.find(unpacked)?.groupValues?.get(1)
                ?: wurlRegex.find(text)?.groupValues?.get(1)
                ?: continue
            if (wurl.isBlank()) continue

            val origin = UrlUtils.origin(mirror)
            emit(
                callback = callback,
                sourceName = name,
                displayName = "MixDrop",
                url = UrlUtils.fixProtocol(wurl),
                type = ExtractorLinkType.VIDEO,
                referer = "$origin/",
                quality = 0
            )
            return true
        }
        return false
    }

    // -----------------------------------------------------------------
    // HELPER METHODS
    // -----------------------------------------------------------------
    private fun normalizeEmbed(url: String): String {
        var clean = UrlUtils.fixProtocol(url).substringBefore("#").trim()

        fun codeAfter(marker: String): String =
            clean.substringAfter(marker)
                .substringBefore("?").substringBefore("&").substringBefore("/")

        val doodHosts = listOf(
            "playmogo.com", "doodstream.com", "dood.to", "dood.li",
            "dood.ws", "dood.yt", "ds2play.com", "d0000d.com", "d000d.com"
        )
        if (doodHosts.any { clean.contains(it, true) }) {
            val c = when {
                clean.contains("/e/") -> codeAfter("/e/")
                clean.contains("/d/") -> codeAfter("/d/")
                else -> ""
            }
            if (c.isNotBlank()) return "https://d0000d.com/e/$c"
            return clean
        }

        if (Regex("""m[ix]{1,2}drop\.\w+/f/""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return clean.replace("/f/", "/e/")
        }

        if (Regex(
                """(?:streamwish|filelions|lulustream|luluvid|luluvdo|vidhide|filemoon)\.\w+/f/""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(clean)
        ) return clean.replace("/f/", "/e/")

        return clean
    }

    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        sourceName: String,
        displayName: String,
        url: String,
        type: ExtractorLinkType,
        referer: String,
        quality: Int,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val qLabel = if (quality > 0) "${quality}p" else "Direct"
        val isM3u8 = type == ExtractorLinkType.M3U8 || url.contains(".m3u8")

        val link = ExtractorLink(
            source = sourceName,
            name = "$displayName ($qLabel)",
            url = url,
            referer = referer,
            quality = if (quality > 0) quality else Qualities.P1080.value,
            isM3u8 = isM3u8,
            headers = defaultHeaders + extraHeaders
        )
        callback(link)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst(
            "a.infos, a.thumb, a[title], h2 a, h3 a, a[href*='speedporn.net/']"
        ) ?: return null

        val href = fixUrl(linkEl.attr("href"))
        if (href.isBlank() || href == "$mainUrl/" || href.startsWith("#") ||
            href.contains("/category/") || href.contains("/tag/") || href.contains("cdn-cgi")
        ) return null

        val rawTitle = selectFirst("span.title, a.infos, h2, h3, .title")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { linkEl.text().trim() }
        val title = rawTitle.stripSiteSuffix()
        if (title.isBlank()) return null

        val poster = selectFirst("div.no-thumb img, img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.let { if (it.isBlank()) null else UrlUtils.fixProtocol(it) }

        return newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
        }
    }

    private fun searchVariants(title: String): List<String> {
        val variants = mutableListOf<String>()
        val clean = cleanTitle(title)
        if (clean.isNotEmpty()) variants.add(clean)

        val stripped = clean.replace(Regex("""[-:_/]+"""), " ").trim()
        if (stripped.isNotEmpty() && stripped != clean) variants.add(stripped)

        val noVolume = stripped
            .replace(Regex("""(?i)\b(?:Vol\.?|Volume|Episode|Ep\.?|Part|No\.?|#)\s*\d+"""), "")
            .trim()
        if (noVolume.isNotEmpty() && noVolume != stripped) variants.add(noVolume)

        return variants.distinct()
    }

    private fun cleanTitle(title: String): String =
        title.replace(Regex("""\[.*?\]|\(.*?\)|1080p|720p|4k|hd|web-dl|bluray""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.stripSiteSuffix(): String =
        this.replace(Regex("""(?i)\s*[-|–—]\s*(?:SpeedPorn|Speed\s*Porn|Free\s*Porn\s*Videos).*$"""), "").trim()

    private suspend fun getPage(url: String): Document =
        app.get(url, headers = defaultHeaders).document

    private suspend fun getTextOrNull(url: String, referer: String): String? =
        runCatching {
            app.get(url, headers = defaultHeaders + mapOf("Referer" to referer)).text
        }.getOrNull()
}
