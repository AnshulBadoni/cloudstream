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
    e.printStackTrace()
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
            u.startsWith("magnet:") -> u
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
        // single pass over word tokens -> no cascading re-replacement
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
//  TORRENT FINDER
// =====================================================================
internal data class TorrentHit(val url: String, val isMagnet: Boolean)
internal data class SearchEntry(val title: String, val url: String)

internal object TorrentFinder {

    val limeMirrors = listOf(
        "https://www.limetorrents.lol",
        "https://limetorrents.lol",
        "https://www.limetorrents.pro",
        "https://limetorrents.unblockit.click"
    )

    fun limeSearchUrls(mirror: String, query: String): List<String> {
        val dashed = query.trim().replace(Regex("""\s+"""), "-")
        val plussed = query.trim().replace(Regex("""\s+"""), "+")
        return listOf(
            "$mirror/search/all/${UrlUtils.encode(dashed)}/seeds/1/",
            "$mirror/search/all/$plussed/"
        ).distinct()
    }

    /** Rows of the LimeTorrents result table. */
    fun findLimeEntries(doc: Document, baseUrl: String): List<SearchEntry> {
        val out = mutableListOf<SearchEntry>()
        val rows = doc.select("table.table2 tr, table tr")
        for (row in rows) {
            val a = row.select("a[href]").firstOrNull { link ->
                val h = link.attr("href")
                h.contains("-torrent-") || h.endsWith(".html")
            } ?: continue
            val title = a.text().trim()
            if (title.isEmpty()) continue
            val href = absolute(a.attr("href"), baseUrl)
            if (href.isEmpty()) continue
            out.add(SearchEntry(title, href))
        }
        return out.distinctBy { it.url }
    }

    /** Magnet first, then .torrent file links. */
    fun extractTorrent(doc: Document): TorrentHit? {
        doc.selectFirst("a[href^=magnet:]")?.attr("href")?.takeIf { it.isNotBlank() }?.let {
            return TorrentHit(it, true)
        }
        val fileLink = doc.select("a[href]").firstOrNull { a ->
            val h = a.attr("href").lowercase()
            h.endsWith(".torrent") || h.contains("itorrents.org") || h.contains("/download/")
        }?.attr("href")
        if (!fileLink.isNullOrBlank()) return TorrentHit(UrlUtils.fixProtocol(fileLink), false)

        // last resort: magnet written in plain text/script
        Regex("""magnet:\?xt=urn:btih:[A-Za-z0-9]{32,40}[^\s"'<>]*""")
            .find(doc.html())?.value?.let { return TorrentHit(it, true) }

        return null
    }

    /** Secondary pages that sometimes hold the real magnet. */
    fun alternateDetailUrls(doc: Document, baseUrl: String): List<String> =
        doc.select("a[href]")
            .map { it.attr("href") }
            .filter { h ->
                h.contains("-torrent-") || h.contains("/torrent/") ||
                        h.contains("download") || h.endsWith(".html")
            }
            .map { absolute(it, baseUrl) }
            .filter { it.isNotEmpty() && it != baseUrl }
            .distinct()
            .take(3)

    private fun absolute(href: String, baseUrl: String): String {
        val h = href.trim()
        return when {
            h.isEmpty() -> ""
            h.startsWith("http") -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/") -> UrlUtils.origin(baseUrl) + h
            else -> UrlUtils.originSlash(baseUrl) + h
        }
    }
}

// =====================================================================
//  PROVIDER
// =====================================================================
class Himeros : MainAPI() {

    override var mainUrl = "https://speedporn.net"
    override var name = "Himeros"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val HOST_MARKERS = listOf(
            "lulustream", "luluvid", "luluvdo", "streamwish", "filelions", "playmogo",
            "dood", "d0000d", "mixdrop", "mxdrop", "streamtape", "voe.sx", "voe.",
            "playmate", "wolfstream", "dropupload", "vidhide", "filemoon"
        )

        private val JUNK_MARKERS = listOf(
            "google.com", "gstatic", "deleted", "favicon", "theporndude",
            "/ads", "disqus", "facebook", "twitter"
        )

