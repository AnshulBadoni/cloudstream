package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * MultiSource Scraper for CloudStream / Zangetsu
 *
 * Architecture & Features:
 * 1. Multi-Source Aggregated Home Page:
 *    - Row 1: "Trending" (xmovix.net, randomized across 1..315 pages on launch)
 *    - Row 2: "Models" (pornpics.de trending performer directory)
 *    - Row 3: "Parodies" (xmovix.net, randomized across 1..6 pages)
 *    - Row 4: "Full HD" (xmovix.net, randomized across 1..114 pages)
 *    - Row 5: "Top 100" (xmovix.net top-rated ranking list)
 *
 * 2. Model-First Search:
 *    - Prioritizes performer profile matches from PornPics and PornTrex at the top of results.
 *    - Followed by matching full-length movie titles from xmovix.net.
 *
 * 3. Multi-Site Performer "Seasons" View:
 *    - Opening a Model profile loads matching video catalogs as distinct seasons:
 *      * Season 1: PornTrex performer collection
 *      * Season 2: xmovix.net performer collection
 *
 * 4. Multi-Player Stream Extractor:
 *    - Inspects page scripts for all embedded video players (Player 1, Player 2, Player 3).
 *    - Unpacks packed JavaScript (p.a.c.k.e.r / VidHide / StreamHide CDN players).
 *    - Resolves 1080p master .m3u8 playlists and MP4 streams with full native download support.
 */
class CustomScraper : MainAPI() {
    override var mainUrl = "https://xmovix.net"
    override var name = "Multi-Source"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    // Base URLs for aggregated sub-sources
    val pornpicsUrl = "https://www.pornpics.de"
    val porntrexUrl = "https://www.porntrex.com"

    // ==========================================
    // 1. HOME PAGE CATALOG DEFINITION
    // ==========================================
    override val mainPage = mainPageOf(
        "en/movies" to "Trending",
        "pornpics_models" to "Models",
        "en/movies/porno-parodies" to "Parodies",
        "en/movies/hd-1080p" to "Full HD",
        "en/top.html" to "Top 100"
    )

    /**
     * Handles home catalog row fetching with automatic page randomization
     * on initial load (page <= 1) for fresh content every time the app opens.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // [Row 2] Models: Scraped from PornPics trending performers
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

            // [Row 5] Top 100: Scraped from xmovix Top 100 trophy list
            "en/top.html" -> {
                val url = "$mainUrl/en/top.html"
                val doc = app.get(url, headers = mapOf("referer" to "$mainUrl/")).document
                doc.select(".top100-items li.top100-item, .top100-box li, li.top100-item").mapNotNull {
                    parseXmovixTop100Item(it)
                }
            }

            // [Rows 1, 3, 4] Trending (1..315), Parodies (1..6), Full HD (1..114) from xmovix
            else -> {
                val path = request.data.trim('/')
                val maxPages = when {
                    path.contains("hd-1080p") -> 114
                    path.contains("porno-parodies") -> 6
                    else -> 315
                }
                // Pick a random page on first load so users get fresh content on every launch
                val targetPage = if (page <= 1) (1..maxPages).random() else page
                val url = if (targetPage <= 1) {
                    "$mainUrl/$path/"
                } else {
                    "$mainUrl/$path/page/$targetPage/"
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

    // ==========================================
    // 2. SEARCH (MODELS FIRST -> VIDEOS)
    // ==========================================
    /**
     * Executes parallel queries across model databases and video search endpoints.
     * Matches are organized with performer profile cards at the top of the search results.
     */
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim()
        val querySlug = cleanQuery.replace(" ", "-").lowercase()
        val querySearch = cleanQuery.replace(" ", "+")

