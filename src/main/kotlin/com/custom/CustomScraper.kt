package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * MultiSource Scraper for CloudStream / Zangetsu
 *
 * Unified Multi-Source Aggregator:
 * 1. Multi-Source Catalogs:
 *    - Row 1: "Popular Movies" (ParadiseHill IMDb-grade popular full movies)
 *    - Row 2: "Trending Models" (pornpics.de trending performer catalog)
 *    - Row 3: "New Releases" (ParadiseHill new movie releases)
 *    - Row 4: "Classics" (ParadiseHill classics)
 *    - Row 5: "Feature Films" (ParadiseHill full-length films)
 *    - Row 6: "Popular Studios" (ParadiseHill studio productions)
 *
 * 2. Model-First Search:
 *    - Prioritizes performer profile matches from PornPics, ParadiseHill, and PornTrex at the top.
 *    - Followed by full-length movie matches.
 *
 * 3. Multi-Site Performer "Seasons" View:
 *    - Opening a Model profile loads matching video catalogs as distinct seasons:
 *      * Season 1: PornTrex performer collection
 *      * Season 2: ParadiseHill full-length movie appearances
 *
 * 4. Multi-Part Movie Support:
 *    - Multi-CD releases (CD1..CD7) are cleanly split into selectable parts/episodes.
 *    - Single-part movies load as standard full movies.
 *
 * 5. Direct 1080p MP4 Streaming & Fast Downloads:
 *    - Direct high-speed .mp4 streams from v1.paradisehill.cc with HTTP Byte-Range support.
 *    - Flawless CloudStream native playback and background downloading.
 */
