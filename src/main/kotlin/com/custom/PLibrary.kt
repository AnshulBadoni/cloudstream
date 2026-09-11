package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.*
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
 *    - Season 4: FPO videos (https://www.fpo.xxx/models/{slug}/)
 *
 * 3. Search Model Prioritization:
 *    - Searching performer names places matching model profile cards (TvType.TvSeries) at the top.
 *
 * 4. Direct Multi-Resolution MP4 Streaming & Fast Downloads:
 *    - YamyHub Playerjs multi-bitrate streams ([1080]...mp4, [720]...mp4, [360]...mp4)
 *    - DaftSex ArtPlayer multi-quality direct MP4s (360p, 480p, 720p, 1080p, 4K)
 *    - TnaFlix direct MP4 and adaptive HLS streams
 *    - FPO direct MP4 streams
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
    val fpoUrl = "https://www.fpo.xxx"

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
    private val fpoHeaders = mapOf(
        "referer" to "$fpoUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    companion object {
        var searchPages: Int = 2
        var yamyModelPages: Int = 2
        var daftModelPages: Int = 2
        var tnaModelPages: Int = 2
        var fpoModelPages: Int = 2
    }

    // 1. HOME PAGE CATALOG DEFINITIONS
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
            // Trending Videos (YamyHub)
            "trending" -> {
                val url = if (page <= 1) "$yamyUrl/" else "$yamyUrl/page/$page/"
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/video/']").mapNotNull {
                    parseYamyVideoCard(it)
                }
            }

            // Actors (PornPics Trending Models with YamyHub fallback)
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

            // Studios (Prioritizing PornPics official logos)
            "studios" -> {
                coroutineScope {
                    TrailerHelper.popularStudios.map { (sName, sSlug) ->
                        async {
                            val ppPoster = TrailerHelper.fetchPornPicsStudioLogo(sSlug)
                            val channelUrl = "$yamyUrl/channel/$sSlug/"
                            newTvSeriesSearchResponse(sName, channelUrl, TvType.TvSeries) {
                                this.posterUrl = ppPoster
                                this.posterHeaders = if (ppPoster?.contains("pornpics") == true) pornpicsHeaders else defaultHeaders
                            }
                        }
                    }.awaitAll()
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

    // 2. UNIFIED MULTI-SOURCE SEARCH WITH PERFORMER MATCH PRIORITIZATION
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim().replace(" ", "+")
        val slugQuery = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val queryWords = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val titleCaseQuery = queryWords.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        // 1. Search actors (PornPics + YamyHub + Synthetic Performer Card)
        val actorsJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()

                // Direct model card synthesis for quick performer matches
                if (queryWords.size in 1..4 && slugQuery.isNotBlank()) {
                    list.add(
                        newTvSeriesSearchResponse(
                            name = titleCaseQuery,
                            url = "$yamyUrl/pornstar/$slugQuery/",
                            type = TvType.TvSeries
                        ) {
                            this.posterHeaders = defaultHeaders
                        }
                    )
                }

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

        // 5. Search FPO videos
        val fpoJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                val url = "$fpoUrl/search/$slugQuery/"
                val doc = runCatching { app.get(url, headers = fpoHeaders).document }.getOrNull()
                doc?.select("div.item, div.video-item, a[href*='/videos/'], a[href*='/video/']")?.mapNotNull { parseFpoVideoCard(it) }?.let { list.addAll(it) }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        val actors = actorsJob.await()
        val videos = (yamyJob.await() + daftJob.await() + tnaJob.await() + fpoJob.await()).distinctBy { it.url }

        // Place Performer / Model profiles at index 0 (TvType.TvSeries)
        (actors + videos).distinctBy { it.url }
    }

    // 3. LOAD RESPONSE (PERFORMERS WITH 4 SEASONS OR VIDEOS)
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
            val trailerM3u8 = runCatching {
                TrailerHelper.fetchStudioTrailerM3u8(name) ?: TrailerHelper.fetchModelTrailerM3u8(name)
            }.getOrNull()

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

            // Season 4: FPO videos (/models/{slug}/)
            val fpoModelJob = async {
                runCatching {
                    val fList = mutableListOf<Episode>()
                    for (p in 1..fpoModelPages.coerceIn(1, 3)) {
                        val fUrl = if (p <= 1) "$fpoUrl/models/$slug/" else "$fpoUrl/models/$slug/page/$p/"
                        val fDoc = runCatching { app.get(fUrl, headers = fpoHeaders).document }.getOrNull() ?: break
                        val cards = fDoc.select("div.item, div.video-item, a[href*='/videos/'], a[href*='/video/'], div:has(img) a")
                        if (cards.isEmpty()) break
                        cards.forEachIndexed { idx, el ->
                            val linkEl = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@forEachIndexed
                            val link = linkEl.attr("href").ifBlank { null } ?: return@forEachIndexed
                            if (link.contains("/models/") || link.contains("/tags/") || link == "#") return@forEachIndexed

                            val imgEl = el.selectFirst("img") ?: linkEl.selectFirst("img")
                            val title = imgEl?.attr("alt")?.ifBlank { null }
                                ?: linkEl.attr("title").ifBlank { null }
                                ?: el.selectFirst(".title, h2, h3")?.text()?.trim()
                                ?: "FPO Scene ${fList.size + 1}"
                            val img = imgEl?.attr("data-src") ?: imgEl?.attr("src")

                            fList.add(
                                Episode(
                                    data = fixUrl(link, fpoUrl),
                                    name = title,
                                    season = 4, // Season 4 = FPO
                                    episode = fList.size + 1,
                                    posterUrl = fixUrlNull(img, fpoUrl)
                                )
                            )
                        }
                    }
                    fList.distinctBy { it.data }
                }.getOrDefault(emptyList())
            }

            episodes.addAll(yamyJob.await())
            episodes.addAll(daftJob.await())
            episodes.addAll(tnaJob.await())
            episodes.addAll(fpoModelJob.await())

            newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster, url)
                this.posterHeaders = defaultHeaders
                this.plot = "PLibrary collection for $name: Season 1 = YamyHub, Season 2 = DaftSex, Season 3 = TnaFlix, Season 4 = FPO"
                this.showStatus = ShowStatus.Completed
                if (!trailerM3u8.isNullOrBlank()) {
                    this.addTrailer(trailerM3u8)
                }
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
        } else if (url.contains("fpo.xxx")) {
            // === FPO VIDEO DETAILS ===
            val doc = app.get(url, headers = fpoHeaders).document
            val title = doc.selectFirst("h1, .video-title, .title")?.text()?.trim() ?: "FPO Video"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("video[poster]")?.attr("poster")

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, fpoUrl)
                this.posterHeaders = fpoHeaders
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

    // 4. STREAM EXTRACTION (DIRECT MP4 & MULTI-RESOLUTION HLS)
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

        // 1. DaftSex Multi-Quality Stream Resolver (360p to 4K)
        if (data.contains("daftsex.biz")) {
            val doc = app.get(data, headers = daftHeaders).document
            val rawHtml = doc.html()

            // A. Check for hash-daftsex AJAX player
            val numMatch = Regex("""num:\s*['"]([^'"]+)['"]""").find(rawHtml)?.groupValues?.get(1)
            val mixMatch = Regex("""mix:\s*['"]([^'"]+)['"]""").find(rawHtml)?.groupValues?.get(1) ?: "moviesiframe2"

            if (!numMatch.isNullOrBlank()) {
                val ajaxRes = runCatching {
                    app.post(
                        "$daftSexUrl/hash-daftsex",
                        data = mapOf("mix" to mixMatch, "num" to numMatch),
                        headers = daftHeaders
                    ).text
                }.getOrNull()

                if (!ajaxRes.isNullOrBlank()) {
                    val iframePath = Regex("""(?:src=)?['"](/iframe/(?:v2/|convert/v2/)?([^'"]+))['"]""").find(ajaxRes)?.groupValues?.get(1)
                        ?: Regex("""(/iframe/v2/[^'"]+)""").find(ajaxRes)?.groupValues?.get(1)

                    if (!iframePath.isNullOrBlank()) {
                        val convertPath = iframePath.replace("/iframe/v2/", "/iframe/convert/v2/")
                        val playerDomain = if (convertPath.startsWith("http")) convertPath else "https://daftsex-biz.ibhan2.top$convertPath"
                        val playerHtml = runCatching {
                            app.get(playerDomain, headers = mapOf("referer" to "$daftSexUrl/")).text
                        }.getOrNull()

                        if (!playerHtml.isNullOrBlank()) {
                            // Match Artplayer quality array: { html: '360p', url: '...' }
                            val qualityMatches = Regex("""html:\s*['"]([^'"]+)['"],\s*url:\s*['"]([^'"]+)['"]""").findAll(playerHtml)
                            for (qEntry in qualityMatches) {
                                val qLabel = qEntry.groupValues[1] // e.g. 360p, 480p, 720p, 1080p, 4K
                                val qUrl = qEntry.groupValues[2]
                                val normalizedQuality = if (qLabel.equals("4k", ignoreCase = true)) "2160p" else qLabel

                                callback(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name DaftSex $qLabel MP4",
                                        url = qUrl,
                                        referer = "$daftSexUrl/",
                                        quality = getQualityFromName(normalizedQuality),
                                        isM3u8 = qUrl.contains(".m3u8"),
                                        headers = daftHeaders
                                    )
                                )
                                count++
                            }
                        }
                    }
                }
            }

            // B. Direct master .mp4 links in DaftSex page (fallback & direct links)
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

        // 2. YamyHub Playerjs Multi-Resolution MP4 Resolver (360p to 1080p)
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

        // 3. TnaFlix Video / Multi-Quality Stream Resolver
        if (data.contains("tnaflix.com")) {
            val doc = runCatching { app.get(data, headers = tnaHeaders).document }.getOrNull()
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
                        name = "$name TnaFlix $qLabel Stream",
                        url = sUrl,
                        referer = "$tnaFlixUrl/",
                        quality = getQualityFromName(qLabel),
                        isM3u8 = sUrl.contains(".m3u8"),
                        headers = tnaHeaders
                    )
                )
                count++
            }
            return count > 0
        }

        // 4. FPO Video Stream Resolver
        if (data.contains("fpo.xxx")) {
            val doc = runCatching { app.get(data, headers = fpoHeaders).document }.getOrNull()
            val rawHtml = doc?.html().orEmpty()

            val streams = Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(rawHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            for (sUrl in streams) {
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
                        name = "$name FPO $qLabel Stream",
                        url = sUrl,
                        referer = "$fpoUrl/",
                        quality = getQualityFromName(qLabel),
                        isM3u8 = sUrl.contains(".m3u8"),
                        headers = fpoHeaders
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

    private fun parseFpoVideoCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/models/") || href.contains("/categories/") || href.contains("/tags/")) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val title = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst(".title, h2, h3")?.text()?.trim()
            ?: return null

        val poster = imgEl?.attr("data-src") ?: imgEl?.attr("src")

        return newMovieSearchResponse(title, fixUrl(href, fpoUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, fpoUrl)
            this.posterHeaders = fpoHeaders
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
