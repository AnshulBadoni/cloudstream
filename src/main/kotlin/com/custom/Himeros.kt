package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

// =====================================================================
//  URL UTILITIES
// =====================================================================
internal object UrlUtils {
    fun fixProtocol(url: String): String {
        val u = url.trim().replace("\\/", "/")
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("magnet:") -> u
            else -> u
        }
    }

    fun origin(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: url

    fun originSlash(url: String): String = origin(url) + "/"

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// =====================================================================
//  DEAN EDWARDS P.A.C.K.E.R UNPACKER
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

    fun unpackOrOriginal(source: String): String {
        if (!source.contains("eval(function(p,a,c,k,e,")) return source
        for (regex in listOf(PACKED_SINGLE, PACKED_DOUBLE)) {
            val m = regex.find(source) ?: continue
            val decoded = try {
                decode(
                    unescape(m.groupValues[1]),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                    unescape(m.groupValues[4]).split("|")
                )
            } catch (_: Exception) {
                ""
            }
            if (decoded.isNotBlank()) return decoded
        }
        return source
    }

    private fun unescape(s: String): String = s
        .replace("\\\\", "\u0000")
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\u0000", "\\")

    private fun encodeBase(value: Int, base: Int): String {
        fun digit(d: Int): String = if (d > 35) (d + 29).toChar().toString() else d.toString(36)
        return if (value < base) digit(value) else encodeBase(value / base, base) + digit(value % base)
    }

    private fun decode(payload: String, base: Int, count: Int, keys: List<String>): String {
        if (base <= 1 || count <= 0) return ""
        val map = HashMap<String, String>(count * 2)
        for (i in 0 until count) {
            val token = encodeBase(i, base)
            val rep = keys.getOrNull(i)
            map[token] = if (rep.isNullOrEmpty()) token else rep
        }
        return Regex("""\b\w+\b""").replace(payload) { m -> map[m.value] ?: m.value }
    }
}

// =====================================================================
//  MEDIA URL FINDER
// =====================================================================
internal object MediaFinder {
    private val DIRECT = Regex(
        """https?:(?:\\/\\/|//)[^\s"'<>()\[\]]+?\.(?:m3u8|mp4|mkv|webm)(?:\?[^\s"'<>()\[\]]*)?""",
        RegexOption.IGNORE_CASE
    )

    private val BLOCKED = listOf(
        "test-videos.co.uk",
        "/sample",
        "sample.mp4",
        "googletagmanager",
        "google-analytics",
        "doubleclick",
        "/ads/",
        "favicon"
    )

    fun find(text: String): List<String> {
        val raw = LinkedHashSet<String>()
        DIRECT.findAll(text).forEach { raw.add(it.value) }
        return raw.map { UrlUtils.fixProtocol(it) }
            .filter { url -> url.startsWith("http") && BLOCKED.none { url.contains(it, true) } }
            .distinct()
    }

    fun isM3u8(url: String): Boolean =
        url.contains(".m3u8", true) || url.contains("/hls", true)
}

