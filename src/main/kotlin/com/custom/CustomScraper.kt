package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

class CustomScraper : MainAPI() {
    override var mainUrl = "https://xmovix.net"
    override var name = "Multi-Source"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    val pornpicsUrl = "https://www.pornpics.de"
    val porntrexUrl = "https://www.porntrex.com"

    // 1. Home Page Catalog Rows
    override val mainPage = mainPageOf(
        "en/movies" to "Trending",
        "pornpics_models" to "Models",
        "en/movies/porno-parodies" to "Parodies",
        "en/movies/hd-1080p" to "Full HD",
        "en/top.html" to "Top 100"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // [Row 2] Models from PornPics
            "pornpics_models" -> {
                val url = if (page <= 1) {
                    "$pornpicsUrl/pornstars/?gender=female&s=trending"
                } else {
                    "$pornpicsUrl/pornstars/?gender=female&s=trending&page=$page"
                }
                val doc = app.get(url, headers = mapOf("referer" to "$pornpicsUrl/")).document
                doc.select("ul#tiles li.thumbwook, #tiles li, li.thumbwook").mapNotNull {
                    parsePornpicsModel(it)
                }
            }

            // [Row 5] Top 100 from xmovix
            "en/top.html" -> {
                val url = "$mainUrl/en/top.html"
                val doc = app.get(url, headers = mapOf("referer" to "$mainUrl/")).document
                doc.select(".top100-items li.top100-item, .top100-box li, li.top100-item").mapNotNull {
                    parseXmovixTop100Item(it)
                }
            }

            // [Rows 1, 3, 4] Trending, Parodies, Full HD from xmovix
            else -> {
                val path = request.data.trim('/')
                val url = if (page <= 1) {
                    "$mainUrl/$path/"
                } else {
                    "$mainUrl/$path/page/$page/"
                }
                val doc = app.get(url, headers = mapOf("referer" to "$mainUrl/")).document
                doc.select(".floats .short, .sect .short, div.short").mapNotNull {
                    parseXmovixShortItem(it)
                }
            }
        }