        // 2a. Search Models in parallel (PornPics + PornTrex)
        val modelSearchJob = async {
            val results = mutableListOf<SearchResponse>()

            // Direct model lookup on PornPics
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

            // Direct model lookup on PornTrex
            runCatching {
                val pDirectUrl = "$porntrexUrl/models/$querySlug/"
                val pDoc = app.get(pDirectUrl, headers = mapOf("referer" to "$porntrexUrl/")).document
                val pName = pDoc.selectFirst("h1")?.text()?.trim()
                if (!pName.isNullOrBlank() && pName.contains(cleanQuery, ignoreCase = true)) {
                    val pPoster = fixUrlNull(
                        pDoc.selectFirst("meta[property='og:image'], .profile-model-info img")?.attr("content")
                            ?: pDoc.selectFirst(".profile-model-info img")?.attr("src"),
                        porntrexUrl
                    )
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

        // 2b. Search full movies on xmovix
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

        // Combine: Performer models first, followed by full movies
        models + videos
    }

    // ==========================================
    // 3. LOAD DETAILS (MODELS OR MOVIES)
    // ==========================================
    /**
     * Loads either:
     * - A Model Profile: aggregated into seasons (Season 1 = PornTrex, Season 2 = xmovix).
     * - A Movie: full movie details including metadata, cast, duration, plot, tags, and recommendations.
     */
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val isModelProfile = url.contains("/pornstars/") || url.contains("/models/") || url.contains("/model/")

        if (isModelProfile) {
            // === MODEL PROFILE BRANCH (MULTI-SITE SEASONS) ===
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

            // --- Season 1: PornTrex Videos ---
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

            // --- Season 2: xmovix.net Videos ---
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
            // === XMOVIX MOVIE DETAILS BRANCH ===
            val doc = app.get(url, headers = mapOf("referer" to "$mainUrl/")).document

            // 1. Movie Title
            val title = doc.selectFirst("meta[itemprop='name']")?.attr("content")
                ?: doc.selectFirst("#s-title span, h1.title, .entry-title, h1")?.text()?.trim()
                ?: "Movie"

            // 2. Poster Image
            val poster = fixUrlNull(
                doc.selectFirst("link[itemprop='thumbnailUrl']")?.attr("href")
                    ?: doc.selectFirst("meta[itemprop='image']")?.attr("content")
                    ?: doc.selectFirst(".fposter img, .full-poster img, .poster img")?.attr("src")
                    ?: doc.selectFirst(".fposter img, .full-poster img")?.attr("data-src"),
                mainUrl
            )

            // 3. Synopsis / Plot
            val description = doc.selectFirst("#s-desc, .fdesc")?.text()
                ?.replace(Regex("(?i)Watch porn movie.*"), "")?.trim()
                ?: doc.selectFirst("meta[itemprop='description']")?.attr("content")?.trim()

            // 4. Release Year
            val year = doc.selectFirst("span[itemprop='dateCreated'] a, .parameters-info .str675 a")?.text()?.filter { it.isDigit() }?.toIntOrNull()

            // 5. Duration (Parsed from schema PT8375S or text 02:19:35)
            val durationMeta = doc.selectFirst("meta[itemprop='duration']")?.attr("content")
            val durationSecs = Regex("""PT(\d+)S""").find(durationMeta.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val durationMinutes = if (durationSecs != null) {
                durationSecs / 60
            } else {
                val durText = doc.selectFirst(".flist-col li:contains(Duration), .duration")?.text()
                durText?.filter { it.isDigit() || it == ':' }?.split(':')?.let { parts ->
                    if (parts.size == 3) parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0)
                    else if (parts.size == 2) parts[0].toIntOrNull()
                    else null
                }
            }

            // 6. User Rating Percentage
            val ratingText = doc.selectFirst(".short-rate-perc, .flikes, .rate-data")?.text()
            val rating = Regex("""(\d{1,3})%""").find(ratingText.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

            // 7. Cast Members (Performers)
            val actors = doc.select("span[itemprop='actors'] a, .flist-col3 a[href*='/watch/name/']")
                .mapNotNull { it.text().trim().ifBlank { null } }
                .distinct()
                .map {
                    ActorData(
                        actor = Actor(it, null),
                        role = null,
                        roleString = "Performer",
                        voiceActor = null
                    )
                }

            // 8. Categories / Genres / Tags
            val tags = doc.select("ul.flist-col3 a[href*='/tags/'], .flist-col3 a[href*='/tags/'], a[href*='/tags/']")
                .mapNotNull { it.text().trim().ifBlank { null } }
                .distinct()

            // 9. Related Recommendations
            val recommendations = doc.select("div.block___bttm .short, .sect-c .short, div.short")
                .mapNotNull { parseXmovixShortItem(it) }
                .distinctBy { it.url }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("referer" to "$mainUrl/")
                this.plot = description
                this.year = year
                this.duration = durationMinutes
                this.rating = rating
                this.actors = actors
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // ==========================================
    // 4. MULTI-PLAYER STREAM EXTRACTION
    // ==========================================
    /**
     * Extracts direct playable streams and HLS master .m3u8 playlists:
     * - Handles PornTrex direct mp4 video links.
     * - Extracts all xmovix player embed URLs from script click handlers and iframes.
     * - Evaluates packed JavaScript (VidHide / StreamHide CDN players) in parallel.
     * - Emits master .m3u8 streams with correct Referer headers for streaming & downloads.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        // A. PornTrex Stream Links Branch
        if (data.contains("porntrex.com")) {
            val response = app.get(data, headers = mapOf("referer" to "$porntrexUrl/")).text
            val urlRegex = Regex("""(video_url|video_alt_url\d*)\s*:\s*['"]([^'"]+)['"]""")
            var found = false
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
                    found = true
                }
            }
            return@coroutineScope found
        }

        // B. Xmovix Multi-Player Embeds Branch
        val doc = app.get(data, headers = mapOf("referer" to "$mainUrl/")).document
        val rawHtml = doc.html()

        // Extract all player embed URLs from script click handlers and iframes
        val scriptEmbeds = Regex("""s2\.src\s*=\s*['"]([^'"]+)['"]""").findAll(rawHtml).map { it.groupValues[1] }.toList()
        val iframeEmbeds = doc.select("iframe[src]").map { it.attr("src") }
        val allEmbeds = (scriptEmbeds + iframeEmbeds).distinct().reversed() // Prioritize Player 3 & 2 first

        var count = 0

        allEmbeds.mapIndexed { index, embedUrl ->
            async {
                val fixedEmbed = fixUrl(embedUrl)
                runCatching {
                    val embedHost = fixedEmbed.substringBefore("/", "").ifBlank { "https://xmovix.net" }
                    val embedDoc = app.get(fixedEmbed, headers = mapOf(
                        "referer" to "$mainUrl/",
                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )).text

                    // Unpack VidHide / StreamHide JavaScript if packed via p.a.c.k.e.r
                    val unpacked = if (embedDoc.contains("eval(function(p,a,c,k,e,")) {
                        unpackJs(embedDoc)
                    } else {
                        embedDoc
                    }

                    // Extract master m3u8 playlist URLs from unpacked JavaScript
                    val m3u8Regex = Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""")
                    val m3u8Matches = m3u8Regex.findAll(unpacked).map { it.value }.toList()

                    for (streamUrl in m3u8Matches) {
                        val playerLabel = "Player ${allEmbeds.size - index}"
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "$name $playerLabel",
                                url = streamUrl,
                                referer = "$embedHost/",
                                quality = Qualities.P1080.value,
                                isM3u8 = true,
                                headers = mapOf(
                                    "referer" to "$embedHost/",
                                    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                )
                            )
                        )
                        count++
                    }

                    // Fallback to direct MP4 streams if no m3u8 was found
                    if (m3u8Matches.isEmpty()) {
                        val mp4Regex = Regex("""https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*""")
                        mp4Regex.findAll(unpacked).forEach { match ->
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "$name MP4",
                                    url = match.value,
                                    referer = "$embedHost/",
                                    quality = Qualities.P1080.value,
                                    isM3u8 = false
                                )
                            )
                            count++
                        }
                    }
                }
            }
        }.awaitAll()

        count > 0
    }

    // ==========================================
    // 5. JAVASCRIPT P.A.C.K.E.R UNPACKER
    // ==========================================
    /**
     * Pure Kotlin implementation of Dean Edwards' p.a.c.k.e.r unpacker.
     * Evaluates obfuscated player scripts and extracts hidden stream sources.
     */
    private fun unpackJs(packedText: String): String {
        val regex = Regex("""eval\(function\(p,a,c,k,e,[rd]\)\s*\{.*?return p\}\s*\('(.*?)',(\d+),(\d+),'([^']*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(packedText) ?: return packedText
        val (payload, radixStr, _, symtabStr) = match.destructured
        val radix = radixStr.toIntOrNull() ?: 36
        val symtab = symtabStr.split('|')

        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun unbasen(s: String, b: Int): Int {
            var res = 0
            for (ch in s) {
                val idx = digits.indexOf(ch)
                if (idx == -1 || idx >= b) return -1
                res = res * b + idx
            }
            return res
        }

        val wordRegex = Regex("""\b[0-9a-zA-Z]+\b""")
        return wordRegex.replace(payload) { m ->
            val word = m.value
            val idx = unbasen(word, radix)
            if (idx in 0 until symtab.size && symtab[idx].isNotBlank()) {
                symtab[idx]
            } else {
                word
            }
        }
    }

    // ==========================================
    // 6. HELPER PARSERS
    // ==========================================
    /**
     * Parses standard xmovix movie cards (e.g. from Trending, Parodies, Full HD, and Search).
     */
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

    /**
     * Parses Top 100 trophy list items from xmovix.net/en/top.html.
     */
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

    /**
     * Parses performer model cards from PornPics (pornpics.de).
     */
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

    /**
     * Resolves relative paths, protocol-relative URLs, and absolute URLs.
     */
    private fun fixUrl(url: String, base: String = mainUrl): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> base.trimEnd('/') + url
            else -> base.trimEnd('/') + "/" + url
        }
    }

    /**
     * Null-safe URL resolver.
     */
    private fun fixUrlNull(url: String?, base: String = mainUrl): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url, base)
    }
}