package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * TnaFlix Standalone Provider for CloudStream
 */
class TnaFlix : MainAPI() {
    override var mainUrl = "https://www.tnaflix.com"
    override var name = "TnaFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries, TvType.Movie)

    private val defaultHeaders = mapOf(
        "referer" to "$mainUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    companion object {
        var searchPages: Int = 2
        var modelPages: Int = 2
    }

    // 1. HOME PAGE CATALOG DEFINITIONS (Clean, No Emojis)
    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "latest" to "Latest",
        "actors" to "Actors",
        "categories" to "Categories"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // Trending Videos
            "trending" -> {
                val url = if (page <= 1) "$mainUrl/trending" else "$mainUrl/trending/$page"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='video']").mapNotNull { parseVideoCard(it) }
            }

            // Latest Videos
            "latest" -> {
                val url = if (page <= 1) "$mainUrl/recent" else "$mainUrl/recent/$page"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='video']").mapNotNull { parseVideoCard(it) }
            }

            // Actors / Models
            "actors" -> {
                val url = if (page <= 1) "$mainUrl/pornstars" else "$mainUrl/pornstars/$page"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/profile/']").mapNotNull { parseActorCard(it) }
            }

            // Categories
            else -> {
                val url = if (page <= 1) "$mainUrl/categories" else "$mainUrl/categories/$page"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='video']").mapNotNull { parseVideoCard(it) }
            }
        }

        val hasNextPage = items.size >= 12
        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = request.data == "actors")
        return newHomePageResponse(homePageList, hasNextPage)
    }

    // 2. SEARCH WITH PERFORMER MATCH PRIORITIZATION
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim().replace(" ", "+")
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
                            url = "$mainUrl/profile/$slugQuery",
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
                    val url = if (p <= 1) "$mainUrl/search?what=$cleanQuery" else "$mainUrl/search?what=$cleanQuery&page=$p"
                    val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull() ?: break
                    val items = doc.select("a[href*='video']").mapNotNull { parseVideoCard(it) }
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

    // 3. LOAD RESPONSE (PERFORMER OR VIDEO)
    override suspend fun load(url: String): LoadResponse {
        val isPerformer = url.contains("/profile/") || url.contains("/pornstar")

        if (isPerformer) {
            val rawSlug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val name = doc?.selectFirst("h1")?.text()?.trim()
                ?: rawSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val slug = rawSlug.ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            val poster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc?.selectFirst(".profile img, .img-holder img, img")?.attr("src")

            val episodes = mutableListOf<Episode>()
            for (p in 1..modelPages.coerceIn(1, 10)) {
                val pageUrl = if (p <= 1) "$mainUrl/profile/$slug" else "$mainUrl/profile/$slug/$p"
                val pageDoc = runCatching { app.get(pageUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                val cards = pageDoc.select("a[href*='video']")
                if (cards.isEmpty()) break
                cards.forEach { el ->
                    val link = el.attr("href").ifBlank { null } ?: return@forEach
                    val title = el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                        ?: el.attr("title").ifBlank { null }
                        ?: "TnaFlix Video ${episodes.size + 1}"
                    val img = el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src")

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
                this.plot = "Videos featuring $name on TnaFlix"
                this.showStatus = ShowStatus.Completed
            }
        } else {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.selectFirst("h1, .video-title")?.text()?.trim() ?: "TnaFlix Video"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("video[poster]")?.attr("poster")

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, mainUrl)
                this.posterHeaders = defaultHeaders
            }
        }
    }

    // 4. STREAM EXTRACTION (MULTI-RESOLUTION MP4 & HLS)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var count = 0
        val doc = runCatching { app.get(data, headers = defaultHeaders).document }.getOrNull()
        val rawHtml = doc?.html().orEmpty()

        val mp4s = Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(rawHtml)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        for (sUrl in mp4s) {
            val qLabel = when {
                sUrl.contains("1080") -> "1080p"
                sUrl.contains("720") -> "720p"
                sUrl.contains("480") -> "480p"
                sUrl.contains("360") -> "360p"
                else -> "720p"
            }

            callback(
                ExtractorLink(
                    source = name,
                    name = "$name $qLabel Stream",
                    url = sUrl,
                    referer = "$mainUrl/",
                    quality = getQualityFromName(qLabel),
                    isM3u8 = sUrl.contains(".m3u8"),
                    headers = defaultHeaders
                )
            )
            count++
        }

        return count > 0
    }

    // 5. HELPER CARD PARSERS
    private fun parseVideoCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='video']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val title = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (title.isBlank()) return null

        val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseActorCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/profile/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val name = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (name.isBlank()) return null

        val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")

        return newTvSeriesSearchResponse(name, fixUrl(href, mainUrl), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
        }
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