        val homePageList = HomePageList(request.name, items, isHorizontalImages = true)
        return newHomePageResponse(homePageList, hasNext = items.isNotEmpty())
    }

    // 2. Search: Models FIRST, then Videos
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim()
        val querySlug = cleanQuery.replace(" ", "-").lowercase()
        val querySearch = cleanQuery.replace(" ", "+")

        // 2a. Search Models in parallel (PornPics + PornTrex models)
        val modelSearchJob = async {
            val results = mutableListOf<SearchResponse>()
            
            // Direct model match from PornPics
            runCatching {
                val directUrl = "$pornpicsUrl/pornstars/$querySlug/"
                val doc = app.get(directUrl, headers = mapOf("referer" to "$pornpicsUrl/")).document
                val name = doc.selectFirst("h1.model-h1, h1")?.text()?.trim()
                if (!name.isNullOrBlank()) {
                    val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"), pornpicsUrl)
                    results.add(
                        newTvSeriesSearchResponse(name, directUrl, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.posterHeaders = mapOf("referer" to "$pornpicsUrl/")
                        }
                    )
                }
            }

            // Direct model match from PornTrex
            runCatching {
                val pDirectUrl = "$porntrexUrl/models/$querySlug/"
                val pDoc = app.get(pDirectUrl, headers = mapOf("referer" to "$porntrexUrl/")).document
                val pName = pDoc.selectFirst("h1")?.text()?.trim()
                if (!pName.isNullOrBlank() && pName.contains(cleanQuery, ignoreCase = true)) {
                    val pPoster = fixUrlNull(pDoc.selectFirst("meta[property='og:image'], .profile-model-info img")?.attr("content") ?: pDoc.selectFirst(".profile-model-info img")?.attr("src"), porntrexUrl)
                    results.add(
                        newTvSeriesSearchResponse(pName, pDirectUrl, TvType.TvSeries) {
                            this.posterUrl = pPoster
                            this.posterHeaders = mapOf("referer" to "$porntrexUrl/")
                        }
                    )
                }
            }

            results.distinctBy { it.name.lowercase().trim() }
        }

        // 2b. Search Videos (xmovix + porntrex)
        val videoSearchJob = async {
            val results = mutableListOf<SearchResponse>()
            runCatching {
                val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=$querySearch"
                val doc = app.get(searchUrl, headers = mapOf("referer" to "$mainUrl/")).document
                val xmovixVideos = doc.select(".floats .short, .sect .short, div.short").mapNotNull {
                    parseXmovixShortItem(it)
                }
                results.addAll(xmovixVideos)
            }
            results
        }

        val models = modelSearchJob.await()
        val videos = videoSearchJob.await()

        // Prioritize Models at the top, then Videos!
        models + videos
    }

    // 3. Load Details: If Model Profile -> Aggregate Multiple Sites as Seasons (Season 1 = PornTrex, Season 2 = xmovix)
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val isModelProfile = url.contains("/pornstars/") || url.contains("/models/") || url.contains("/model/")

        if (isModelProfile) {
            // Extract model name and slug
            val slug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val rawName = slug.replace("-", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val doc = runCatching { app.get(url).document }.getOrNull()
            val name = doc?.selectFirst("h1.model-h1, .profile-model-info h1, h1")?.text()?.trim() ?: rawName
            val poster = fixUrlNull(
                doc?.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc?.selectFirst(".profile-model-info img, img")?.attr("src"),
                url
            )

            val episodes = mutableListOf<Episode>()

            // === Season 1: PornTrex Videos ===
            val porntrexJob = async {
                runCatching {
                    val pUrl = "$porntrexUrl/models/$slug/"
                    val pDoc = app.get(pUrl, headers = mapOf("referer" to "$porntrexUrl/")).document
                    val pElements = pDoc.select("div.video-list div.video-item, .list-videos .item, .item")
                    pElements.mapIndexedNotNull { index, el ->
                        val link = el.selectFirst("a[href*='/video/'], a")?.attr("href") ?: return@mapIndexedNotNull null
                        val title = el.selectFirst("strong.title, .title")?.text()?.trim() ?: "PornTrex Video ${index + 1}"
                        val pPoster = fixUrlNull(el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src"), porntrexUrl)
                        val duration = el.selectFirst(".duration, .durations, .time")?.text()?.trim()

                        Episode(
                            data = fixUrl(link, porntrexUrl),
                            name = title,
                            season = 1, // Season 1 = PornTrex
                            episode = index + 1,
                            posterUrl = pPoster,
                            description = duration
                        )
                    }
                }.getOrDefault(emptyList())
            }

            // === Season 2: xmovix.net Videos ===
            val xmovixJob = async {
                runCatching {
                    val xSearchUrl = "$mainUrl/index.php?do=search&subaction=search&story=${slug.replace("-", "+")}"
                    val xDoc = app.get(xSearchUrl, headers = mapOf("referer" to "$mainUrl/")).document
                    val xElements = xDoc.select(".floats .short, .sect .short, div.short")
                    xElements.mapIndexedNotNull { index, el ->
                        val link = el.selectFirst("a.th-title, a.short-poster, a[href]")?.attr("href") ?: return@mapIndexedNotNull null
                        val title = el.selectFirst("a.th-title, .title-box a")?.text()?.trim() ?: "xmovix Video ${index + 1}"
                        val xPoster = fixUrlNull(el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src"), mainUrl)

                        Episode(
                            data = fixUrl(link, mainUrl),
                            name = title,
                            season = 2, // Season 2 = xmovix
                            episode = index + 1,
                            posterUrl = xPoster
                        )
                    }
                }.getOrDefault(emptyList())
            }

            episodes.addAll(porntrexJob.await())
            episodes.addAll(xmovixJob.await())

            newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("referer" to "$mainUrl/")
                this.plot = "Multi-Source Collection for $name: Season 1 = PornTrex, Season 2 = xmovix"
                this.showStatus = ShowStatus.Completed
            }
        } else {
            // Standard Video Load
            val doc = app.get(url, headers = mapOf("referer" to "$mainUrl/")).document

            val title = doc.selectFirst("h1.title, .entry-title, h1, .full-title")?.text()?.trim() ?: "Movie"
            val poster = fixUrlNull(
                doc.selectFirst(".full-poster img, .poster img, meta[property='og:image']")?.attr("src")
                    ?: doc.selectFirst(".full-poster img, .poster img")?.attr("data-src")
            )
            val description = doc.selectFirst(".full-text, .full-desc, .description, .plot")?.text()?.trim()
            val year = doc.selectFirst(".year, .release-date")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            val tags = doc.select(".full-tags a, .genres a, .tags a").map { it.text().trim() }.filter { it.isNotBlank() }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("referer" to "$mainUrl/")
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    // 4. Stream Links Extraction
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Check if data is from PornTrex
        if (data.contains("porntrex.com")) {
            val response = doc.html()
            val urlRegex = Regex("""(video_url|video_alt_url\d*)\s*:\s*['"]([^'"]+)['"]""")
            urlRegex.findAll(response).forEach { match ->
                val streamUrl = match.groupValues[2]
                if (streamUrl.startsWith("http")) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name PornTrex",
                            url = streamUrl,
                            referer = "$porntrexUrl/",
                            quality = Qualities.P720.value,
                            isM3u8 = streamUrl.contains(".m3u8")
                        )
                    )
                }
            }
            return true
        }

        // xmovix / general video streams
        val iframes = doc.select("iframe[src]").map { it.attr("src") }
        for (iframeUrl in iframes) {
            val fixed = fixUrl(iframeUrl)
            if (fixed.contains("m3u8") || fixed.contains(".mp4")) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Stream",
                        url = fixed,
                        referer = "$mainUrl/",
                        quality = Qualities.P1080.value,
                        isM3u8 = fixed.contains(".m3u8")
                    )
                )
            }
        }
        return true
    }

    // --- Parsers ---
    private fun parseXmovixShortItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a.th-title, a.short-poster, a[href]") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank()) return null

        val title = element.selectFirst("a.th-title, .title-box a")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        val posterEl = element.selectFirst("img")
        val rawPoster = posterEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: posterEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val poster = fixUrlNull(rawPoster, mainUrl)

        val qualityTag = element.selectFirst(".short-meta, .short-label")?.text()?.trim()
        val quality = if (qualityTag?.contains("Full HD", ignoreCase = true) == true || qualityTag?.contains("1080", ignoreCase = true) == true) {
            SearchQuality.FourK
        } else {
            SearchQuality.WebRip
        }

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = poster
            this.quality = quality
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    private fun parseXmovixTop100Item(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank()) return null

        val title = element.selectFirst(".top100-name")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        val posterEl = element.selectFirst("img")
        val rawPoster = posterEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: posterEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val poster = fixUrlNull(rawPoster, mainUrl)

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    private fun parsePornpicsModel(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a.rel-link, a[href*='/pornstars/'], a") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank()) return null

        val name = element.selectFirst("span.m-name")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        val posterEl = element.selectFirst("img")
        val rawPoster = posterEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: posterEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val poster = fixUrlNull(rawPoster, pornpicsUrl)

        return newTvSeriesSearchResponse(name, fixUrl(href, pornpicsUrl), TvType.TvSeries) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "referer" to "$pornpicsUrl/",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }
    }

    private fun fixUrl(url: String, base: String = mainUrl): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> base.trimEnd('/') + url
            else -> base.trimEnd('/') + "/" + url
        }
    }

    private fun fixUrlNull(url: String?, base: String = mainUrl): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url, base)
    }
}