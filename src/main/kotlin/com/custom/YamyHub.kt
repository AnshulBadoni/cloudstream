package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * YamyHub Standalone Provider for CloudStream
 */
class YamyHub : MainAPI() {
    override var mainUrl = "https://www.yamyhub.com"
    override var name = "YamyHub"
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
        "channel/vixen/" to "Vixen",
        "channel/blacked-porn/" to "Blacked",
        "channel/brazzers-porn/" to "Brazzers"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // Trending Videos
            "trending" -> {
                val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/video/']").mapNotNull { parseVideoCard(it) }
            }

            // Actors / Models (PornPics Trending Models with YamyHub fallback)
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
                    val yUrl = if (page <= 1) "$mainUrl/pornstars/" else "$mainUrl/pornstars/page/$page/"
                    val doc = app.get(yUrl, headers = defaultHeaders).document
                    doc.select("a[href*='/pornstar/']").mapNotNull { parseActorCard(it) }
                }
            }

            // Studios / Channels (Prioritizing PornPics official logos)
            "studios" -> {
                coroutineScope {
                    TrailerHelper.popularStudios.map { (sName, sSlug) ->
                        async {
                            val ppPoster = TrailerHelper.fetchPornPicsStudioLogo(sSlug)
                            val channelUrl = "$mainUrl/channel/$sSlug/"
                            newTvSeriesSearchResponse(sName, channelUrl, TvType.TvSeries) {
                                this.posterUrl = ppPoster
                                this.posterHeaders = if (ppPoster?.contains("pornpics") == true) pornpicsHeaders else defaultHeaders
                            }
                        }
                    }.awaitAll()
                }
            }

            // Studio Specific Channels (Vixen, Blacked, Brazzers)
            else -> {
                val slug = request.data.trimStart('/')
                val url = if (page <= 1) "$mainUrl/$slug" else "$mainUrl/${slug}page/$page/"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/video/']").mapNotNull { parseVideoCard(it) }
            }
        }

        val hasNextPage = items.size >= 12
        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = request.data != "actors")
        return newHomePageResponse(homePageList, hasNextPage)
    }

    // 2. SEARCH WITH PERFORMER MATCH PRIORITIZATION
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim().replace(" ", "+")
        val slugQuery = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val queryWords = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val titleCaseQuery = queryWords.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        // 1. Search actors (Actor Search + Synthetic Performer Card)
        val actorsJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                val yUrl = "$mainUrl/pornstars/?s=$cleanQuery"
                val doc = runCatching { app.get(yUrl, headers = defaultHeaders).document }.getOrNull()
                val realActors = doc?.select("a[href*='/pornstar/']")?.mapNotNull { parseActorCard(it) }.orEmpty()

                if (realActors.isNotEmpty()) {
                    list.addAll(realActors)
                } else if (queryWords.size in 1..4 && slugQuery.isNotBlank()) {
                    val actorDoc = runCatching { app.get("$mainUrl/pornstar/$slugQuery/", headers = defaultHeaders).document }.getOrNull()
                    val actorPoster = extractImg(actorDoc?.selectFirst("div.profile-pic img, .img-holder img, meta[property='og:image'], img"))

                    list.add(
                        newTvSeriesSearchResponse(
                            name = titleCaseQuery,
                            url = "$mainUrl/pornstar/$slugQuery/",
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
                    val url = if (p <= 1) "$mainUrl/search/?s=$cleanQuery" else "$mainUrl/search/page/$p/?s=$cleanQuery"
                    val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull() ?: break
                    val items = doc.select("a[href*='/video/']").mapNotNull { parseVideoCard(it) }
                    if (items.isEmpty()) break
                    list.addAll(items)
                }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        val actors = actorsJob.await()
        val videos = videosJob.await()

        // Place Performer card at index 0 (TvType.TvSeries)
        (actors + videos).distinctBy { it.url }
    }

    // 3. LOAD RESPONSE (PERFORMER/CHANNEL OR VIDEO)
    override suspend fun load(url: String): LoadResponse {
        if (url.startsWith("trailer:")) {
            return newMovieLoadResponse("Trailer / Preview", url, TvType.Movie, url)
        }

        val isPerformerOrChannel = url.contains("/pornstar/") || url.contains("/pornstars/") || url.contains("/channel/") || url.contains("/channels/")

        if (isPerformerOrChannel) {
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
                doc?.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: extractImg(doc?.selectFirst(".profile img, .img-holder img, img"))
            }

            val episodes = mutableListOf<Episode>()

            val prefix = if (url.contains("/channel/")) "channel" else "pornstar"
            for (p in 1..modelPages.coerceIn(1, 10)) {
                val pageUrl = if (p <= 1) "$mainUrl/$prefix/$slug/" else "$mainUrl/$prefix/$slug/page/$p/"
                val pageDoc = runCatching { app.get(pageUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                val cards = pageDoc.select("a[href*='/video/']")
                if (cards.isEmpty()) break
                cards.forEach { el ->
                    val link = el.attr("href").ifBlank { null } ?: return@forEach
                    val imgEl = el.selectFirst("img")
                    val title = imgEl?.attr("alt")?.ifBlank { null }
                        ?: el.attr("title").ifBlank { null }
                        ?: "Scene ${episodes.size + 1}"
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
                this.plot = "Videos featuring $name on YamyHub"
                this.showStatus = ShowStatus.Completed
            }
        } else {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?: "YamyHub Video"

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: extractImg(doc.selectFirst(".player-holder img, img.thumb, .player img"))

            val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst(".video-details, .description")?.text()?.trim()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, mainUrl)
                this.posterHeaders = defaultHeaders
                this.plot = description
            }
        }
    }

    // 4. STREAM EXTRACTION (PLAYERJS MULTI-RESOLUTION MP4)
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
        val doc = app.get(data, headers = defaultHeaders).document
        val iframeSrc = doc.selectFirst("iframe[src*='/player/']")?.attr("src")
            ?: doc.selectFirst("meta[name='twitter:player']")?.attr("content")
            ?: (mainUrl + "/player/?t=" + data.trimEnd('/').substringAfterLast('/'))

        val playerUrl = fixUrl(iframeSrc, mainUrl)
        val playerRes = runCatching { app.get(playerUrl, headers = mapOf("referer" to "$mainUrl/")).text }.getOrNull()

        if (!playerRes.isNullOrBlank()) {
            val filePattern = Regex("""file\s*:\s*["']([^"']+)["']""")
            val fileStr = filePattern.find(playerRes)?.groupValues?.get(1) ?: playerRes

            val qualityEntries = Regex("""(?:\[(\d+)\])?(https?://[^\s,"'<>]+)""").findAll(fileStr)
            for (entry in qualityEntries) {
                val qLabel = entry.groupValues[1].ifBlank { "720" }
                val streamUrl = entry.groupValues[2]

                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name ${qLabel}p MP4",
                        url = streamUrl,
                        referer = "$mainUrl/",
                        quality = getQualityFromName("${qLabel}p"),
                        isM3u8 = streamUrl.contains(".m3u8"),
                        headers = mapOf("referer" to "$mainUrl/")
                    )
                )
                count++
            }
        }
        return count > 0
    }

    // 5. HELPER CARD PARSERS
    private fun parseVideoCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/video/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/pornstar/") || href.contains("/channel/")) return null

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
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/pornstar/']") ?: return null
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

        return newTvSeriesSearchResponse(name, "$mainUrl/pornstar/$slug/", TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster, pornpicsUrl)
            this.posterHeaders = pornpicsHeaders
        }
    }

    private fun parseChannelCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/channel/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val name = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (name.isBlank()) return null

        val poster = extractImg(imgEl)

        return newMovieSearchResponse(name, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
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