class CustomScraper : MainAPI() {
    override var mainUrl = "https://en.paradisehill.cc"
    override var name = "Multi-Source"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    val pornpicsUrl = "https://www.pornpics.de"
    val porntrexUrl = "https://www.porntrex.com"

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Cookie" to "is18=fb3a79a565a9b0610bba3a8f6cfcd32f32257acc782c0ed60c46314b771cb32ba%3A2%3A%7Bi%3A0%3Bs%3A4%3A%22is18%22%3Bi%3A1%3Bb%3A1%3B%7D",
        "Referer" to "https://en.paradisehill.cc/"
    )

    // ==========================================
    // 1. HOME PAGE CATALOG DEFINITIONS
    // ==========================================
    override val mainPage = mainPageOf(
        "popular/?filter=all&sort=by_likes" to "Popular Movies",
        "pornpics_models" to "Trending Models",
        "all/?sort=created_at" to "New Releases",
        "category/classics/?sort=created_at" to "Classics",
        "category/feature-films/?sort=created_at" to "Feature Films",
        "studios/?sort=by_likes" to "Popular Studios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // Row 2: Models scraped from PornPics trending performers
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

            // ParadiseHill movie and studio catalogs
            else -> {
                val url = if (page <= 1) {
                    "$mainUrl/${request.data}"
                } else {
                    val sep = if (request.data.contains("?")) "&" else "?"
                    "$mainUrl/${request.data}${sep}page=$page"
                }
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select(".all-block .item, .item, .all-films .item, div.item").mapNotNull {
                    parseParadiseMovieCard(it)
                }
            }
        }

        val hasNextPage = items.size >= 12
        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = request.data == "pornpics_models")
        return newHomePageResponse(homePageList, hasNextPage)
    }

    // ==========================================
    // 2. MODEL-FIRST SEARCH
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim().replace(" ", "+")
        val slugQuery = query.trim().lowercase().replace(" ", "-")

        // 1. Search performer models on PornPics
        val pornpicsJob = async {
            runCatching {
                val pUrl = "$pornpicsUrl/search/sr-models/?q=$cleanQuery"
                val pDoc = app.get(pUrl, headers = mapOf("referer" to "$pornpicsUrl/")).document
                pDoc.select("ul#tiles li.thumbwook, #tiles li, li.thumbwook").mapNotNull {
                    parsePornpicsModel(it)
                }
            }.getOrDefault(emptyList())
        }

        // 2. Search actors on ParadiseHill
        val paradiseActorsJob = async {
            runCatching {
                val aUrl = "$mainUrl/search/?pattern=$cleanQuery&what=2"
                val aDoc = app.get(aUrl, headers = defaultHeaders).document
                aDoc.select("a[href*='/actor/']").mapNotNull {
                    parseParadiseActorCard(it)
                }
            }.getOrDefault(emptyList())
        }

        // 3. Search movies on ParadiseHill
        val paradiseMoviesJob = async {
            runCatching {
                val mUrl = "$mainUrl/search/?pattern=$cleanQuery&what=1"
                val mDoc = app.get(mUrl, headers = defaultHeaders).document
                mDoc.select(".all-block .item, .item, .all-films .item").mapNotNull {
                    parseParadiseMovieCard(it)
                }
            }.getOrDefault(emptyList())
        }

        // 4. Search videos on PornTrex
        val porntrexJob = async {
            runCatching {
                val ptUrl = "$porntrexUrl/search/$slugQuery/"
                val ptDoc = app.get(ptUrl, headers = mapOf("referer" to "$porntrexUrl/")).document
                ptDoc.select("div.video-list div.video-item, .list-videos .item, .item").take(15).mapNotNull { el ->
                    val link = el.selectFirst("a[href*='/video/'], a")?.attr("href") ?: return@mapNotNull null
                    val title = el.selectFirst("strong.title, .title")?.text()?.trim() ?: return@mapNotNull null
                    val poster = fixUrlNull(el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src"), porntrexUrl)
                    newMovieSearchResponse(title, fixUrl(link, porntrexUrl), TvType.Movie) {
                        this.posterUrl = poster
                        this.posterHeaders = mapOf("referer" to "$porntrexUrl/")
                    }
                }
            }.getOrDefault(emptyList())
        }

        val models = (pornpicsJob.await() + paradiseActorsJob.await()).distinctBy { it.url }
        val movies = (paradiseMoviesJob.await() + porntrexJob.await()).distinctBy { it.url }

        // Models first, followed by movies
        models + movies
    }

    // ==========================================
    // 3. LOAD (MODELS OR MOVIES)
    // ==========================================
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val isModelProfile = url.contains("/pornstars/") || url.contains("/models/") || url.contains("/model/") || url.contains("/actor/")

        if (isModelProfile) {
            // === MODEL PROFILE BRANCH (MULTI-SITE SEASONS) ===
            val slug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val rawName = slug.replace("-", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val name = doc?.selectFirst("h1.model-h1, .profile-model-info h1, title")?.text()
                ?.replace(Regex("(?i)Porn Actor\\s*"), "")?.trim() ?: rawName

            val rawModelPoster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc?.selectFirst(".profile-model-info img, img")?.attr("src")
            val poster = fixUrlNull(toHighResParadisePoster(rawModelPoster), url)

            val episodes = mutableListOf<Episode>()

            // Season 1: PornTrex Videos
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

            // Season 2: ParadiseHill Movies
            val paradiseJob = async {
                runCatching {
                    val aDoc = if (url.contains("paradisehill.cc/actor/")) doc else {
                        val sUrl = "$mainUrl/search/?pattern=${slug.replace("-", "+")}&what=2"
                        val sDoc = app.get(sUrl, headers = defaultHeaders).document
                        val actorHref = sDoc.selectFirst("a[href*='/actor/']")?.attr("href")
                        if (!actorHref.isNullOrBlank()) app.get(fixUrl(actorHref, mainUrl), headers = defaultHeaders).document else null
                    }

                    aDoc?.select(".all-block .item, .item, .all-films .item")?.mapIndexedNotNull { index, el ->
                        val linkEl = el.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                        val href = linkEl.attr("href")
                        if (href.isBlank() || href.contains("/actor/")) return@mapIndexedNotNull null

                        val title = el.selectFirst(".name a, .name, a.title")?.text()?.trim()
                            ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                            ?: "ParadiseHill Movie ${index + 1}"

                        val rawPoster = el.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                            ?: el.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                        val xPoster = fixUrlNull(toHighResParadisePoster(rawPoster), mainUrl)

                        Episode(
                            data = fixUrl(href, mainUrl),
                            name = title,
                            season = 2, // Season 2 = ParadiseHill Movies
                            episode = index + 1,
                            posterUrl = xPoster
                        )
                    }.orEmpty()
                }.getOrDefault(emptyList())
            }

            episodes.addAll(porntrexJob.await())
            episodes.addAll(paradiseJob.await())

            newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("referer" to "$mainUrl/")
                this.plot = "Multi-Source Collection for $name: Season 1 = PornTrex, Season 2 = ParadiseHill Feature Films"
                this.showStatus = ShowStatus.Completed
            }
        } else {
            // === PARADISEHILL FULL MOVIE DETAILS BRANCH ===
            val doc = app.get(url, headers = defaultHeaders).document

            val title = doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?: doc.selectFirst("h1, .title h1")?.text()?.trim()
                ?: "Movie"

            val rawPoster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".poster img, img.poster, div.poster img")?.attr("src")
            val poster = fixUrlNull(toHighResParadisePoster(rawPoster), mainUrl)

            val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst(".story, div[itemprop='description'], .description")?.text()?.trim()

            val releaseYear = doc.selectFirst("span[itemprop='releasedEvent']")?.text()
                ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            val categories = doc.select("span[itemprop='genre'] a, a[href*='/category/']")
                .mapNotNull { it.text().trim().ifBlank { null } }
                .distinct()

            val actors = doc.select("a[href*='/actor/']")
                .mapNotNull { el ->
                    val actorName = el.text().trim()
                    if (actorName.isNotBlank() && !actorName.contains("Actors", ignoreCase = true)) {
                        ActorData(
                            actor = Actor(actorName, null),
                            role = null,
                            roleString = "Performer",
                            voiceActor = null
                        )
                    } else null
                }.distinctBy { it.actor.name.lowercase() }

            // Extract direct multi-part MP4 video sources
            val rawHtml = doc.html()
            val videoListMatch = Regex("""var\s+videoList\s*=\s*(\[[^;]+\]);""").find(rawHtml)
            val videoListJson = videoListMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: rawHtml

            val srcRegex = Regex("""["']src["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
            val foundSources = srcRegex.findAll(videoListJson).map { it.groupValues[1] }.distinct().toList()
            val mp4Sources = if (foundSources.isNotEmpty()) {
                foundSources.map { fixUrl(it, mainUrl) }
            } else {
                val fallbackRegex = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
                fallbackRegex.findAll(rawHtml).map { it.groupValues[1] }
                    .filter { it.contains("paradise") || it.contains("video") }
                    .distinct().toList()
            }

            // If movie is split into multi-CD parts (e.g. CD1, CD2 ... CD7)
            if (mp4Sources.size > 1) {
                val episodes = mp4Sources.mapIndexed { index, streamUrl ->
                    val partNum = index + 1
                    Episode(
                        data = streamUrl,
                        name = "Part $partNum (CD $partNum)",
                        episode = partNum,
                        posterUrl = poster
                    )
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.posterHeaders = mapOf("referer" to "$mainUrl/")
                    this.plot = description
                    this.year = releaseYear
                    this.tags = categories
                    this.actors = actors
                    this.showStatus = ShowStatus.Completed
                }
            } else {
                val directStream = mp4Sources.firstOrNull() ?: url
                newMovieLoadResponse(title, url, TvType.Movie, directStream) {
                    this.posterUrl = poster
                    this.posterHeaders = mapOf("referer" to "$mainUrl/")
                    this.plot = description
                    this.year = releaseYear
                    this.tags = categories
                    this.actors = actors
                }
            }
        }
    }

    // ==========================================
    // 4. DIRECT 1080P MP4 STREAM EXTRACTION
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // A. Direct MP4 Stream Link (from ParadiseHill CD parts or direct video)
        if (data.contains(".mp4", ignoreCase = true)) {
            val streamUrl = fixUrl(data, mainUrl)
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name Direct MP4 (1080p)",
                    url = streamUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    isM3u8 = false,
                    headers = mapOf(
                        "referer" to "$mainUrl/",
                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                )
            )
            return true
        }

        // B. PornTrex Video URL Branch
        if (data.contains("porntrex.com")) {
            val response = app.get(data, headers = mapOf("referer" to "$porntrexUrl/")).text
            val flashvarsMatch = Regex("""var\s+flashvars\s*=\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL).find(response)
            val scriptContent = flashvarsMatch?.groupValues?.get(1) ?: response

            val urlRegex = Regex("""(video_url|video_alt_url\d*)\s*:\s*['"]([^'"]+)['"]""")
            val textRegex = Regex("""(video_url_text|video_alt_url\d*_text)\s*:\s*['"]([^'"]+)['"]""")

            val qualityMap = mutableMapOf<String, String>()
            textRegex.findAll(scriptContent).forEach { match ->
                val key = match.groupValues[1].replace("_text", "")
                val quality = match.groupValues[2]
                qualityMap[key] = quality
            }

            var found = false
            urlRegex.findAll(scriptContent).forEach { match ->
                val key = match.groupValues[1]
                val streamUrl = match.groupValues[2]
                if (streamUrl.startsWith("http")) {
                    val qualityLabel = qualityMap[key] ?: "720p"
                    val qualityValue = getQualityFromName(qualityLabel)
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name PornTrex ($qualityLabel)",
                            url = streamUrl,
                            referer = "$porntrexUrl/",
                            quality = qualityValue,
                            isM3u8 = streamUrl.contains(".m3u8"),
                            headers = mapOf("referer" to "$porntrexUrl/")
                        )
                    )
                    found = true
                }
            }
            return found
        }

        // C. ParadiseHill Movie Page URL Branch (e.g. from Performer Season 2 or Movie URL)
        val doc = app.get(data, headers = defaultHeaders).document
        val rawHtml = doc.html()
        val videoListMatch = Regex("""var\s+videoList\s*=\s*(\[[^;]+\]);""").find(rawHtml)
        val videoListJson = videoListMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: rawHtml

        val srcRegex = Regex("""["']src["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
        val foundSources = srcRegex.findAll(videoListJson).map { it.groupValues[1] }.distinct().toList()
        val mp4Sources = if (foundSources.isNotEmpty()) {
            foundSources.map { fixUrl(it, mainUrl) }
        } else {
            val fallbackRegex = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
            fallbackRegex.findAll(rawHtml).map { it.groupValues[1] }
                .filter { it.contains("paradise") || it.contains("video") }
                .distinct().toList()
        }

        var count = 0
        mp4Sources.forEachIndexed { index, rawMp4Url ->
            val mp4Url = fixUrl(rawMp4Url, mainUrl)
            val label = if (mp4Sources.size > 1) "Part ${index + 1} (1080p)" else "Full Movie (1080p)"
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = mp4Url,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    isM3u8 = false,
                    headers = mapOf(
                        "referer" to "$mainUrl/",
                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                )
            )
            count++
        }

        return count > 0
    }

    // ==========================================
    // 5. HELPER PARSERS & POSTER UPGRADERS
    // ==========================================
    private fun toHighResParadisePoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace("preview-", "")
            .replace(Regex("""\.webp$""", RegexOption.IGNORE_CASE), ".jpg")
    }

    private fun parseParadiseMovieCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/actor/") || href.contains("/category/") || href.contains("/studios/") || href.contains("/signup/") || href.contains("/login/")) return null

        val title = element.selectFirst(".name a, .name, a.title")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        val posterEl = element.selectFirst("img")
        val rawPoster = posterEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: posterEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        val highResPoster = toHighResParadisePoster(rawPoster)

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(highResPoster ?: rawPoster, mainUrl)
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    private fun parseParadiseActorCard(element: Element): SearchResponse? {
        val href = element.attr("href").ifBlank { element.selectFirst("a[href]")?.attr("href") } ?: return null
        if (!href.contains("/actor/")) return null

        val name = element.selectFirst(".name")?.text()?.trim()
            ?: element.attr("title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: element.text().trim()
        if (name.isBlank()) return null

        val posterEl = element.selectFirst("img")
        val rawPoster = posterEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: posterEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        val highResPoster = toHighResParadisePoster(rawPoster)

        return newTvSeriesSearchResponse(name, fixUrl(href, mainUrl), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(highResPoster ?: rawPoster, mainUrl)
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