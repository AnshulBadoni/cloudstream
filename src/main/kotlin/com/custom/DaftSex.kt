package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * DaftSex Standalone Provider for CloudStream
 */
class DaftSex : MainAPI() {
    override var mainUrl = "https://daftsex.biz"
    override var name = "DaftSex"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries, TvType.Movie)

    val pornpicsUrl = "https://www.pornpics.de"

    private val defaultHeaders = mapOf(
        "referer" to "$mainUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
    private val pornpicsHeaders = mapOf(
        "referer" to "$pornpicsUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    companion object {
        var searchPages: Int = 2
        var modelPages: Int = 2
    }

    private val popularStudios = listOf(
        "Vixen" to "vixen",
        "Blacked" to "blacked",
        "Brazzers" to "brazzers",
        "Tushy" to "tushy",
        "Deeper" to "deeper",
        "Reality Kings" to "reality-kings",
        "Naughty America" to "naughty-america",
        "Pure Taboo" to "pure-taboo",
        "Slayed" to "slayed",
        "Mofos" to "mofos",
        "Evil Angel" to "evil-angel",
        "Wicked" to "wicked",
        "Twistys" to "twistys",
        "Babes" to "babes",
        "FakeHub" to "fakehub",
        "PropertySex" to "propertysex"
    )

    // 1. HOME PAGE CATALOG DEFINITIONS (Standard 6 Rows, Clean, No Emojis)
    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "actors" to "Actors",
        "studios" to "Studios",
        "vixen" to "Vixen",
        "blacked" to "Blacked",
        "brazzers" to "Brazzers"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // Trending Videos
            "trending" -> {
                val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("a[href*='/movie/'], div.movie-item a")?.mapNotNull { parseMovieCard(it) }.orEmpty()
            }

            // Actors / Models (PornPics Trending Models with DaftSex video profile link)
            "actors" -> {
                val ppUrl = if (page <= 1) {
                    "$pornpicsUrl/pornstars/?gender=female&orientation=straight&s=trending"
                } else {
                    "$pornpicsUrl/pornstars/?gender=female&orientation=straight&s=trending&page=$page"
                }
                val ppItems = runCatching {
                    val doc = app.get(ppUrl, headers = pornpicsHeaders).document
                    doc.select("li.thumb-block, li:has(a[href*='/pornstars/']), div.thumb-holder").mapNotNull {
                        parsePornPicsActorCard(it)
                    }
                }.getOrDefault(emptyList())

                if (ppItems.isNotEmpty()) {
                    ppItems
                } else {
                    val doc = runCatching { app.get("$mainUrl/models", headers = defaultHeaders).document }.getOrNull()
                    doc?.select("a[href*='/video/']")?.mapNotNull { parseActorCard(it) }.orEmpty()
                }
            }

            // Studios / Channels Directory
            "studios" -> {
                popularStudios.map { (sName, sSlug) ->
                    newTvSeriesSearchResponse(sName, "$mainUrl/video/$sSlug", TvType.TvSeries) {
                        this.posterHeaders = defaultHeaders
                    }
                }
            }

            // Studio Specific Channels: Vixen, Blacked, Brazzers
            "vixen" -> {
                val url = if (page <= 1) "$mainUrl/video/vixen" else "$mainUrl/video/vixen/$page"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("a[href*='/movie/'], div.movie-item a")?.mapNotNull { parseMovieCard(it) }.orEmpty()
            }

            "blacked" -> {
                val url = if (page <= 1) "$mainUrl/video/blacked" else "$mainUrl/video/blacked/$page"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("a[href*='/movie/'], div.movie-item a")?.mapNotNull { parseMovieCard(it) }.orEmpty()
            }

            else -> {
                val url = if (page <= 1) "$mainUrl/video/brazzers" else "$mainUrl/video/brazzers/$page"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("a[href*='/movie/'], div.movie-item a")?.mapNotNull { parseMovieCard(it) }.orEmpty()
            }
        }

        val hasNextPage = items.size >= 12
        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = request.data != "actors")
        return newHomePageResponse(homePageList, hasNextPage)
    }

    // 2. SEARCH WITH PERFORMER MATCH PRIORITIZATION
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val slugQuery = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val queryWords = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val titleCaseQuery = queryWords.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        // 1. Search actors (Synthetic Performer Card)
        val actorsJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                if (queryWords.size in 1..4 && slugQuery.isNotBlank()) {
                    list.add(
                        newTvSeriesSearchResponse(
                            name = titleCaseQuery,
                            url = "$mainUrl/video/$slugQuery",
                            type = TvType.TvSeries
                        ) {
                            this.posterHeaders = defaultHeaders
                        }
                    )
                }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        // 2. Search videos across configured searchPages
        val videosJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                for (p in 1..searchPages.coerceIn(1, 10)) {
                    val url = if (p <= 1) "$mainUrl/video/$slugQuery" else "$mainUrl/video/$slugQuery/$p"
                    val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull() ?: break
                    val items = doc.select("a[href*='/movie/'], div.movie-item a").mapNotNull { parseMovieCard(it) }
                    if (items.isEmpty()) break
                    list.addAll(items)
                }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        val actors = actorsJob.await()
        val videos = videosJob.await()

        (actors + videos).distinctBy { it.url }
    }

    // 3. LOAD RESPONSE (PERFORMER/CHANNEL OR VIDEO)
    override suspend fun load(url: String): LoadResponse {
        val isChannelOrPerformer = url.contains("/video/") && !url.contains("/movie/")

        if (isChannelOrPerformer) {
            val rawSlug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val name = doc?.selectFirst("h1")?.text()?.trim()
                ?: rawSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val slug = rawSlug.ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            val poster = extractImg(doc?.selectFirst("meta[property='og:image'], .profile, .img-holder, div.video-thumb, img"))

            val episodes = mutableListOf<Episode>()
            for (p in 1..modelPages.coerceIn(1, 10)) {
                val pageUrl = if (p <= 1) "$mainUrl/video/$slug" else "$mainUrl/video/$slug/$p"
                val pageDoc = runCatching { app.get(pageUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                val cards = pageDoc.select("a[href*='/movie/'], div.movie-item a")
                if (cards.isEmpty()) break
                cards.forEach { el ->
                    val linkEl = if (el.tagName() == "a") el else el.selectFirst("a[href*='/movie/']") ?: return@forEach
                    val link = linkEl.attr("href").ifBlank { null } ?: return@forEach

                    val title = linkEl.selectFirst("div.video-title, .video-title, .title, h1, h2, h3")?.text()?.trim()
                        ?.ifBlank { null }
                        ?: linkEl.attr("title").ifBlank { null }
                        ?: "DaftSex Video ${episodes.size + 1}"
                    val img = extractImg(el) ?: extractImg(linkEl)

                    episodes.add(
                        Episode(
                            data = fixUrl(link, mainUrl),
                            name = title,
                            season = 1,
                            episode = episodes.size + 1,
                            posterUrl = fixUrlNull(img, mainUrl)
                        )
                    )
                }
            }

            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = fixUrlNull(poster, url)
                this.posterHeaders = defaultHeaders
                this.plot = "Videos for $name on DaftSex"
                this.showStatus = ShowStatus.Completed
            }
        } else {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.selectFirst("h1, .video-title")?.text()?.trim() ?: "DaftSex Video"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: extractImg(doc.selectFirst("video[poster], .player, div.video-thumb, img"))

            val currentId = url.substringAfterLast("/").trim()
            val recommendations = doc.select("a[href*='/movie/']")
                .filter { a ->
                    val h = a.attr("href")
                    h.isNotBlank() && !h.contains(currentId)
                }
                .mapNotNull { parseMovieCard(it) }
                .distinctBy { it.url }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, mainUrl)
                this.posterHeaders = defaultHeaders
                this.recommendations = recommendations
            }
        }
    }

    // 4. STREAM EXTRACTION (ARTPLAYER MULTI-RESOLUTION MP4 360P TO 4K)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var count = 0
        val doc = app.get(data, headers = defaultHeaders).document
        val rawHtml = doc.html()

        // 1. Check for hash-daftsex AJAX player
        val numMatch = Regex("""num:\s*['"]([^'"]+)['"]""").find(rawHtml)?.groupValues?.get(1)
        val mixMatch = Regex("""mix:\s*['"]([^'"]+)['"]""").find(rawHtml)?.groupValues?.get(1) ?: "moviesiframe2"

        if (!numMatch.isNullOrBlank()) {
            val ajaxRes = runCatching {
                app.post(
                    "$mainUrl/hash-daftsex",
                    data = mapOf("mix" to mixMatch, "num" to numMatch),
                    headers = defaultHeaders + mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
            }.getOrNull()

            if (!ajaxRes.isNullOrBlank()) {
                val iframeMatch = Regex("""(?:src=)?['"](https?://[^'"\s]+/iframe/(?:v2/|convert/v2/)[^'"\s]+|/iframe/(?:v2/|convert/v2/)[^'"\s]+)['"]""")
                    .find(ajaxRes)?.groupValues?.get(1)

                if (!iframeMatch.isNullOrBlank()) {
                    val convertPath = iframeMatch.replace("/iframe/v2/", "/iframe/convert/v2/")
                    val playerDomain = if (convertPath.startsWith("http")) convertPath else "https://daftsex-biz.ibhan2.top$convertPath"
                    val playerHtml = runCatching {
                        app.get(playerDomain, headers = mapOf("referer" to "$mainUrl/")).text
                    }.getOrNull()

                    if (!playerHtml.isNullOrBlank()) {
                        // Match Artplayer quality array: { html: '360p', url: '...' }
                        val qualityMatches = Regex("""html:\s*['"]([^'"]+)['"],\s*url:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).findAll(playerHtml)
                        for (qEntry in qualityMatches) {
                            val qLabel = qEntry.groupValues[1].trim()
                            val qUrl = qEntry.groupValues[2].trim()
                            val normalizedQuality = when {
                                qLabel.contains("4k", ignoreCase = true) || qLabel.contains("2160") -> "2160p"
                                qLabel.contains("1080") -> "1080p"
                                qLabel.contains("720") -> "720p"
                                qLabel.contains("480") -> "480p"
                                qLabel.contains("360") -> "360p"
                                else -> qLabel
                            }

                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "$name $qLabel MP4",
                                    url = qUrl,
                                    referer = "$mainUrl/",
                                    quality = getQualityFromName(normalizedQuality),
                                    isM3u8 = qUrl.contains(".m3u8"),
                                    headers = defaultHeaders
                                )
                            )
                            count++
                        }
                    }
                }
            }
        }

        // 2. Direct master .mp4 links in DaftSex page (fallback & direct links)
        if (count == 0) {
            val mp4Matches = Regex("""(https?://daftsex\.biz/movie/[a-zA-Z0-9_\-]+\.mp4)""").findAll(rawHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            for (mp4 in mp4Matches) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name 1080p MP4",
                        url = mp4,
                        referer = "$mainUrl/",
                        quality = getQualityFromName("1080p"),
                        isM3u8 = false,
                        headers = defaultHeaders
                    )
                )
                count++
            }
        }

        return count > 0
    }

    // 5. HELPER CARD PARSERS
    private fun parseMovieCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/movie/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val title = linkEl.selectFirst("div.video-title, .video-title, .title, h1, h2, h3")?.text()?.trim()
            ?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("div.video-title, .video-title")?.text()?.trim()
            ?: return null

        val poster = extractImg(element) ?: extractImg(linkEl)

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseActorCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/video/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val name = linkEl.selectFirst("div.video-title, .video-title, .title, h1, h2, h3")?.text()?.trim()
            ?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("div.video-title, .video-title")?.text()?.trim()
            ?: return null

        val poster = extractImg(element) ?: extractImg(linkEl)

        return newTvSeriesSearchResponse(name, fixUrl(href, mainUrl), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parsePornPicsActorCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/pornstars/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/list/")) return null

        val slug = href.trimEnd('/').substringAfterLast('/')
        val name = element.selectFirst(".name, span.title, .title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        val poster = extractImg(element.selectFirst("img")) ?: extractImg(element)

        return newTvSeriesSearchResponse(name, "$mainUrl/video/$slug", TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster, pornpicsUrl)
            this.posterHeaders = pornpicsHeaders
        }
    }

    private fun extractImg(element: Element?): String? {
        if (element == null) return null

        val target = if (element.hasAttr("data-src") || element.hasAttr("data-original") || element.hasAttr("data-thumb") || element.hasAttr("src")) {
            element
        } else {
            element.selectFirst("div.video-thumb, div[data-src], div[data-original], div[data-thumb], div.thumb, img, [style*='background']") ?: element
        }

        var raw = target.attr("data-src").ifBlank { null }
            ?: target.attr("data-original").ifBlank { null }
            ?: target.attr("data-thumb").ifBlank { null }
            ?: target.attr("data-lazy-src").ifBlank { null }
            ?: target.attr("data-image").ifBlank { null }
            ?: target.attr("content").ifBlank { null }
            ?: target.attr("src").ifBlank { null }

        if (raw.isNullOrBlank()) {
            val style = target.attr("style")
            val styleMatch = Regex("""url\(['"]?([^'"\)]+)['"]?\)""").find(style)
            raw = styleMatch?.groupValues?.get(1)
        }

        return if (raw != null && !raw.startsWith("data:image")) raw else null
    }

    private fun fixUrl(url: String, base: String = mainUrl): String {
        val cleanUrl = url.replace("\\/", "/")
        return when {
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> cleanUrl
            cleanUrl.startsWith("//") -> "https:$cleanUrl"
            cleanUrl.startsWith("/") -> base.trimEnd('/') + cleanUrl
            else -> base.trimEnd('/') + "/" + cleanUrl
        }
    }

    private fun fixUrlNull(url: String?, base: String = mainUrl): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url, base)
    }
}