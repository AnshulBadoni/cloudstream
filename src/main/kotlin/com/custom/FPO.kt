package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * FPO Standalone Provider for CloudStream
 */
class FPO : MainAPI() {
    override var mainUrl = "https://www.fpo.xxx"
    override var name = "FPO"
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
                val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("div.item, div.video-item, a[href*='/videos/'], a[href*='/video/']")?.mapNotNull { parseVideoCard(it) }.orEmpty()
            }

            // Actors / Models (PornPics Trending Models with FPO video profile link)
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
                    val url = if (page <= 1) "$mainUrl/models/" else "$mainUrl/models/page/$page/"
                    val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                    doc?.select("div.item, div.model-item, a[href*='/models/']")?.mapNotNull { parseActorCard(it) }.orEmpty()
                }
            }

            // Studios / Channels (Prioritizing PornPics official logos)
            "studios" -> {
                coroutineScope {
                    TrailerHelper.popularStudios.map { (sName, sSlug) ->
                        async {
                            val ppPoster = TrailerHelper.fetchPornPicsStudioLogo(sSlug)
                            val channelUrl = "$mainUrl/search/$sSlug/"
                            newTvSeriesSearchResponse(sName, channelUrl, TvType.TvSeries) {
                                this.posterUrl = ppPoster
                                this.posterHeaders = if (ppPoster?.contains("pornpics") == true) pornpicsHeaders else defaultHeaders
                            }
                        }
                    }.awaitAll()
                }
            }

            // Studio Specific Channels: Vixen, Blacked, Brazzers
            "vixen" -> {
                val url = if (page <= 1) "$mainUrl/search/vixen/" else "$mainUrl/search/vixen/page/$page/"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("div.item, div.video-item, a[href*='/videos/'], a[href*='/video/']")?.mapNotNull { parseVideoCard(it) }.orEmpty()
            }

            "blacked" -> {
                val url = if (page <= 1) "$mainUrl/search/blacked/" else "$mainUrl/search/blacked/page/$page/"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("div.item, div.video-item, a[href*='/videos/'], a[href*='/video/']")?.mapNotNull { parseVideoCard(it) }.orEmpty()
            }

            else -> {
                val url = if (page <= 1) "$mainUrl/search/brazzers/" else "$mainUrl/search/brazzers/page/$page/"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("div.item, div.video-item, a[href*='/videos/'], a[href*='/video/']")?.mapNotNull { parseVideoCard(it) }.orEmpty()
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
                    val actorDoc = runCatching { app.get("$mainUrl/models/$slugQuery/", headers = defaultHeaders).document }.getOrNull()
                    val actorPoster = extractImg(actorDoc?.selectFirst("div.profile-pic img, .avatar img, div.item img, img"))

                    list.add(
                        newTvSeriesSearchResponse(
                            name = titleCaseQuery,
                            url = "$mainUrl/models/$slugQuery/",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = fixUrlNull(actorPoster, mainUrl)
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
                    val url = if (p <= 1) "$mainUrl/search/$slugQuery/" else "$mainUrl/search/$slugQuery/page/$p/"
                    val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull() ?: break
                    val items = doc.select("div.item, div.video-item, a[href*='/videos/'], a[href*='/video/']").mapNotNull { parseVideoCard(it) }
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
        if (url.startsWith("trailer:")) {
            return newMovieLoadResponse("Trailer / Preview", url, TvType.Movie, url)
        }

        val isPerformer = url.contains("/models/") || (url.contains("/search/") && !url.contains("/video/") && !url.contains("/videos/"))

        if (isPerformer) {
            val rawSlug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val name = doc?.selectFirst("h1")?.text()?.trim()
                ?: rawSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val slug = rawSlug.ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            val ppPoster = TrailerHelper.fetchPornPicsStudioLogo(slug)
            val poster = if (!ppPoster.isNullOrBlank()) {
                ppPoster
            } else {
                extractImg(doc?.selectFirst("meta[property='og:image'], .profile img, .img-holder img, img"))
            }

            val episodes = mutableListOf<Episode>()
            val trailerM3u8 = runCatching {
                TrailerHelper.fetchStudioTrailerM3u8(name) ?: TrailerHelper.fetchModelTrailerM3u8(name)
            }.getOrNull()

            for (p in 1..modelPages.coerceIn(1, 10)) {
                val pageUrl = if (url.contains("/search/")) {
                    if (p <= 1) "$mainUrl/search/$slug/" else "$mainUrl/search/$slug/page/$p/"
                } else {
                    if (p <= 1) "$mainUrl/models/$slug/" else "$mainUrl/models/$slug/page/$p/"
                }
                val pageDoc = runCatching { app.get(pageUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                val cards = pageDoc.select("div.item, div.video-item, a[href*='/videos/'], a[href*='/video/'], div:has(img) a")
                if (cards.isEmpty()) break
                cards.forEach { el ->
                    val linkEl = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@forEach
                    val link = linkEl.attr("href").ifBlank { null } ?: return@forEach
                    if (link.contains("/models/") || link.contains("/tags/") || link == "#") return@forEach

                    val imgEl = el.selectFirst("img") ?: linkEl.selectFirst("img")
                    val title = imgEl?.attr("alt")?.ifBlank { null }
                        ?: linkEl.attr("title").ifBlank { null }
                        ?: el.selectFirst(".title, h2, h3")?.text()?.trim()
                        ?: "FPO Scene ${episodes.size + 1}"
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
                this.posterHeaders = if (poster?.contains("pornpics") == true) pornpicsHeaders else defaultHeaders
                this.plot = "Videos featuring $name on FPO"
                this.showStatus = ShowStatus.Completed
                if (!trailerM3u8.isNullOrBlank()) {
                    this.addTrailer(trailerM3u8)
                }
            }
        } else {
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val title = doc?.selectFirst("h1, .video-title, .title")?.text()?.trim() ?: "FPO Video"
            val poster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc?.selectFirst("video[poster]")?.attr("poster")
                ?: extractImg(doc?.selectFirst("img"))

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, mainUrl)
                this.posterHeaders = defaultHeaders
            }
        }
    }

    // 4. STREAM EXTRACTION (MULTI-RESOLUTION MP4)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (TrailerHelper.handleTrailerStream(data, name, callback)) {
            return true
        }

        var count = 0
        val doc = runCatching { app.get(data, headers = defaultHeaders).document }.getOrNull()
        val rawHtml = doc?.html().orEmpty()

        val streams = Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(rawHtml)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        for (sUrl in streams) {
            val qLabel = when {
                sUrl.contains("2160") || sUrl.contains("4k") || sUrl.contains("4K") -> "4K"
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
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/models/") || href.contains("/tags/")) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val title = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst(".title, h2, h3")?.text()?.trim()
            ?: return null

        val poster = extractImg(imgEl)

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseActorCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || !href.contains("/models/")) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val name = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst(".title, h2, h3, .name")?.text()?.trim()
            ?: return null

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

        return newTvSeriesSearchResponse(name, "$mainUrl/models/$slug/", TvType.TvSeries) {
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
            ?: element.attr("content").ifBlank { null }
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