        private val ITEM_SELECTOR =
            "div.video-block, div.thumb, .item, .post, article"
    }

    private val siteHeaders
        get() = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

    // -----------------------------------------------------------------
    // HTTP  (one code path, checks HTTP range for success safely)
    // -----------------------------------------------------------------
    private suspend fun getPage(url: String, referer: String = "$mainUrl/"): Document {
        val res = app.get(url, headers = siteHeaders, referer = referer)
        if (res.code !in 200..299) throw Exception("HTTP ${res.code} for $url")
        return res.document
    }

    private suspend fun getTextOrNull(url: String, referer: String): String? = try {
        val res = app.get(url, headers = siteHeaders, referer = referer)
        if (res.code !in 200..299) {
            hLog("skip $url -> HTTP ${res.code}")
            null
        } else res.text
    } catch (e: Exception) {
        hLog("request failed $url", e); null
    }

    private suspend fun getDocOrNull(url: String, referer: String): Document? = try {
        val res = app.get(url, headers = siteHeaders, referer = referer)
        if (res.code in 200..299) res.document else null
    } catch (e: Exception) {
        hLog("request failed $url", e); null
    }

    // -----------------------------------------------------------------
    // LINK EMISSION
    // -----------------------------------------------------------------
    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        sourceName: String,
        displayName: String,
        url: String,
        type: ExtractorLinkType,
        referer: String,
        quality: Int = 0,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        try {
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = displayName,
                    url = url,
                    type = type
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf("User-Agent" to UA) + extraHeaders
                }
            )
            hLog("emit [$displayName] $url (ref=$referer)")
        } catch (e: Exception) {
            hLog("emit failed for $url", e)
        }
    }

    // -----------------------------------------------------------------
    // MAIN PAGE
    // -----------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/hdmovies/" to "HD Movies",
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/adult/" to "Adult Movies",
        "$mainUrl/free-movies/" to "Full Length Movies",
        "$mainUrl/hdporn/" to "HD Scenes",
        "$mainUrl/search-tags/" to "Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val doc = getPage(url)

        val items = doc.select(ITEM_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = doc.selectFirst(
            "a.next, a.next_page, .pagination a[href*='/page/'], .nav-links a.next"
        ) != null

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    // -----------------------------------------------------------------
    // TITLE HELPERS
    // -----------------------------------------------------------------
    private fun String.stripSiteSuffix(): String = this
        .replace(Regex("""(?i)^\s*Watch\s+"""), "")
        .replace(Regex("""(?i)\s+Porn\s+Online\s+Free\s*$"""), "")
        .trim()

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""&#?\w+;"""), " ")
        t = t.replace(Regex("""\[.*?\]|\(.*?\)|<.*?>"""), " ")
        t = t.replace(
            Regex(
                """(?i)\b(?:Blacked|Evil\s*Angel|Brazzers|Tushy|Vixen|Bang!?|Naughty\s*America|""" +
                        """Sweet\s*Sinner|Wicked|Digital\s*Playground|Jules\s*Jordan|Reality\s*Kings|DDF|Mofos)""" +
                        """(?:\s*\d{2,4})?\b"""
            ), " "
        )
        t = t.replace(
            Regex(
                """(?i)\b(?:\d{3,4}p|4K|2160p|1080p|720p|480p|WEB-?DL|BDRip|DVDRip|HDRip|""" +
                        """x264|x265|HEVC|AAC|MP3|SPLITSCENES|XXX|FULL|HD|VOSTFR|FRENCH)\b"""
            ), " "
        )
        t = t.replace(Regex("""\b(?:19|20)\d{2}\b"""), " ")
        t = t.replace(Regex("""#(\d+)"""), "$1")
        t = t.replace(Regex("""(?i)\.torrent|\.html"""), "")
        t = t.replace(Regex("""[-–—:_/]+"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t.ifEmpty { raw.trim() }
    }

    // ---- roman / arabic normalisation -------------------------------
    private fun parseRoman(s: String): Int? {
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        val up = s.uppercase()
        var total = 0
        var prev = 0
        for (i in up.length - 1 downTo 0) {
            val cur = v[up[i]] ?: return null
            if (cur < prev) total -= cur else total += cur
            prev = cur
        }
        return if (total > 0) total else null
    }

    private fun romanToArabic(input: String): String =
        input.replace(Regex("""(?i)\b([MDCLXVI]+)\b""")) { m ->
            parseRoman(m.groupValues[1])?.toString() ?: m.value
        }

    private fun intToRoman(num: Int): String {
        if (num !in 1..3999) return num.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = num
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) {
            n -= vals[i]; sb.append(syms[i])
        }
        return sb.toString()
    }

    private fun arabicToRoman(input: String): String =
        input.replace(Regex("""\b(\d{1,2})\b""")) { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..99) intToRoman(n) else m.value
        }

    private fun searchVariants(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val cleaned = trimmed
            .replace(Regex("""(?i)\b(?:Vol\.?|Volume|Episode|Ep\.?|Part|No\.?|#)\s*(\d+)"""), "$1")
            .replace(Regex("""(?i)\b(?:Vol\.?|Volume|Episode|Ep\.?|Part|No\.?)\s*([MDCLXVI]+)\b"""), "$1")
            .replace(Regex("""\((?:19\d\d|20\d\d)\)"""), "")
            .replace(Regex("""[-:_/]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return listOf(cleaned, romanToArabic(cleaned), arabicToRoman(cleaned), trimmed)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun tokensOf(text: String, stop: Set<String> = emptySet()): Set<String> =
        text.lowercase()
            .replace(Regex("""[^a-z0-9]"""), " ")
            .split(" ")
            .filter { it.isNotBlank() && it !in stop }
            .toSet()

    // -----------------------------------------------------------------
    // SEARCH RESULT PARSING
    // -----------------------------------------------------------------
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

        return newMovieSearchResponse(cleanTitle(title), href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // -----------------------------------------------------------------
    // SEARCH
    // -----------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        for (variant in searchVariants(query)) {
            val url = "$mainUrl/?s=${UrlUtils.encode(variant)}"
            val doc = try {
                getPage(url)
            } catch (e: Exception) {
                hLog("search failed for '$variant'", e); continue
            }
            val results = doc.select(ITEM_SELECTOR)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    // -----------------------------------------------------------------
    // LOAD
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

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster?.let { UrlUtils.fixProtocol(it) }
            this.plot = plot
            this.tags = tags
            this.actors = actors
        }
    }

    // -----------------------------------------------------------------
    // EMBED URL NORMALISATION
    // -----------------------------------------------------------------
    private fun normalizeEmbed(url: String): String {
        var clean = UrlUtils.fixProtocol(url).substringBefore("#").trim()

        fun codeAfter(marker: String): String =
            clean.substringAfter(marker)
                .substringBefore("?").substringBefore("&").substringBefore("/")

        // DoodStream family (playmogo is a Dood rebrand -> same backend)
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

        // MixDrop: /f/ -> /e/
        if (Regex("""m[ix]{1,2}drop\.\w+/f/""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return clean.replace("/f/", "/e/")
        }

        // Wish forks: /f/ -> /e/
        if (Regex(
                """(?:streamwish|filelions|lulustream|luluvid|luluvdo|vidhide|filemoon)\.\w+/f/""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(clean)
        ) return clean.replace("/f/", "/e/")

        return clean
    }

    // -----------------------------------------------------------------
    // FALLBACK RESOLVER: Lulu family
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
        hLog("lulu: nothing found for $embedUrl")
        return false
    }

    // -----------------------------------------------------------------
    // FALLBACK RESOLVER: MixDrop
    // -----------------------------------------------------------------
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
        hLog("mixdrop: nothing found for $embedUrl")
        return false
    }

    // -----------------------------------------------------------------
    // LOAD LINKS
    // -----------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getPage(data)
        val rawHtml = doc.html()

        val emitted = AtomicInteger(0)
        val report: (ExtractorLink) -> Unit = { link ->
            callback(link)
            emitted.incrementAndGet()
        }

        val movieTitle = doc.selectFirst("h1.title, h1.entry-title, h1")
            ?.text()?.trim()?.stripSiteSuffix().orEmpty()

        // ---------- collect candidates --------------------------------
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

        // ---------- filter + normalise ---------------------------------
        val embedUrls = candidates
            .map { UrlUtils.fixProtocol(it) }
            .filter { u ->
                u.startsWith("http") &&
                        JUNK_MARKERS.none { u.contains(it, true) } &&
                        HOST_MARKERS.any { u.contains(it, true) }
            }
            .map { normalizeEmbed(it) }
            .distinct()

        hLog("found ${embedUrls.size} embed candidates for $data")

        coroutineScope {
            // 1) Built-in extractors first (maintained upstream)
            val extractorJobs = embedUrls.map { url ->
                async {
                    try {
                        loadExtractor(url, "$mainUrl/", subtitleCallback, report)
                    } catch (e: Exception) {
                        hLog("loadExtractor failed $url", e)
                    }
                }
            }

            // 2) Direct media found on the page itself
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

            // 3) Torrents in parallel
            val torrentJobs = if (movieTitle.isNotEmpty()) listOf(
                async { resolvePornoTorrent(movieTitle, report) },
                async { resolveLimeTorrents(movieTitle, report) }
            ) else emptyList()

            extractorJobs.awaitAll()

            // 4) Custom fallbacks ONLY if the built-ins produced nothing
            if (emitted.get() == 0) {
                hLog("built-in extractors produced nothing, trying custom resolvers")
                val fallbacks =
                    embedUrls.filter { u ->
                        listOf("lulustream", "luluvid", "luluvdo").any { u.contains(it, true) }
                    }.take(2).map { async { resolveLulu(it, report) } } +
                            embedUrls.filter { u ->
                                u.contains("mixdrop", true) || u.contains("mxdrop", true)
                            }.take(2).map { async { resolveMixDrop(it, report) } }
                fallbacks.awaitAll()
            }

            torrentJobs.awaitAll()
        }

        hLog("loadLinks emitted ${emitted.get()} link(s)")
        return emitted.get() > 0
    }

    // -----------------------------------------------------------------
    // TORRENT EMISSION
    // -----------------------------------------------------------------
    private suspend fun emitTorrent(
        callback: (ExtractorLink) -> Unit,
        sourceName: String,
        rawTitle: String,
        hit: TorrentHit,
        referer: String
    ) {
        val q = QualityParser.fromText(rawTitle)
        val finalQuality = if (q > 0) q else Qualities.P1080.value
        emit(
            callback = callback,
            sourceName = sourceName,
            displayName = "$sourceName [${cleanTitle(rawTitle)}]",
            url = hit.url,
            type = ExtractorLinkType.TORRENT,
            referer = referer,
            quality = finalQuality
        )
    }

    // -----------------------------------------------------------------
    // PORNOTORRENT
    // -----------------------------------------------------------------
    private suspend fun resolvePornoTorrent(title: String, callback: (ExtractorLink) -> Unit) {
        try {
            val num = Regex("""\b(\d+)\b""").find(title)?.groupValues?.get(1)
            val baseName = title
                .replace(Regex("""(?i)\b(?:Vol\.?|Volume|Episode|Ep\.?|Part|No\.?|#)?\s*\d+"""), "")
                .replace(Regex("""[-:_/]+"""), " ")
                .trim()

            val queries = mutableListOf<String>()
            if (num != null && baseName.isNotEmpty()) {
                queries.add("$baseName #$num")
                queries.add("$baseName $num")
            }
            queries.addAll(searchVariants(title))
            queries.add(title)
            if (baseName.isNotEmpty()) queries.add(baseName)

            val wanted = tokensOf(title, setOf("vol", "volume"))

            for (q in queries.distinct()) {
                val searchUrl = "https://pornotorrent.com.br/?s=${UrlUtils.encode(q)}"
                val doc = getDocOrNull(searchUrl, "https://pornotorrent.com.br/") ?: continue

                for (article in doc.select("article, .post, div.item, .cp-card")) {
                    val a = article.selectFirst(
                        "h1 a, h2 a, h3 a, a[title], .entry-title a, .cp-card__title a, a.cp-card__link"
                    ) ?: continue

                    val postTitle = a.attr("title")
                        .ifBlank { a.attr("aria-label") }
                        .ifBlank { a.text().trim() }
                    val postHref = fixUrl(a.attr("href"))
                    if (postTitle.isBlank() || postHref.isBlank()) continue
                    if (wanted.isNotEmpty() && !wanted.all(tokensOf(postTitle)::contains)) continue

                    val postDoc = getDocOrNull(postHref, "https://pornotorrent.com.br/") ?: continue
                    var hit = TorrentFinder.extractTorrent(postDoc)

                    if (hit == null) {
                        for (alt in TorrentFinder.alternateDetailUrls(postDoc, postHref)) {
                            val altDoc = getDocOrNull(alt, postHref) ?: continue
                            hit = TorrentFinder.extractTorrent(altDoc)
                            if (hit != null) break
                        }
                    }

                    if (hit != null) {
                        emitTorrent(callback, "PornoTorrent", postTitle, hit, "https://pornotorrent.com.br/")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            hLog("pornotorrent resolver crashed", e)
        }
    }

    // -----------------------------------------------------------------
    // LIMETORRENTS
    // -----------------------------------------------------------------
    private suspend fun resolveLimeTorrents(title: String, callback: (ExtractorLink) -> Unit) {
        try {
            val wanted = tokensOf(title, setOf("the", "a", "an", "vol", "volume"))
                .filter { it.length > 1 && !it.matches(Regex("""(?:19|20)\d{2}""")) }
                .toSet()

            for (query in searchVariants(title).ifEmpty { listOf(title) }) {
                for (mirror in TorrentFinder.limeMirrors) {
                    for (searchUrl in TorrentFinder.limeSearchUrls(mirror, query)) {
                        val searchDoc = getDocOrNull(searchUrl, "$mirror/") ?: continue

                        val entries = TorrentFinder.findLimeEntries(searchDoc, mirror)
                            .filter { e -> wanted.isEmpty() || wanted.all(tokensOf(e.title)::contains) }

                        for (entry in entries.take(5)) {
                            val detail = getDocOrNull(entry.url, "$mirror/") ?: continue
                            var hit = TorrentFinder.extractTorrent(detail)

                            if (hit == null) {
                                for (alt in TorrentFinder.alternateDetailUrls(detail, entry.url)) {
                                    val altDoc = getDocOrNull(alt, entry.url) ?: continue
                                    hit = TorrentFinder.extractTorrent(altDoc)
                                    if (hit != null) break
                                }
                            }

                            if (hit != null) {
                                emitTorrent(callback, "LimeTorrents", entry.title, hit, "$mirror/")
                                return
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            hLog("limetorrents resolver crashed", e)
        }
    }
}
