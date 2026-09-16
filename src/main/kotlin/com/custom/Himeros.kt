package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Himeros - SpeedPorn Master Provider for CloudStream
 *
 * Direct Catalogs from SpeedPorn:
 * 1. HD Movies -> https://speedporn.net/hdmovies/
 * 2. Latest Releases -> https://speedporn.net/
 * 3. Adult Movies -> https://speedporn.net/adult/
 * 4. Full Length Movies -> https://speedporn.net/free-movies/
 * 5. HD Scenes -> https://speedporn.net/hdporn/
 * 6. Popular -> https://speedporn.net/search-tags/
 *
 * Resolvers:
 * - Direct Built-in Unpackers: Luluvid, StreamWish, Luluvdo, FileLions, VOE, MixDrop, DoodStream.
 * - Torrent Resolvers (Parallel): PornoTorrent (pornotorrent.com.br), LimeTorrents (limetorrents.lol).
 */
class Himeros : MainAPI() {
    override var mainUrl = "https://speedporn.net"
    override var name = "Himeros"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    val speedpornHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    private val unsafeClient: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (e: Exception) {
            OkHttpClient()
        }
    }

    private suspend fun fetchHtml(url: String, headers: Map<String, String> = speedpornHeaders): Document = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        val response = unsafeClient.newCall(reqBuilder.build()).execute()
        val body = response.body?.string() ?: ""
        Jsoup.parse(body, url)
    }

    // 1. SPEEDPORN HOME PAGE CATALOGS
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
        val url = if (page <= 1) {
            "$base/"
        } else {
            "$base/page/$page/"
        }
        val document = try {
            fetchHtml(url)
        } catch (e: Exception) {
            app.get(url, headers = speedpornHeaders).document
        }

        val items = document.select("div.video-block, div.thumb, .item, .post, article")
            .mapNotNull { it.toSpeedPornSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSpeedPornSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a.infos, a.thumb, a[title], h2 a, h3 a, a[href*='speedporn.net/']") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (href.isEmpty() || href == "$mainUrl/" || href.contains("/category/") || href.contains("/tag/") || href.contains("cdn-cgi") || href.startsWith("#")) {
            return null
        }

        val rawTitle = selectFirst("span.title, a.infos, h2, h3, .title")?.text()?.trim()
            ?: linkEl.attr("title").ifEmpty { linkEl.text().trim() }
        val title = rawTitle.replace(Regex("""(?i)^Watch\s+"""), "")
            .replace(Regex("""(?i)\s+Porn\s+Online\s+Free$"""), "")
            .trim()
        if (title.isEmpty()) return null

        val imgEl = selectFirst("div.no-thumb img, img")
        val posterUrl = imgEl?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // 2. ROMAN & NUMBER NORMALIZATION HELPERS
    private fun parseRoman(s: String): Int? {
        val roman = s.uppercase()
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (i in roman.length - 1 downTo 0) {
            val curr = values[roman[i]] ?: return null
            if (curr < prev) total -= curr else total += curr
            prev = curr
        }
        return if (total > 0) total else null
    }

    private fun convertRomanNumerals(input: String): String {
        return input.replace(Regex("""(?i)\b([MDCLXVI]+)\b""")) { m ->
            val num = parseRoman(m.groupValues[1])
            if (num != null) num.toString() else m.value
        }
    }

    private fun intToRoman(num: Int): String {
        if (num <= 0 || num > 3999) return num.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = num
        val sb = StringBuilder()
        for (i in vals.indices) {
            while (n >= vals[i]) {
                n -= vals[i]
                sb.append(syms[i])
            }
        }
        return sb.toString()
    }

    private fun convertArabicToRoman(input: String): String {
        return input.replace(Regex("""\b(\d{1,2})\b""")) { m ->
            val num = m.groupValues[1].toIntOrNull()
            if (num != null && num in 1..99) intToRoman(num) else m.value
        }
    }

    private fun normalizeSearchVariants(raw: String): List<String> {
        val variants = mutableListOf<String>()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return variants

        // 1. Standard sanitized query (strip Vol., Volume, Ep., Episode, Part, No., #, Release Years)
        val cleaned = trimmed
            .replace(Regex("""(?i)\b(?:Vol\.?|Volume|Episode|Ep\.?|Part|No\.?|#)\s*(\d+)"""), "$1")
            .replace(Regex("""(?i)\b(?:Vol\.?|Volume|Episode|Ep\.?|Part|No\.?)\s*([MDCLXVI]+)\b"""), "$1")
            .replace(Regex("""\((?:19\d\d|20\d\d)\)"""), "")
            .replace(Regex("""[-:_/]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        variants.add(cleaned)

        // 2. Roman to Arabic (e.g. Level Up IV -> Level Up 4)
        val romanToArab = convertRomanNumerals(cleaned)
        if (!variants.contains(romanToArab)) variants.add(romanToArab)

        // 3. Arabic to Roman (e.g. Level Up 4 -> Level Up IV)
        val arabToRoman = convertArabicToRoman(cleaned)
        if (!variants.contains(arabToRoman)) variants.add(arabToRoman)

        // 4. Raw trimmed if different
        if (!variants.contains(trimmed)) variants.add(trimmed)

        return variants
    }

    // 3. SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val variants = normalizeSearchVariants(query)

        for (v in variants) {
            val searchUrl = "$mainUrl/?s=${URLEncoder.encode(v, "UTF-8")}"
            val doc = try {
                fetchHtml(searchUrl)
            } catch (e: Exception) {
                app.get(searchUrl, headers = speedpornHeaders).document
            }

            doc.select("div.video-block, div.thumb, .item, .post, article")
                .mapNotNull { it.toSpeedPornSearchResult() }
                .forEach { results.add(it) }

            if (results.isNotEmpty()) break
        }

        return results.distinctBy { it.url }
    }

    // 4. LOAD MOVIE DETAILS
    override suspend fun load(url: String): LoadResponse {
        val doc = try {
            fetchHtml(url)
        } catch (e: Exception) {
            app.get(url, headers = speedpornHeaders).document
        }

        val rawTitle = doc.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim() ?: "SpeedPorn Movie"
        val title = rawTitle.replace(Regex("""(?i)^Watch\s+"""), "")
            .replace(Regex("""(?i)\s+Porn\s+Online\s+Free$"""), "")
            .trim()

        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("div.no-thumb img, article img, div.entry-content img, img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }

        val plot = doc.selectFirst("div.description, div.entry-content p, div.synopsis, p")?.text()?.trim()

        val tags = doc.select("a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val actors = doc.select("a[href*='/pornstar/'], a[href*='/actor/'], a[href*='/model/']")
            .map { ActorData(Actor(it.text().trim(), null)) }
            .filter { it.actor.name.isNotEmpty() }
            .distinctBy { it.actor.name }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.actors = actors
        }
    }

    // 5. DEAN EDWARDS PACKER UNPACKER
    private fun unpackPacker(packedJs: String): String {
        val regex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?return p\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(packedJs) ?: return ""
        var p = match.groupValues[1]
        val a = match.groupValues[2].toIntOrNull() ?: 10
        var c = match.groupValues[3].toIntOrNull() ?: 0
        val k = match.groupValues[4].split("|")

        while (c-- > 0) {
            if (c < k.size && k[c].isNotEmpty()) {
                val key = java.lang.Integer.toString(c, a)
                p = p.replace(Regex("""\b$key\b"""), k[c])
            }
        }
        return p
    }

    // 6. DIRECT EMBED RESOLVER (Luluvid / StreamWish / FileLions)
    private suspend fun resolveLuluvidStreamWish(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val responseText = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to speedpornHeaders["User-Agent"]!!)).text
            val unpacked = unpackPacker(responseText)
            val sourceRegex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?)""")
            val streamMatch = sourceRegex.find(unpacked)?.value ?: sourceRegex.find(responseText)?.value

            if (!streamMatch.isNullOrBlank()) {
                val host = embedUrl.substringAfter("://").substringBefore("/")
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "SpeedPorn [$host 1080p]",
                        url = streamMatch,
                        referer = embedUrl,
                        quality = Qualities.P1080.value,
                        isM3u8 = streamMatch.contains(".m3u8")
                    )
                )
            }
        } catch (_: Exception) {}
    }

    // 7. NORMALIZE EMBED URLS
    private fun normalizeEmbedUrl(url: String): String {
        var clean = url.trim()
        if (clean.startsWith("//")) clean = "https:$clean"

        // Playmogo / DoodStream mirror -> d0000d.com / dood.to
        if (clean.contains("playmogo.com/e/") || clean.contains("playmogo.com/d/")) {
            val code = clean.substringAfter("/e/").substringAfter("/d/").substringBefore("?").substringBefore("&")
            return "https://d0000d.com/e/$code"
        }
        if (clean.contains("doodstream.com/d/") || clean.contains("dood.to/d/") || clean.contains("dood.li/d/") || clean.contains("dood.ws/d/")) {
            val code = clean.substringAfter("/d/").substringBefore("?").substringBefore("&")
            return "https://d0000d.com/e/$code"
        }
        if (clean.contains("doodstream.com/e/") || clean.contains("dood.to/e/") || clean.contains("dood.li/e/") || clean.contains("dood.ws/e/")) {
            val code = clean.substringAfter("/e/").substringBefore("?").substringBefore("&")
            return "https://d0000d.com/e/$code"
        }

        // MixDrop mirror -> mixdrop.ag / mixdrop.co
        if (clean.contains("mixdrop.my/e/") || clean.contains("mixdrop.co/e/") || clean.contains("mixdrop.to/e/") || clean.contains("mixdrop.sx/e/")) {
            val code = clean.substringAfter("/e/").substringBefore("?").substringBefore("&")
            return "https://mixdrop.ag/e/$code"
        }
        if (clean.contains("mixdrop.ag/f/") || clean.contains("mixdrop.my/f/") || clean.contains("mixdrop.co/f/")) {
            val code = clean.substringAfter("/f/").substringBefore("?").substringBefore("&")
            return "https://mixdrop.ag/e/$code"
        }

        // StreamWish / Luluvid / FileLions mirrors
        if (clean.contains("luluvid.com/e/") || clean.contains("luluvdo.com/e/")) {
            val code = clean.substringAfter("/e/").substringBefore("?").substringBefore("&")
            return "https://streamwish.to/e/$code"
        }
        if (clean.contains("streamwish.to/f/") || clean.contains("filelions.to/f/") || clean.contains("filelions.com/f/")) {
            val code = clean.substringAfter("/f/").substringBefore("?").substringBefore("&")
            return "https://streamwish.to/e/$code"
        }

        // StreamTape
        if (clean.contains("streamtape.com/e/")) {
            return clean
        }

        // VOE mirror
        if (clean.contains("voe.sx/e/")) {
            return clean
        }

        return clean
    }

    // 8. RESOLVE LINKS (SPEEDPORN EMBEDS + DIRECT UNPACKERS + TORRENTS)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            fetchHtml(data)
        } catch (e: Exception) {
            app.get(data, headers = speedpornHeaders).document
        }

        val rawHtml = doc.html()
        val movieTitle = doc.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("""(?i)^Watch\s+"""), "")
            ?.replace(Regex("""(?i)\s+Porn\s+Online\s+Free$"""), "")
            ?.trim() ?: ""

        val rawCandidateUrls = mutableSetOf<String>()

        // A. Extract all anchor hrefs
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript")) {
                rawCandidateUrls.add(href)
            }
        }

        // B. Extract all iframe src & data-src
        for (iframe in doc.select("iframe")) {
            val src = iframe.attr("src").trim()
            val dataSrc = iframe.attr("data-src").trim()
            if (src.isNotEmpty()) rawCandidateUrls.add(src)
            if (dataSrc.isNotEmpty()) rawCandidateUrls.add(dataSrc)
        }

        // C. Extract custom data attributes
        for (el in doc.select("[data-url], [data-src], [data-href], [data-embed]")) {
            val u = el.attr("data-url").ifEmpty { el.attr("data-src").ifEmpty { el.attr("data-href").ifEmpty { el.attr("data-embed") } } }.trim()
            if (u.isNotEmpty()) rawCandidateUrls.add(u)
        }

        // D. Regex scan the whole page HTML for host URLs
        val lockerRegex = Regex("""(https?://[^\s"'<>]*(?:playmogo|dood|mixdrop|streamtape|voe|streamwish|filelions|wolfstream|luluvid|luluvdo|dropupload|rapidgator|nitroflare)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
        lockerRegex.findAll(rawHtml).forEach { m ->
            rawCandidateUrls.add(m.value)
        }

        // Filter valid video lockers
        val validOriginalUrls = rawCandidateUrls.filter { url ->
            !url.contains("google.com") &&
            !url.contains("api.") &&
            !url.contains("deleted") &&
            !url.contains("theporndude") &&
            (url.contains("playmogo") || url.contains("dood") || url.contains("mixdrop") ||
             url.contains("streamtape") || url.contains("voe.sx") || url.contains("streamwish") ||
             url.contains("filelions") || url.contains("luluvid") || url.contains("luluvdo") ||
             url.contains("wolfstream") || url.contains("dropupload"))
        }.distinct()

        coroutineScope {
            // E. Direct custom unpackers for Luluvid / StreamWish / FileLions
            val luluJobs = validOriginalUrls.filter { 
                it.contains("luluvid") || it.contains("luluvdo") || it.contains("streamwish") || it.contains("filelions") 
            }.map { embedUrl ->
                async { resolveLuluvidStreamWish(embedUrl, callback) }
            }

            // F. Default CloudStream extractors for others
            val extractorJobs = validOriginalUrls.map { normalizeEmbedUrl(it) }.distinct().map { embedUrl ->
                async {
                    try {
                        loadExtractor(embedUrl, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }

            // G. Look for direct MP4 / M3U8 video streams in page scripts
            val directStreamRegex = Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)(?:\?[^\s"'<>]*)?)""", RegexOption.IGNORE_CASE)
            directStreamRegex.findAll(rawHtml).forEach { m ->
                val streamUrl = m.value
                if (!streamUrl.contains("test-videos.co.uk") && !streamUrl.contains("sample")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "SpeedPorn Direct (1080p)",
                            url = streamUrl,
                            referer = "$mainUrl/",
                            quality = Qualities.P1080.value,
                            isM3u8 = streamUrl.contains(".m3u8")
                        )
                    )
                }
            }

            // H. Parallel Torrent Resolution (PornoTorrent & LimeTorrents)
            val pTorrent = if (movieTitle.isNotEmpty()) async { resolvePornoTorrent(movieTitle, callback) } else null
            val lTorrent = if (movieTitle.isNotEmpty()) async { resolveLimeTorrents(movieTitle, callback) } else null

            luluJobs.awaitAll()
            extractorJobs.awaitAll()
            pTorrent?.await()
            lTorrent?.await()
        }

        return true
    }

    // 9. PORNOTORRENT RESOLVER
    private suspend fun resolvePornoTorrent(title: String, callback: (ExtractorLink) -> Unit) {
        try {
            val variants = normalizeSearchVariants(title)
            for (cleanTitle in variants) {
                val q = cleanTitle.replace(Regex("""[^a-zA-Z0-9\s]"""), " ").trim()
                val url = "https://pornotorrent.com.br/en/?s=${URLEncoder.encode(q, "UTF-8")}"
                val doc = app.get(url, headers = mapOf("User-Agent" to speedpornHeaders["User-Agent"]!!)).document
                val firstPost = doc.selectFirst("article a[href], .post a[href], div.item a[href]")?.attr("href") ?: continue
                val postDoc = app.get(fixUrl(firstPost), headers = mapOf("User-Agent" to speedpornHeaders["User-Agent"]!!)).document

                var magnet = ""
                for (a in postDoc.select("a[href]")) {
                    val href = a.attr("href")
                    if (href.startsWith("magnet:")) {
                        magnet = href
                        break
                    } else if (href.contains("/download/?m=")) {
                        val enc = href.substringAfter("/download/?m=")
                        magnet = try { URLDecoder.decode(enc, "UTF-8") } catch (_: Exception) { enc }
                        break
                    }
                }

                if (magnet.startsWith("magnet:")) {
                    callback.invoke(
                        ExtractorLink(
                            source = "PornoTorrent",
                            name = "PornoTorrent [Torrent 1080p]",
                            url = magnet,
                            referer = "https://pornotorrent.com.br/",
                            quality = Qualities.P1080.value,
                            isM3u8 = false
                        )
                    )
                    break
                }
            }
        } catch (_: Exception) {}
    }

    // 10. LIMETORRENTS RESOLVER
    private suspend fun resolveLimeTorrents(title: String, callback: (ExtractorLink) -> Unit) {
        try {
            val variants = normalizeSearchVariants(title)
            for (cleanTitle in variants) {
                val cleanQuery = cleanTitle.replace(Regex("""[^a-zA-Z0-9]+"""), "-").trim('-')
                val url = "https://www.limetorrents.lol/search/all/$cleanQuery/"
                val doc = app.get(url, headers = mapOf("User-Agent" to speedpornHeaders["User-Agent"]!!)).document
                val firstTorrent = doc.selectFirst("div.tt-name a:last-child, a[href*='-torrent-']")?.attr("href") ?: continue
                val torrentDoc = app.get(fixUrl(firstTorrent), headers = mapOf("User-Agent" to speedpornHeaders["User-Agent"]!!)).document
                val magnet = torrentDoc.selectFirst("a[href^='magnet:']")?.attr("href") ?: ""

                if (magnet.startsWith("magnet:")) {
                    callback.invoke(
                        ExtractorLink(
                            source = "LimeTorrents",
                            name = "LimeTorrents [Torrent]",
                            url = magnet,
                            referer = "https://www.limetorrents.lol/",
                            quality = Qualities.P1080.value,
                            isM3u8 = false
                        )
                    )
                    break
                }
            }
        } catch (_: Exception) {}
    }
}