// =====================================================================
//  QUALITY PARSER
// =====================================================================
internal object QualityParser {
    fun fromText(text: String?): Int {
        if (text.isNullOrBlank()) return Qualities.Unknown.value
        val t = text.lowercase()
        return when {
            t.contains("2160") || t.contains("4k") -> Qualities.P2160.value
            t.contains("1440") -> Qualities.P1440.value
            t.contains("1080") -> Qualities.P1080.value
            t.contains("720") -> Qualities.P720.value
            t.contains("480") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}

// =====================================================================
//  TORRENT FINDER
// =====================================================================
internal data class TorrentHit(val url: String)
internal data class SearchEntry(val title: String, val url: String)

internal object TorrentFinder {
    val limeMirrors = listOf("https://www.limetorrents.lol", "https://limetorrents.lol", "https://limetorrents.pro")

    fun limeSearchUrls(mirror: String, query: String): List<String> {
        val plussed = query.trim().replace(Regex("""\s+"""), "+")
        return listOf("$mirror/search/all/$plussed/")
    }

    fun findLimeEntries(doc: Document, baseUrl: String): List<SearchEntry> {
        val out = mutableListOf<SearchEntry>()
        for (row in doc.select("table.table2 tr, table tr")) {
            val a = row.select("a[href]").firstOrNull { h ->
                val href = h.attr("href")
                href.contains("-torrent-") || href.endsWith(".html")
            } ?: continue
            val title = a.text().trim()
            if (title.isEmpty()) continue
            val href = absolute(a.attr("href"), baseUrl)
            if (href.isNotEmpty()) out.add(SearchEntry(title, href))
        }
        return out.distinctBy { it.url }
    }

    fun extractTorrent(doc: Document): TorrentHit? {
        doc.selectFirst("a[href^=magnet:]")?.attr("href")?.takeIf { it.isNotBlank() }?.let {
            return TorrentHit(it)
        }
        val fileLink = doc.select("a[href]").firstOrNull { a ->
            val h = a.attr("href").lowercase()
            h.endsWith(".torrent") || h.contains("itorrents.org") || h.contains("/download/")
        }?.attr("href")
        if (!fileLink.isNullOrBlank()) return TorrentHit(UrlUtils.fixProtocol(fileLink))

        Regex("""magnet:\?xt=urn:btih:[A-Za-z0-9]{32,40}[^\s"'<>]*""")
            .find(doc.html())?.value?.let { return TorrentHit(it) }

        return NoneHandler.hit()
    }

    private object NoneHandler {
        fun hit(): TorrentHit? = null
    }

    fun alternateDetailUrls(doc: Document, baseUrl: String): List<String> =
        doc.select("a[href]").map { it.attr("href") }
            .filter { h -> h.contains("-torrent-") || h.contains("/torrent/") || h.endsWith(".html") }
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
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val HOST_MARKERS = listOf(
            "lulustream",
            "luluvid",
            "luluvdo",
            "streamwish",
            "filelions",
            "playmogo",
            "dood",
            "d0000d",
            "mixdrop",
            "mxdrop",
            "streamtape",
            "voe.sx",
            "vidhide",
            "filemoon"
        )
        private val JUNK_MARKERS =
            listOf("google.com", "gstatic", "deleted", "favicon", "theporndude", "/ads", "disqus")
    }

    private val siteHeaders
        get() = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

    private suspend fun getDoc(url: String, referer: String = "$mainUrl/"): Document {
        return app.get(url, headers = siteHeaders, referer = referer).document
    }

    private suspend fun getDocOrNull(url: String, referer: String): Document? = try {
        app.get(url, headers = siteHeaders, referer = referer).document
    } catch (_: Exception) {
        null
    }

    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        sourceName: String,
        displayName: String,
        url: String,
        referer: String,
        quality: Int = Qualities.Unknown.value,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        try {
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = displayName,
                    url = url,
                    type = if (MediaFinder.isM3u8(url)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf("User-Agent" to UA) + extraHeaders
                }
            )
        } catch (_: Exception) {
        }
    }

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
        val doc = getDoc(url)

        val items = doc.select("div.video-block, div.thumb, .item, .post, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = doc.selectFirst("a.next, .pagination a[href*='/page/'], .nav-links a.next") != null
        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun String.stripSiteSuffix(): String = this
        .replace(Regex("""(?i)^\s*Watch\s+"""), "")
        .replace(Regex("""(?i)\s+Porn\s+Online\s+Free\s*$"""), "")
        .trim()

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("""&#?\w+;"""), " ")
        t = t.replace(Regex("""\[.*?\]|\(.*?\)|<.*?>"""), " ")
        t = t.replace(
            Regex("""(?i)\b(?:Blacked|Evil\s*Angel|Brazzers|Tushy|Vixen|Bang!?|Naughty\s*America|Sweet\s*Sinner|Wicked|Digital\s*Playground|Jules\s*Jordan|Reality\s*Kings|DDF|Mofos)(?:\s*\d{2,4})?\b"""),
            " "
        )
        t = t.replace(
            Regex("""(?i)\b(?:\d{3,4}p|4K|2160p|1080p|720p|480p|WEB-?DL|BDRip|DVDRip|HDRip|x264|x265|HEVC|AAC|MP3|XXX|FULL|HD|VOSTFR|FRENCH)\b"""),
            " "
        )
        t = t.replace(Regex("""\b(?:19|20)\d{2}\b"""), " ")
        t = t.replace(Regex("""[-–—:_/]+"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim().ifEmpty { raw.trim() }
    }

    private fun parseRoman(s: String): Int? {
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        val up = s.uppercase()
        for (i in up.length - 1 downTo 0) {
            val cur = v[up[i]] ?: return null
            if (cur < prev) total -= cur else total += cur
            prev = cur
        }
        return if (total > 0) total else null
    }

    private fun romanToArabic(input: String): String =
        input.replace(Regex("""(?i)\b([MDCLXVI]+)\b""")) { m -> parseRoman(m.groupValues[1])?.toString() ?: m.value }

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
            m.groupValues[1].toIntOrNull()?.let { if (it in 1..99) intToRoman(it) else null } ?: m.value
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
        return listOf(cleaned, romanToArabic(cleaned), arabicToRoman(cleaned), trimmed).filter { it.isNotBlank() }
            .distinct()
    }

    private fun tokensOf(text: String, stop: Set<String> = emptySet()): Set<String> =
        text.lowercase().replace(Regex("""[^a-z0-9]"""), " ").split(" ").filter { it.isNotBlank() && it !in stop }
            .toSet()

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a.infos, a.thumb, a[title], h2 a, h3 a, a[href*='speedporn.net/']") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (href.isBlank() || href == "$mainUrl/" || href.startsWith("#") || href.contains("/category/") || href.contains(
                "/tag/"
            )
        ) return null

        val rawTitle = selectFirst("span.title, a.infos, h2, h3, .title")?.text()?.trim() ?: linkEl.attr("title")
            .ifBlank { linkEl.text().trim() }
        val title = rawTitle.stripSiteSuffix()
        if (title.isBlank()) return null

        val poster = selectFirst("div.no-thumb img, img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.let { if (it.isBlank()) null else UrlUtils.fixProtocol(it) }

        return newMovieSearchResponse(cleanTitle(title), href, TvType.NSFW) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        for (variant in searchVariants(query)) {
            val url = "$mainUrl/?s=${UrlUtils.encode(variant)}"
            val doc = getDocOrNull(url, "$mainUrl/") ?: continue
            val results = doc.select("div.video-block, div.thumb, .item, .post, article")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val title =
            (doc.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim() ?: "SpeedPorn Video").stripSiteSuffix()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("div.no-thumb img, article img, div.entry-content img, img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.ifBlank { null }
        val plot =
            doc.selectFirst("div.description, div.entry-content p, div.synopsis, p")?.text()?.trim()?.ifBlank { null }
        val tags =
            doc.select("a[href*='/tag/'], a[href*='/category/']").map { it.text().trim() }.filter { it.isNotEmpty() }
                .distinct()
        val actors =
            doc.select("a[href*='/pornstar/'], a[href*='/actor/'], a[href*='/model/']").map { it.text().trim() }
                .filter { it.isNotEmpty() }.distinct().map { ActorData(Actor(it, null)) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster?.let { UrlUtils.fixProtocol(it) }
            this.plot = plot
            this.tags = tags
            this.actors = actors
        }
    }

    private fun normalizeEmbed(url: String): String {
        val clean = UrlUtils.fixProtocol(url).substringBefore("#").trim()
        val doodHosts = listOf("playmogo.com", "doodstream.com", "dood.to", "dood.li", "dood.ws", "d0000d.com")
        if (doodHosts.any { clean.contains(it, true) }) {
            val c = when {
                clean.contains("/e/") -> clean.substringAfter("/e/").substringBefore("?").substringBefore("&")
                    .substringBefore("/")

                clean.contains("/d/") -> clean.substringAfter("/d/").substringBefore("?").substringBefore("&")
                    .substringBefore("/")

                else -> ""
            }
            if (c.isNotBlank()) return "https://d0000d.com/e/$c"
        }
        if (Regex("""m[ix]{1,2}drop\.\w+/f/""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return clean.replace("/f/", "/e/")
        }
        if (Regex(
                """(?:streamwish|filelions|lulustream|luluvid|luluvdo|vidhide|filemoon)\.\w+/f/""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(clean)
        ) {
            return clean.replace("/f/", "/e/")
        }
        return clean
    }

    private suspend fun resolveLulu(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val code = Regex("""/(?:e|f|d)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1) ?: return false
        val mirrors = (listOf(embedUrl) + listOf(
            "https://lulustream.com/e/$code",
            "https://luluvdo.com/e/$code",
            "https://luluvid.com/e/$code"
        )).distinct()

        for (mirror in mirrors) {
            val text = getDocOrNull(mirror, "$mainUrl/")?.html() ?: continue
            if (text.length < 500) continue
            val searchable = JsPacker.unpackOrOriginal(text) + "\n" + text
            val stream = MediaFinder.find(searchable).firstOrNull() ?: continue
            val origin = UrlUtils.origin(mirror)
            emit(
                callback,
                name,
                "LuluStream",
                stream,
                "$origin/",
                QualityParser.fromText(stream),
                mapOf("Origin" to origin)
            )
            return true
        }
        return false
    }

    private suspend fun resolveMixDrop(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val code = Regex("""/(?:e|f)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1) ?: return false
        val mirrors = (listOf(embedUrl) + listOf(
            "https://mixdrop.ag/e/$code",
            "https://mixdrop.my/e/$code",
            "https://mixdrop.co/e/$code",
            "https://mxdrop.to/e/$code"
        )).distinct()
        val wurlRegex = Regex("""(?:MDCore\.)?wurl\s*[:=]\s*["']([^"']+)["']""")

        for (mirror in mirrors) {
            val text = getDocOrNull(mirror, "$mainUrl/")?.html() ?: continue
            if (text.length < 500) continue
            val unpacked = JsPacker.unpackOrOriginal(text)
            val wurl =
                wurlRegex.find(unpacked)?.groupValues?.get(1) ?: wurlRegex.find(text)?.groupValues?.get(1) ?: continue
            if (wurl.isBlank()) continue
            val origin = UrlUtils.origin(mirror)
            emit(callback, name, "MixDrop", UrlUtils.fixProtocol(wurl), "$origin/", Qualities.Unknown.value)
            return true
        }
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)
        val rawHtml = doc.html()
        val emitted = AtomicInteger(0)
        val report: (ExtractorLink) -> Unit = { link -> callback(link); emitted.incrementAndGet() }

        val movieTitle = doc.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim()?.stripSiteSuffix().orEmpty()

        val candidates = LinkedHashSet<String>()
        doc.select("a[href]")
            .forEach { a -> val h = a.attr("href").trim(); if (h.isNotEmpty() && !h.startsWith("#")) candidates.add(h) }
        doc.select("iframe").forEach { f ->
            listOf("src", "data-src").forEach { attr ->
                f.attr(attr).trim().takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            }
        }
        doc.select("[data-url],[data-src],[data-href],[data-embed],[data-fl-source],[data-fl-url]").forEach { el ->
            listOf("data-fl-source", "data-fl-url", "data-url", "data-src", "data-href", "data-embed").forEach { attr ->
                val u = el.attr(attr).trim(); if (u.isNotEmpty()) candidates.add(u)
            }
        }

        Regex("""https?:(?://|\\/\\/)[^\s"'<>\\]*(?:""" + HOST_MARKERS.joinToString("|") { Regex.escape(it) } + """)[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE)
            .findAll(rawHtml).forEach { candidates.add(it.value) }

        val embedUrls = candidates.map { UrlUtils.fixProtocol(it) }
            .filter { u ->
                u.startsWith("http") && JUNK_MARKERS.none {
                    u.contains(
                        it,
                        true
                    )
                } && HOST_MARKERS.any { u.contains(it, true) }
            }
            .map { normalizeEmbed(it) }.distinct()

        coroutineScope {
            val extractorJobs = embedUrls.map { url ->
                async {
                    try {
                        loadExtractor(url, "$mainUrl/", subtitleCallback, report)
                    } catch (_: Exception) {
                    }
                }
            }

            MediaFinder.find(rawHtml).forEach { streamUrl ->
                emit(report, name, "SpeedPorn Direct", streamUrl, "$mainUrl/", QualityParser.fromText(streamUrl))
            }

            val torrentJobs = if (movieTitle.isNotEmpty()) listOf(
                async { resolvePornoTorrent(movieTitle, report) },
                async { resolveLimeTorrents(movieTitle, report) }
            ) else emptyList()

            extractorJobs.awaitAll()

            if (emitted.get() == 0) {
                val fallbacks =
                    embedUrls.filter { u -> listOf("lulustream", "luluvid", "luluvdo").any { u.contains(it, true) } }
                        .take(2).map { async { resolveLulu(it, report) } } +
                            embedUrls.filter { u -> u.contains("mixdrop", true) || u.contains("mxdrop", true) }.take(2)
                                .map { async { resolveMixDrop(it, report) } }
                fallbacks.awaitAll()
            }

            torrentJobs.awaitAll()
        }

        return emitted.get() > 0
    }

    private suspend fun emitTorrent(
        callback: (ExtractorLink) -> Unit,
        sourceName: String,
        rawTitle: String,
        hit: TorrentHit,
        referer: String
    ) {
        emit(
            callback,
            sourceName,
            "$sourceName [${cleanTitle(rawTitle)}]",
            hit.url,
            referer,
            QualityParser.fromText(rawTitle)
        )
    }

    private suspend fun resolvePornoTorrent(title: String, callback: (ExtractorLink) -> Unit) {
        try {
            val num = Regex("""\b(\d+)\b""").find(title)?.groupValues?.get(1)
            val baseName = title.replace(Regex("""(?i)\b(?:Vol\.?|Volume|Episode|Ep\.?|Part|No\.?|#)?\s*\d+"""), "")
                .replace(Regex("""[-:_/]+"""), " ").trim()
            val queries = mutableListOf<String>()
            if (num != null && baseName.isNotEmpty()) {
                queries.add("$baseName #$num"); queries.add("$baseName $num")
            }
            queries.addAll(searchVariants(title)); queries.add(title)
            if (baseName.isNotEmpty()) queries.add(baseName)

            val wanted = tokensOf(title, setOf("vol", "volume"))
            for (q in queries.distinct()) {
                val doc =
                    getDocOrNull("https://pornotorrent.com.br/?s=${UrlUtils.encode(q)}", "https://pornotorrent.com.br/")
                        ?: continue
                for (article in doc.select("article, .post, div.item, .cp-card")) {
                    val a =
                        article.selectFirst("h1 a, h2 a, h3 a, a[title], .entry-title a, .cp-card__title a, a.cp-card__link")
                            ?: continue
                    val postTitle = a.attr("title").ifBlank { a.attr("aria-label") }.ifBlank { a.text().trim() }
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
                        emitTorrent(callback, "PornoTorrent", postTitle, hit, "https://pornotorrent.com.br/"); return
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun resolveLimeTorrents(title: String, callback: (ExtractorLink) -> Unit) {
        try {
            val wanted = tokensOf(title, setOf("the", "a", "an", "vol", "volume")).filter {
                it.length > 1 && !it.matches(
                    Regex("""(?:19|20)\d{2}""")
                )
            }.toSet()
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
                                emitTorrent(callback, "LimeTorrents", entry.title, hit, "$mirror/"); return
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
