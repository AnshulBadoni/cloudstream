package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 5MoviesPorn Provider - High quality streaming aggregator from 5moviesporn.io
 *
 * Direct Catalogs:
 * 1. Latest Releases -> https://www.5moviesporn.io/
 * 2. Full Movies -> https://www.5moviesporn.io/genre/xxx-movies/
 * 3. Scenes -> https://www.5moviesporn.io/genre/xxx-scenes/
 * 4. Amateur, Anal, Asian, Big Tits, Creampie, Ebony, Hardcore, Lesbian, MILF, Teen, Threesome
 *
 * Stream Resolvers:
 * - VOE Embeds (voe.sx) via primepages.sbs player wrapper
 * - PlayMogo & DoodStream Embeds
 * - StreamTape / MixDrop / StreamWish Embeds
 */
class FiveMoviesPorn : MainAPI() {
    override var mainUrl = "https://www.5moviesporn.io"
    override var name = "5MoviesPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/genre/xxx-movies/" to "Full Movies",
        "$mainUrl/genre/xxx-scenes/" to "Scenes",
        "$mainUrl/tag/amateur/" to "Amateur",
        "$mainUrl/tag/anal/" to "Anal",
        "$mainUrl/tag/asian/" to "Asian",
        "$mainUrl/tag/big-tits/" to "Big Tits",
        "$mainUrl/tag/creampie/" to "Creampie",
        "$mainUrl/tag/ebony/" to "Ebony",
        "$mainUrl/tag/hardcore/" to "Hardcore",
        "$mainUrl/tag/lesbian/" to "Lesbian",
        "$mainUrl/tag/milf-cougar/" to "MILF",
        "$mainUrl/tag/teen/" to "Teen",
        "$mainUrl/tag/threesome/" to "Threesome"
    )

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""&#?\w+;"""), " ")
        t = t.replace(Regex("""\[.*?\]|\(.*?\)|<.*?>"""), " ")
        t = t.replace(
            Regex("""(?i)(?:Blacked|Evil\s*Angel|Brazzers|Tushy|Vixen|Bang!?|Naughty\s*America|Sweet\s*Sinner|Wicked|Digital\s*Playground|Jules\s*Jordan|Reality\s*Kings|DDF|Mofos)(?:\s*\d{2,4})?"""),
            " "
        )
        t = t.replace(
            Regex("""(?i)(?:\d{3,4}p|4K|2160p|1080p|720p|480p|WEB-?DL|BDRip|DVDRip|HDRip|x264|x265|HEVC|AAC|MP3|SPLITSCENES|XXX|FULL|HD|VOSTFR|FRENCH)"""),
            " "
        )
        t = t.replace(Regex("""(?:19|20)\d{2}"""), " ")
        t = t.replace(Regex("""[-–—:_/]+"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t.ifEmpty { raw.trim() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a[href]") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (href.isEmpty() || href == "$mainUrl/" ||
            href.contains("/tag/") || href.contains("/genre/") || href.contains("/category/") ||
            href.contains("/page/") || href.contains("wp-content") || href.contains("feed") ||
            href.contains("sitemap") || href.contains("contact") || href.contains("legal")
        ) {
            return null
        }

        val rawTitle = selectFirst("h2, h3, .title, a[title]")?.let {
            it.attr("title").ifEmpty { it.text().trim() }
        } ?: linkEl.attr("title").ifEmpty {
            href.trimEnd('/').substringAfterLast('/').replace('-', ' ').trim()
        }
        if (rawTitle.isEmpty()) return null

        val imgEl = selectFirst("img")
        val poster = imgEl?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        return newMovieSearchResponse(cleanTitle(rawTitle), href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = headers
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val doc = runCatching { app.get(url, headers = headers).document }.getOrNull()

        val items = doc?.select("div[class*='item'], div[class*='video'], div[class*='thumb'], article, .post")
            ?.mapNotNull { it.toSearchResult() }
            ?.distinctBy { it.url }
            .orEmpty()

        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val doc = runCatching { app.get(searchUrl, headers = headers).document }.getOrNull() ?: return emptyList()

        val items = doc.select("div[class*='item'], div[class*='video'], div[class*='thumb'], article, .post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = runCatching { app.get(url, headers = headers).document }.getOrNull()
        val rawTitle = doc?.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ').trim()
        val title = cleanTitle(rawTitle)

        val poster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc?.selectFirst("img.cover, div.poster img, article img, img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }

        val plot = doc?.selectFirst("div.description, div.entry-content p, p")?.text()?.trim()

        val tags = doc?.select("a[href*='/tag/'], a[href*='/genre/']")
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = headers
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val doc = runCatching { app.get(data, headers = headers).document }.getOrNull() ?: return@coroutineScope false
        val rawHtml = doc.html()
        var count = 0

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.let { cleanTitle(it) }
            ?: data.trimEnd('/').substringAfterLast('/').replace('-', ' ').trim()

        // 1. Direct media URLs in page HTML
        StreamSupport.extractMediaUrls(rawHtml).forEach { streamUrl ->
            if (!streamUrl.contains("test-videos.co.uk") && !streamUrl.contains("sample")) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = streamUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.P1080.value,
                        isM3u8 = streamUrl.contains(".m3u8")
                    )
                )
                count++
            }
        }

        // 2. Extract wrapped iframes (e.g. primepages.sbs/play.php?host=...)
        val candidateEmbeds = mutableListOf<String>()
        for (iframe in doc.select("iframe")) {
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.trim()
            if (src.isNotEmpty()) {
                if (src.contains("play.php") && src.contains("host=")) {
                    val encodedHost = Regex("""[?&]host=([^&#]+)""").find(src)?.groupValues?.get(1)
                    if (!encodedHost.isNullOrBlank()) {
                        val decoded = runCatching { URLDecoder.decode(encodedHost, "UTF-8") }.getOrNull() ?: encodedHost
                        candidateEmbeds.add(decoded)
                    }
                } else {
                    candidateEmbeds.add(src)
                }
            }
        }

        // 3. Extract data-url attributes
        for (el in doc.select("[data-url], [data-src], [data-href], [data-embed]")) {
            val u = el.attr("data-url")
                .ifEmpty { el.attr("data-src").ifEmpty { el.attr("data-href").ifEmpty { el.attr("data-embed") } } }
                .trim()
            if (u.isNotEmpty() && (u.contains("http://") || u.contains("https://") || u.startsWith("//"))) {
                candidateEmbeds.add(if (u.startsWith("//")) "https:$u" else u)
            }
        }

        // 4. Scan whole HTML for known host locker patterns
        val hostRegex = Regex(
            """(https?://[^\s"'<>\\]+?(?:voe\.sx|playmogo|dood|d0000d|streamtape|streamwish|mixdrop|mxdrop|filelions|luluvid|luluvdo|lulustream)[^\s"'<>\\]*)""",
            RegexOption.IGNORE_CASE
        )
        hostRegex.findAll(rawHtml).forEach { m ->
            candidateEmbeds.add(m.value)
        }

        val validHosts = candidateEmbeds.filter { u ->
            !u.contains("google.com") && !u.contains("deleted") && !u.contains("favicon")
        }.distinct()

        // 5. Direct unpackers for LuluStream / StreamWish / FileLions
        val luluJobs = validHosts.filter {
            it.contains("lulustream") || it.contains("luluvid") || it.contains("luluvdo") ||
                    it.contains("streamwish") || it.contains("filelions")
        }.take(3).map { embedUrl ->
            async {
                resolveLuluvidStreamWish(embedUrl) { link ->
                    callback(link)
                    count++
                }
            }
        }

        // 6. Direct unpackers for MixDrop
        val mixdropJobs = validHosts.filter {
            it.contains("mixdrop") || it.contains("mxdrop")
        }.take(2).map { embedUrl ->
            async {
                resolveMixDrop(embedUrl) { link ->
                    callback(link)
                    count++
                }
            }
        }

        // 7. Standard CloudStream extractors for others
        val extractorJobs = validHosts.filter {
            !it.contains("mixdrop") && !it.contains("mxdrop") &&
                    !it.contains("lulustream") && !it.contains("luluvid") && !it.contains("luluvdo") &&
                    !it.contains("streamwish") && !it.contains("filelions")
        }.flatMap { original ->
            listOf(original, normalizeEmbedUrl(original))
        }.distinct().take(4).map { embedUrl ->
            async {
                val clean = normalizeEmbedUrl(embedUrl)
                runCatching {
                    loadExtractor(clean, "$mainUrl/", subtitleCallback) { link ->
                        callback(link)
                        count++
                    }
                }
            }
        }

        // 8. Torrent Fallback Resolution (PornoTorrent & LimeTorrents)
        val pTorrent = if (title.isNotEmpty()) async {
            resolvePornoTorrent(title) { link ->
                callback(link)
                count++
            }
        } else null

        val lTorrent = if (title.isNotEmpty()) async {
            resolveLimeTorrents(title) { link ->
                callback(link)
                count++
            }
        } else null

        luluJobs.awaitAll()
        mixdropJobs.awaitAll()
        extractorJobs.awaitAll()
        pTorrent?.await()
        lTorrent?.await()
        count > 0
    }

    private fun unpackPacker(packedJs: String): String {
        try {
            val regex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?return p\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(packedJs) ?: return ""
            var p = match.groupValues[1]
            val a = match.groupValues[2].toIntOrNull() ?: 10
            var c = match.groupValues[3].toIntOrNull() ?: 0
            val k = match.groupValues[4].split("|")

            while (c-- > 0) {
                if (c < k.size && k[c].isNotEmpty()) {
                    val key = java.lang.Integer.toString(c, a)
                    p = p.replace(Regex("""\b$key\b"""), java.util.regex.Matcher.quoteReplacement(k[c]))
                }
            }
            return p
        } catch (_: Exception) {
            return ""
        }
    }

    private suspend fun resolveLuluvidStreamWish(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val code = Regex("""/(?:e|f)/([a-zA-Z0-9]+)""").find(embedUrl)?.groupValues?.get(1)
                ?: embedUrl.substringAfterLast("/").substringBefore("?").substringBefore("&")
            if (code.isBlank()) return

            val mirrors = listOf(
                "https://lulustream.com/e/$code",
                "https://luluvdo.com/e/$code",
                "https://luluvid.com/e/$code",
                embedUrl,
                "https://filelions.to/e/$code",
                "https://streamwish.to/e/$code"
            ).distinct()

            for (mirror in mirrors) {
                val responseText = try {
                    app.get(
                        mirror,
                        headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to headers["User-Agent"]!!)
                    ).text
                } catch (_: Exception) {
                    continue
                }

                if (responseText.length < 500) continue
                val unpacked = unpackPacker(responseText)
                val streamMatch = StreamSupport.extractMediaUrls(unpacked).firstOrNull()
                    ?: StreamSupport.extractMediaUrls(responseText).firstOrNull()
                    ?: Regex("""sources\s*:\s*\[\{file\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)

                if (!streamMatch.isNullOrBlank()) {
                    val isLulu = mirror.contains("lulu")
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = if (isLulu) "$name [LuluStream 1080p]" else "$name [StreamWish 1080p]",
                            url = streamMatch,
                            referer = "",
                            quality = Qualities.P1080.value,
                            isM3u8 = streamMatch.contains(".m3u8"),
                            headers = mapOf("User-Agent" to headers["User-Agent"]!!)
                        )
                    )
                    break
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun resolveMixDrop(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val code = Regex("""/(?:e|f)/([a-zA-Z0-9]+)""").find(embedUrl)?.groupValues?.get(1)
                ?: embedUrl.substringAfterLast("/").substringBefore("?").substringBefore("&")
            if (code.isBlank()) return

            val mirrors = listOf(
                "https://mixdrop.ag/e/$code",
                "https://mixdrop.my/e/$code",
                embedUrl,
                "https://mxdrop.top/e/$code",
                "https://mixdrop.co/e/$code",
                "https://mixdrop.sx/e/$code"
            ).distinct()

            for (mirror in mirrors) {
                val responseText = try {
                    app.get(
                        mirror,
                        headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to headers["User-Agent"]!!)
                    ).text
                } catch (_: Exception) {
                    continue
                }

                if (responseText.length < 500) continue
                val unpacked = unpackPacker(responseText)
                val wurlRegex = Regex("""(?:MDCore\.wurl|wurl)\s*[:=]\s*["']([^"']+)"""")
                val wurlMatch = wurlRegex.find(unpacked)?.groupValues?.get(1)
                    ?: wurlRegex.find(responseText)?.groupValues?.get(1)

                if (!wurlMatch.isNullOrBlank() && wurlMatch.trim().isNotEmpty()) {
                    val fullUrl = if (wurlMatch.startsWith("//")) "https:$wurlMatch" else wurlMatch
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name [MixDrop 1080p]",
                            url = fullUrl,
                            referer = "https://mixdrop.ag/",
                            quality = Qualities.P1080.value,
                            isM3u8 = false,
                            headers = mapOf(
                                "Referer" to "https://mixdrop.ag/",
                                "User-Agent" to headers["User-Agent"]!!
                            )
                        )
                    )
                    break
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun resolvePornoTorrent(title: String, callback: (ExtractorLink) -> Unit) {
        try {
            val q = URLEncoder.encode(title, "UTF-8")
            val url = "https://pornotorrent.com.br/?s=$q"
            val doc = try {
                app.get(url, headers = mapOf("User-Agent" to headers["User-Agent"]!!)).document
            } catch (_: Exception) {
                return
            }

            val articles = doc.select("article, .post, div.item, .cp-card")
            for (article in articles) {
                val linkEl = article.selectFirst("h2 a, h3 a, h1 a, a[title], .entry-title a, .cp-card__title a, a.cp-card__link") ?: continue
                val postTitle = linkEl.attr("aria-label").ifEmpty { linkEl.attr("title") }.ifEmpty { linkEl.text().trim() }
                val postHref = fixUrl(linkEl.attr("href"))
                if (postHref.isEmpty() || postTitle.isEmpty()) continue

                val postDoc = try {
                    app.get(postHref, headers = mapOf("User-Agent" to headers["User-Agent"]!!)).document
                } catch (_: Exception) {
                    continue
                }

                val torrent = TorrentSupport.extractTorrent(postDoc)
                if (torrent != null) {
                    callback.invoke(
                        ExtractorLink(
                            source = "PornoTorrent",
                            name = "$name [PornoTorrent ${cleanTitle(postTitle)}]",
                            url = torrent.url,
                            referer = "https://pornotorrent.com.br/",
                            quality = when (TorrentSupport.qualityFromText(postTitle)) {
                                2160 -> Qualities.P2160.value
                                1440 -> Qualities.P1440.value
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                else -> Qualities.Unknown.value
                            },
                            isM3u8 = false
                        ).apply { this.type = ExtractorLinkType.TORRENT }
                    )
                    break
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun resolveLimeTorrents(title: String, callback: (ExtractorLink) -> Unit) {
        try {
            for (mirror in TorrentSupport.limeMirrors) {
                for (searchUrl in TorrentSupport.limeSearchUrls(mirror, title)) {
                    val searchDocument = runCatching {
                        app.get(
                            searchUrl,
                            headers = mapOf("User-Agent" to headers["User-Agent"]!!, "Referer" to "$mirror/")
                        ).document
                    }.getOrNull() ?: continue

                    val matches = TorrentSupport.findLimeSearchEntries(searchDocument, searchUrl)
                    for (entry in matches.take(2)) {
                        val detailDoc = runCatching {
                            app.get(
                                entry.url,
                                headers = mapOf("User-Agent" to headers["User-Agent"]!!, "Referer" to "$mirror/")
                            ).document
                        }.getOrNull() ?: continue

                        val torrent = TorrentSupport.extractTorrent(detailDoc)
                        if (torrent != null) {
                            callback.invoke(
                                ExtractorLink(
                                    source = "LimeTorrents",
                                    name = "$name [LimeTorrents ${cleanTitle(entry.title)}]",
                                    url = torrent.url,
                                    referer = "$mirror/",
                                    quality = when (TorrentSupport.qualityFromText(entry.title)) {
                                        2160 -> Qualities.P2160.value
                                        1440 -> Qualities.P1440.value
                                        1080 -> Qualities.P1080.value
                                        720 -> Qualities.P720.value
                                        480 -> Qualities.P480.value
                                        else -> Qualities.Unknown.value
                                    },
                                    isM3u8 = false
                                ).apply { this.type = ExtractorLinkType.TORRENT }
                            )
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun normalizeEmbedUrl(url: String): String {
        var clean = url.trim().replace("&amp;", "&")
        if (clean.startsWith("//")) clean = "https:$clean"
        if (clean.contains("playmogo.com/d/")) {
            val code = clean.substringAfter("/d/").substringBefore("?").substringBefore("&").substringBefore("/download")
            return "https://playmogo.com/e/$code"
        }
        if (clean.contains("doodstream.com/d/") || clean.contains("dood.to/d/") || clean.contains("dood.li/d/")) {
            val code = clean.substringAfter("/d/").substringBefore("?").substringBefore("&")
            return "https://d0000d.com/e/$code"
        }
        return clean
    }

    private fun fixUrl(url: String, base: String = mainUrl): String {
        val clean = url.replace("\\/", "/")
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> base.trimEnd('/') + clean
            else -> base.trimEnd('/') + "/" + clean
        }
    }

    private fun fixUrlNull(url: String?, base: String = mainUrl): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url, base)
    }
}
