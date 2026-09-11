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
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/movie/'], div.movie-item a").mapNotNull { parseMovieCard(it) }
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
                    val doc = app.get("$mainUrl/models", headers = defaultHeaders).document
                    doc.select("a[href*='/video/']").mapNotNull { parseActorCard(it) }
                }
            }

            // Studios / Channels Directory
            "studios" -> {
                val url = if (page <= 1) "$mainUrl/channels" else "$mainUrl/channels/$page"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/channel/'], a[href*='/movie/'], div.channel-item a").mapNotNull { parseMovieCard(it) }
            }

            // Studio Specific Channels: Vixen, Blacked, Brazzers
            "vixen" -> {
                val url = if (page <= 1) "$mainUrl/video/vixen" else "$mainUrl/video/vixen/$page"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/movie/'], div.movie-item a").mapNotNull { parseMovieCard(it) }
            }

            "blacked" -> {
                val url = if (page <= 1) "$mainUrl/video/blacked" else "$mainUrl/video/blacked/$page"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/movie/'], div.movie-item a").mapNotNull { parseMovieCard(it) }
            }

            else -> {
                val url = if (page <= 1) "$mainUrl/video/brazzers" else "$mainUrl/video/brazzers/$page"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/movie/'], div.movie-item a").mapNotNull { parseMovieCard(it) }
            }
        }

        val hasNextPage = items.size >= 12
        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = request.data == "actors")
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

    // 3. LOAD RESPONSE (PERFORMER OR VIDEO)
    override suspend fun load(url: String): LoadResponse {
        val isPerformer = url.contains("/video/") && !url.contains("/movie/")

        if (isPerformer) {
            val rawSlug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val name = doc?.selectFirst("h1")?.text()?.trim()
                ?: rawSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val slug = rawSlug.ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            val poster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: extractImg(doc?.selectFirst(".profile img, .img-holder img, img"))

            val episodes = mutableListOf<Episode>()
            for (p in 1..modelPages.coerceIn(1, 10)) {
                val pageUrl = if (p <= 1) "$mainUrl/video/$slug" else "$mainUrl/video/$slug/$p"
                val pageDoc = runCatching { app.get(pageUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                val cards = pageDoc.select("a[href*='/movie/'], div.movie-item a")
                if (cards.isEmpty()) break
                cards.forEach { el ->
                    val link = el.attr("href").ifBlank { null } ?: return@forEach
                    val imgEl = el.selectFirst("img")
                    val title = imgEl?.attr("alt")?.ifBlank { null }
                        ?: el.attr("title").ifBlank { null }
                        ?: "DaftSex Video ${episodes.size + 1}"
                    val img = extractImg(imgEl)

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
                this.plot = "Videos featuring $name on DaftSex"
                this.showStatus = ShowStatus.Completed
            }
        } else {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.selectFirst("h1, .video-title")?.text()?.trim() ?: "DaftSex Video"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: extractImg(doc.selectFirst("video[poster], .player img, img"))

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, mainUrl)
                this.posterHeaders = defaultHeaders
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
                    headers = defaultHeaders
                ).text
            }.getOrNull()

            if (!ajaxRes.isNullOrBlank()) {
                val iframePath = Regex("""(?:src=)?['"](/iframe/(?:v2/|convert/v2/)?([^'"]+))['"]""").find(ajaxRes)?.groupValues?.get(1)
                    ?: Regex("""(/iframe/v2/[^'"]+)""").find(ajaxRes)?.groupValues?.get(1)

                if (!iframePath.isNullOrBlank()) {
                    val convertPath = iframePath.replace("/iframe/v2/", "/iframe/convert/v2/")
                    val playerDomain = if (convertPath.startsWith("http")) convertPath else "https://daftsex-biz.ibhan2.top$convertPath"
                    val playerHtml = runCatching {
                        app.get(playerDomain, headers = mapOf("referer" to "$mainUrl/")).text
                    }.getOrNull()

                    if (!playerHtml.isNullOrBlank()) {
                        // Match Artplayer quality array: { html: '360p', url: '...' }
                        val qualityMatches = Regex("""html:\s*['"]([^'"]+)['"],\s*url:\s*['"]([^'"]+)['"]""").findAll(playerHtml)
                        for (qEntry in qualityMatches) {
                            val qLabel = qEntry.groupValues[1]
                            val qUrl = qEntry.groupValues[2]
                            val normalizedQuality = if (qLabel.equals("4k", ignoreCase = true)) "2160p" else qLabel

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

        return count > 0
    }

    // 5. HELPER CARD PARSERS
    private fun parseMovieCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/movie/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val title = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (title.isBlank()) return null

        val poster = extractImg(imgEl)

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseActorCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/video/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val name = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (name.isBlank()) return null

        val poster = extractImg(imgEl)

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

        val poster = extractImg(element.selectFirst("img"))

        return newTvSeriesSearchResponse(name, "$mainUrl/video/$slug", TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster, pornpicsUrl)
            this.posterHeaders = pornpicsHeaders
        }
    }

    private fun extractImg(element: Element?): String? {
        if (element == null) return null
        val raw = element.attr("data-src").ifBlank { null }
            ?: element.attr("data-original").ifBlank { null }
            ?: element.attr("data-thumb").ifBlank { null }
            ?: element.attr("data-lazy-src").ifBlank { null }
            ?: element.attr("data-image").ifBlank { null }
            ?: element.attr("src").ifBlank { null }
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