package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * PLibrary Scraper for CloudStream / Zangetsu
 *
 * High-Quality Multi-Source Provider:
 * 1. Catalogs:
 *    - 🔥 Trending (YamyHub)
 *    - 👤 Actors (PornPics trending performers with horizontal portrait cards)
 *    - 🏢 Studios (YamyHub /channels/ studio list)
 *    - ⭐ Vixen (YamyHub /channel/vixen/)
 *    - ⭐ Blacked (YamyHub /channel/blacked-porn/)
 *    - ⭐ Brazzers (YamyHub /channel/brazzers-porn/)
 *
 * 2. Performer Multi-Site "Seasons":
 *    - Season 1: YamyHub videos (/pornstar/{slug}/)
 *    - Season 2: DaftSex videos (/video/{slug})
 *    - Season 3: TnaFlix videos (/profile/{slug})
 *
 * 3. Direct Multi-Resolution MP4 Streaming & Fast Downloads:
 *    - YamyHub direct MP4 multi-bitrate streams from Playerjs ([1080]...mp4, [720]...mp4, [360]...mp4)
 *    - DaftSex direct master .mp4 streams (https://daftsex.biz/movie/{id}.mp4)
 *    - TnaFlix direct MP4 and adaptive HLS streams
 */
class PLibrary : MainAPI() {
    override var mainUrl = "https://www.yamyhub.com"
    override var name = "PLibrary"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries, TvType.Movie)

    val yamyUrl = "https://www.yamyhub.com"
    val daftSexUrl = "https://daftsex.biz"
    val tnaFlixUrl = "https://www.tnaflix.com"
    val pornpicsUrl = "https://www.pornpics.de"

    private val defaultHeaders = mapOf(
        "referer" to "$mainUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
    private val daftHeaders = mapOf(
        "referer" to "$daftSexUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
    private val tnaHeaders = mapOf(
        "referer" to "$tnaFlixUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
    private val pornpicsHeaders = mapOf(
        "referer" to "$pornpicsUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    companion object {
        var searchPages: Int = 2
        var yamyModelPages: Int = 2
        var daftModelPages: Int = 2
        var tnaModelPages: Int = 2
    }

    // 1. HOME PAGE CATALOG DEFINITIONS
    override val mainPage = mainPageOf(
        "trending" to "🔥 Trending",
        "actors" to "👤 Actors",
        "studios" to "🏢 Studios",
        "channel/vixen/" to "⭐ Vixen",
        "channel/blacked-porn/" to "⭐ Blacked",
        "channel/brazzers-porn/" to "⭐ Brazzers"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // 🔥 Trending Videos (YamyHub)
            "trending" -> {
                val url = if (page <= 1) "$yamyUrl/" else "$yamyUrl/page/$page/"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/video/']").mapNotNull {
                    parseYamyVideoCard(it)
                }
            }

            // 👤 Actors (PornPics Trending Models with YamyHub fallback)
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
                    // Fallback to YamyHub Pornstars list
                    val yUrl = if (page <= 1) "$yamyUrl/pornstars/" else "$yamyUrl/pornstars/page/$page/"
                    val doc = app.get(yUrl, headers = defaultHeaders).document
                    doc.select("a[href*='/pornstar/']").mapNotNull {
                        parseYamyActorCard(it)
                    }
                }
            }

            // 🏢 Studios / Channels (YamyHub)
            "studios" -> {
                val url = if (page <= 1) "$yamyUrl/channels/" else "$yamyUrl/channels/page/$page/"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/channel/']").mapNotNull {
                    parseYamyChannelCard(it)
                }
            }

            // ⭐ Studio Specific Channels (e.g. Vixen, Blacked, Brazzers)
            else -> {
                val slug = request.data.trimStart('/')
                val url = if (page <= 1) "$yamyUrl/$slug" else "$yamyUrl/${slug}page/$page/"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/video/']").mapNotNull {
                    parseYamyVideoCard(it)
                }
            }
        }

        val hasNextPage = items.size >= 12
        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = request.data == "actors")
        return newHomePageResponse(homePageList, hasNextPage)
    }

    // 2. UNIFIED MULTI-SOURCE SEARCH
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim().replace(" ", "+")
        val slugQuery = query.trim().lowercase().replace(" ", "-")

        // 1. Search actors (PornPics + YamyHub)
        val actorsJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                // Search YamyHub pornstars
                val yUrl = "$yamyUrl/pornstars/?s=$cleanQuery"
                val yDoc = runCatching { app.get(yUrl, headers = defaultHeaders).document }.getOrNull()
                yDoc?.select("a[href*='/pornstar/']")?.mapNotNull { parseYamyActorCard(it) }?.let { list.addAll(it) }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        // 2. Search YamyHub videos (across configured searchPages)
        val yamyJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                for (p in 1..searchPages.coerceIn(1, 4)) {
                    val url = if (p <= 1) "$yamyUrl/search/?s=$cleanQuery" else "$yamyUrl/search/page/$p/?s=$cleanQuery"
                    val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull() ?: break
                    val items = doc.select("a[href*='/video/']").mapNotNull { parseYamyVideoCard(it) }
                    if (items.isEmpty()) break
                    list.addAll(items)
                }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        // 3. Search DaftSex videos
        val daftJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                for (p in 1..searchPages.coerceIn(1, 3)) {
                    val url = if (p <= 1) "$daftSexUrl/video/$slugQuery" else "$daftSexUrl/video/$slugQuery/$p"
                    val doc = runCatching { app.get(url, headers = daftHeaders).document }.getOrNull() ?: break
                    val items = doc.select("a[href*='/movie/'], div.movie-item a").mapNotNull { parseDaftMovieCard(it) }
                    if (items.isEmpty()) break
                    list.addAll(items)
                }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        // 4. Search TnaFlix videos
        val tnaJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                val url = "$tnaFlixUrl/search?what=$cleanQuery"
                val doc = runCatching { app.get(url, headers = tnaHeaders).document }.getOrNull()
                doc?.select("a[href*='video']")?.mapNotNull { parseTnaVideoCard(it) }?.let { list.addAll(it) }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        val actors = actorsJob.await()
        val videos = (yamyJob.await() + daftJob.await() + tnaJob.await()).distinctBy { it.url }

        actors + videos
    }

    // 3. LOAD RESPONSE (PERFORMERS WITH 3 SEASONS OR VIDEOS)
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val isPerformer = url.contains("/pornstar/") || url.contains("/pornstars/") || url.contains("/models/") || url.contains("/profile/")

        if (isPerformer) {
            // === PERFORMER MULTI-SITE SEASONS VIEW ===
            val rawSlug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val name = doc?.selectFirst("h1")?.text()?.trim()
                ?: rawSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val slug = rawSlug.ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            val poster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc?.selectFirst(".profile img, .img-holder img, img")?.attr("src")

            val episodes = mutableListOf<Episode>()

            // Season 1: YamyHub videos (/pornstar/{slug}/)
            val yamyJob = async {
                runCatching {
                    val yList = mutableListOf<Episode>()
                    for (p in 1..yamyModelPages.coerceIn(1, 5)) {
                        val yUrl = if (p <= 1) "$yamyUrl/pornstar/$slug/" else "$yamyUrl/pornstar/$slug/page/$p/"
                        val yDoc = runCatching { app.get(yUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                        val cards = yDoc.select("a[href*='/video/']")
                        if (cards.isEmpty()) break
                        cards.forEachIndexed { idx, el ->
                            val link = el.attr("href").ifBlank { null } ?: return@forEachIndexed
                            val title = el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                                ?: el.attr("title").ifBlank { null }
                                ?: "YamyHub Scene ${yList.size + 1}"
                            val img = el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src")

                            yList.add(
                                Episode(
                                    data = fixUrl(link, yamyUrl),
                                    name = title,
                                    season = 1, // Season 1 = YamyHub
                                    episode = yList.size + 1,
                                    posterUrl = fixUrlNull(img, yamyUrl)
                                )
                            )
                        }
                    }
                    yList.distinctBy { it.data }
                }.getOrDefault(emptyList())
            }

            // Season 2: DaftSex videos (/video/{slug})
            val daftJob = async {
                runCatching {
                    val dList = mutableListOf<Episode>()
                    for (p in 1..daftModelPages.coerceIn(1, 4)) {
                        val dUrl = if (p <= 1) "$daftSexUrl/video/$slug" else "$daftSexUrl/video/$slug/$p"
                        val dDoc = runCatching { app.get(dUrl, headers = daftHeaders).document }.getOrNull() ?: break
                        val cards = dDoc.select("a[href*='/movie/'], div.movie-item a")
                        if (cards.isEmpty()) break
                        cards.forEachIndexed { idx, el ->
                            val link = el.attr("href").ifBlank { null } ?: return@forEachIndexed
                            val title = el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                                ?: el.attr("title").ifBlank { null }
                                ?: "DaftSex Video ${dList.size + 1}"
                            val img = el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src")

                            dList.add(
                                Episode(
                                    data = fixUrl(link, daftSexUrl),
                                    name = title,
                                    season = 2, // Season 2 = DaftSex
                                    episode = dList.size + 1,
                                    posterUrl = fixUrlNull(img, daftSexUrl)
                                )
                            )
                        }
                    }
                    dList.distinctBy { it.data }
                }.getOrDefault(emptyList())
            }

            // Season 3: TnaFlix videos (/profile/{slug})
            val tnaJob = async {
                runCatching {
                    val tList = mutableListOf<Episode>()
                    for (p in 1..tnaModelPages.coerceIn(1, 3)) {
                        val tUrl = if (p <= 1) "$tnaFlixUrl/profile/$slug" else "$tnaFlixUrl/profile/$slug/$p"
                        val tDoc = runCatching { app.get(tUrl, headers = tnaHeaders).document }.getOrNull() ?: break
                        val cards = tDoc.select("a[href*='video']")
                        if (cards.isEmpty()) break
                        cards.forEachIndexed { idx, el ->
                            val link = el.attr("href").ifBlank { null } ?: return@forEachIndexed
                            val title = el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                                ?: el.attr("title").ifBlank { null }
                                ?: "TnaFlix Video ${tList.size + 1}"
                            val img = el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src")

                            tList.add(
                                Episode(
                                    data = fixUrl(link, tnaFlixUrl),
                                    name = title,
                                    season = 3, // Season 3 = TnaFlix
                                    episode = tList.size + 1,
                                    posterUrl = fixUrlNull(img, tnaFlixUrl)
                                )
                            )
                        }
                    }
                    tList.distinctBy { it.data }
                }.getOrDefault(emptyList())
            }

            episodes.addAll(yamyJob.await())
            episodes.addAll(daftJob.await())
            episodes.addAll(tnaJob.await())

            newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster, url)
                this.posterHeaders = defaultHeaders
                this.plot = "PLibrary collection for $name: Season 1 = YamyHub, Season 2 = DaftSex, Season 3 = TnaFlix"
                this.showStatus = ShowStatus.Completed
            }
        } else if (url.contains("daftsex.biz")) {
            // === DAFTSEX VIDEO DETAILS ===
            val doc = app.get(url, headers = daftHeaders).document
            val title = doc.selectFirst("h1, .video-title")?.text()?.trim() ?: "DaftSex Video"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("video[poster]")?.attr("poster")

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, daftSexUrl)
                this.posterHeaders = daftHeaders
            }
        } else {
            // === YAMYHUB VIDEO DETAILS ===
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?: "YamyHub Video"

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".player-holder img, img.thumb")?.attr("src")

            val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst(".video-details, .description")?.text()?.trim()

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, yamyUrl)
                this.posterHeaders = defaultHeaders
                this.plot = description
            }
        }
    }

    // 4. STREAM EXTRACTION (DIRECT MP4 & HLS)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var count = 0

        // 1. DaftSex Direct MP4 Resolver
        if (data.contains("daftsex.biz")) {
            val doc = app.get(data, headers = daftHeaders).document
            val rawHtml = doc.html()

            val mp4Matches = Regex("""(https?://daftsex\.biz/movie/[a-zA-Z0-9_\-]+\.mp4)""").findAll(rawHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            for (mp4 in mp4Matches) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name DaftSex 1080p MP4",
                        url = mp4,
                        referer = "$daftSexUrl/",
                        quality = getQualityFromName("1080p"),
                        isM3u8 = false,
                        headers = daftHeaders
                    )
                )
                count++
            }
            return count > 0
        }

        // 2. YamyHub Playerjs Multi-Resolution MP4 Resolver
        if (data.contains("yamyhub.com")) {
            val doc = app.get(data, headers = defaultHeaders).document
            val iframeSrc = doc.selectFirst("iframe[src*='/player/']")?.attr("src")
                ?: doc.selectFirst("meta[name='twitter:player']")?.attr("content")
                ?: (yamyUrl + "/player/?t=" + data.trimEnd('/').substringAfterLast('/'))

            val playerUrl = fixUrl(iframeSrc, yamyUrl)
            val playerRes = runCatching { app.get(playerUrl, headers = mapOf("referer" to "$yamyUrl/")).text }.getOrNull()

            if (!playerRes.isNullOrBlank()) {
                // Match Playerjs file string: file:"[720]https://...mp4,[360]https://...360p.mp4"
                val filePattern = Regex("""file\s*:\s*["']([^"']+)["']""")
                val fileStr = filePattern.find(playerRes)?.groupValues?.get(1) ?: playerRes

                // Extract each [quality]url pair
                val qualityEntries = Regex("""(?:\[(\d+)\])?(https?://[^\s,"'<>]+)""").findAll(fileStr)
                for (entry in qualityEntries) {
                    val qLabel = entry.groupValues[1].ifBlank { "720" }
                    val streamUrl = entry.groupValues[2]

                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name YamyHub ${qLabel}p MP4",
                            url = streamUrl,
                            referer = "$yamyUrl/",
                            quality = getQualityFromName("${qLabel}p"),
                            isM3u8 = streamUrl.contains(".m3u8"),
                            headers = mapOf("referer" to "$yamyUrl/")
                        )
                    )
                    count++
                }
            }
            return count > 0
        }

        // 3. TnaFlix Video / HLS Stream Resolver
        if (data.contains("tnaflix.com")) {
            val doc = runCatching { app.get(data, headers = tnaHeaders).document }.getOrNull()
            val rawHtml = doc?.html().orEmpty()

            val mp4s = Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(rawHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            for (sUrl in mp4s) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name TnaFlix Stream",
                        url = sUrl,
                        referer = "$tnaFlixUrl/",
                        quality = getQualityFromName("720p"),
                        isM3u8 = sUrl.contains(".m3u8"),
                        headers = tnaHeaders
                    )
                )
                count++
            }
            return count > 0
        }

        return false
    }

    // 5. HELPER CARD PARSERS
    private fun parseYamyVideoCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/video/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/pornstar/") || href.contains("/channel/")) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val title = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst(".title, h2, h3")?.text()?.trim()
            ?: return null

        val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")

        return newMovieSearchResponse(title, fixUrl(href, yamyUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, yamyUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseYamyActorCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/pornstar/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val name = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (name.isBlank()) return null

        val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")

        return newTvSeriesSearchResponse(name, fixUrl(href, yamyUrl), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster, yamyUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseYamyChannelCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/channel/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val name = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (name.isBlank()) return null

        val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")

        return newMovieSearchResponse(name, fixUrl(href, yamyUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, yamyUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parsePornPicsActorCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/pornstars/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/list/")) return null

        val name = element.selectFirst(".name, span.title, .title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        val poster = element.selectFirst("img")?.attr("data-src")
            ?: element.selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(name, fixUrl(href, pornpicsUrl), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster, pornpicsUrl)
            this.posterHeaders = pornpicsHeaders
        }
    }

    private fun parseDaftMovieCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/movie/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val title = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (title.isBlank()) return null

        val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")

        return newMovieSearchResponse(title, fixUrl(href, daftSexUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, daftSexUrl)
            this.posterHeaders = daftHeaders
        }
    }

    private fun parseTnaVideoCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='video']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val title = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.text().trim()
        if (title.isBlank()) return null

        val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")

        return newMovieSearchResponse(title, fixUrl(href, tnaFlixUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, tnaFlixUrl)
            this.posterHeaders = tnaHeaders
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
