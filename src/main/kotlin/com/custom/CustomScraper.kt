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
 *    - Row 2: "Popular Actors" (ParadiseHill performer catalog with photos —
 *              replaced the old PornPics dependency, actor images come from
 *              the video sites themselves)
 *    - Row 3: "New Releases" (ParadiseHill new movie releases)
 *    - Row 4: "Classics" (ParadiseHill classics)
 *    - Row 5: "Feature Films" (ParadiseHill full-length films)
 *    - Row 6: "Popular Studios" (ParadiseHill studio productions)
 *
 * 2. Actor-First Search:
 *    - Prioritizes performer profile matches from ParadiseHill at the top.
 *    - Followed by full-length movie matches (ParadiseHill) and
 *      scene matches (PornTrex).
 *
 * 3. Multi-Site Performer "Seasons" View:
 *    - Opening an Actor profile loads matching video catalogs as distinct seasons:
 *      * Season 1: PornTrex performer collection (looked up by performer NAME,
 *        since ParadiseHill actor URLs only contain a numeric id)
 *      * Season 2: ParadiseHill full-length movie appearances
 *
 * 4. Multi-Part Movie Support:
 *    - Multi-CD releases (CD1..CD7) are cleanly split into selectable parts/episodes,
 *      using the site's own "Part N" labels when available.
 *    - Single-part movies load as standard full movies.
 *
 * 5. Direct 1080p MP4 Streaming & Fast Downloads:
 *    - Direct high-speed .mp4 streams from v1.paradisehill.cc with HTTP Byte-Range support.
 *    - Every emitted link carries its referer header so the CloudStream
 *      download manager can fetch it directly.
 *    - MP4 links are always preferred over HLS (.m3u8) links, because
 *      CloudStream can only download direct files.
 */
class CustomScraper : MainAPI() {
    override var mainUrl = "https://en.paradisehill.cc"
    override var name = "MultiSource"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries, TvType.Movie)

    val porntrexUrl = "https://www.porntrex.com"

    private val defaultHeaders = mapOf("referer" to "$mainUrl/")
    private val porntrexHeaders = mapOf("referer" to "$porntrexUrl/")

    // 1. HOME PAGE CATALOG DEFINITIONS
    override val mainPage = mainPageOf(
        "popular/?filter=all&sort=by_likes" to "Popular Movies",
        "ph_actors" to "Popular Actors",
        "all/?sort=created_at" to "New Releases",
        "category/classics/?sort=created_at" to "Classics",
        "category/feature-films/?sort=created_at" to "Feature Films",
        "studios/?sort=by_likes" to "Popular Studios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // Row 2: Performers scraped from the ParadiseHill actor catalog
            // (replaces the old PornPics trending models row — the video sites
            // provide actor photos themselves)
            "ph_actors" -> {
                val url = if (page <= 1) {
                    "$mainUrl/actors/?sort=by_likes"
                } else {
                    "$mainUrl/actors/?sort=by_likes&page=$page"
                }
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("a[href*='/actor/']").mapNotNull {
                    parseParadiseActorCard(it)
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
        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = request.data == "ph_actors")
        return newHomePageResponse(homePageList, hasNextPage)
    }

    // 2. ACTOR-FIRST SEARCH
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim().replace(" ", "+")
        val slugQuery = query.trim().lowercase().replace(" ", "-")

        // 1. Search performers on ParadiseHill (their own actor index)
        val paradiseActorsJob = async {
            runCatching {
                val aUrl = "$mainUrl/search/?pattern=$cleanQuery&what=2"
                val aDoc = app.get(aUrl, headers = defaultHeaders).document
                aDoc.select("a[href*='/actor/']").mapNotNull {
                    parseParadiseActorCard(it)
                }
            }.getOrDefault(emptyList())
        }

        // 2. Search movies on ParadiseHill
        val paradiseMoviesJob = async {
            runCatching {
                val mUrl = "$mainUrl/search/?pattern=$cleanQuery&what=1"
                val mDoc = app.get(mUrl, headers = defaultHeaders).document
                mDoc.select(".all-block .item, .item, .all-films .item").mapNotNull {
                    parseParadiseMovieCard(it)
                }
            }.getOrDefault(emptyList())
        }

        // 3. Search videos on PornTrex
        val porntrexJob = async {
            runCatching {
                val ptUrl = "$porntrexUrl/search/$slugQuery/"
                val ptDoc = app.get(ptUrl, headers = porntrexHeaders).document
                ptDoc.select("div.video-list div.video-item, .list-videos .item, .item").take(15).mapNotNull { el ->
                    val link = el.selectFirst("a[href*='/video/'], a")?.attr("href") ?: return@mapNotNull null
                    val title = el.selectFirst("strong.title, .title")?.text()?.trim() ?: return@mapNotNull null
                    val poster = fixUrlNull(el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src"), porntrexUrl)
                    newMovieSearchResponse(title, fixUrl(link, porntrexUrl), TvType.Movie) {
                        this.posterUrl = poster
                        this.posterHeaders = porntrexHeaders
                    }
                }
            }.getOrDefault(emptyList())
        }

        val models = paradiseActorsJob.await().distinctBy { it.url }
        val movies = (paradiseMoviesJob.await() + porntrexJob.await()).distinctBy { it.url }

        // Actors first, followed by movies
        models + movies
    }

    // 3. LOAD (ACTORS OR MOVIES)
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val isModelProfile = url.contains("/pornstars/") || url.contains("/models/") || url.contains("/model/") || url.contains("/actor/")

        if (isModelProfile) {
            // === ACTOR PROFILE BRANCH (MULTI-SITE SEASONS) ===
            val urlSlug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()

            // Fetch the profile page first, so the performer NAME can drive
            // cross-site lookups (ParadiseHill actor URLs only contain an id,
            // e.g. /actor/23476/ -> the PornTrex lookup needs "riley-reid").
            val profileHeaders = if (url.contains(porntrexUrl)) porntrexHeaders else defaultHeaders
            val doc = runCatching { app.get(url, headers = profileHeaders).document }.getOrNull()

            val name = doc?.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                ?: doc?.selectFirst("title")?.text()?.replace(Regex("(?i)Porn Actor\\s*"), "")?.trim()?.ifBlank { null }
                ?: urlSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val modelSlug = if (urlSlug.isBlank() || urlSlug.all { it.isDigit() } || url.contains("/actor/")) {
                name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            } else {
                urlSlug
            }

            val rawModelPoster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc?.selectFirst(".profile-model-info img, img")?.attr("src")
            val poster = fixUrlNull(toHighResParadisePoster(rawModelPoster), url)

            val episodes = mutableListOf<Episode>()

            // Season 1: PornTrex Videos (looked up by performer name)
            val porntrexJob = async {
                runCatching {
                    val pUrl = "$porntrexUrl/models/$modelSlug/"
                    val pDoc = app.get(pUrl, headers = porntrexHeaders).document
                    val pElements = pDoc.select("div.video-list div.video-item, .list-videos .item, .item")
                    pElements.mapIndexedNotNull { index, el ->
                        val link = el.selectFirst("a[href*='/video/']")?.attr("href") ?: return@mapIndexedNotNull null
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
                        val sUrl = "$mainUrl/search/?pattern=${modelSlug.replace("-", "+")}&what=2"
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
                this.posterHeaders = defaultHeaders
                this.plot = "Multi-Source Collection for $name: Season 1 = PornTrex, Season 2 = ParadiseHill Feature Films"
                this.showStatus = ShowStatus.Completed
            }
        } else if (url.contains("porntrex.com")) {
            // === PORNTREX VIDEO DETAILS BRANCH ===
            val doc = app.get(url, headers = porntrexHeaders).document
            val scriptText = doc.selectFirst("script:containsData(var flashvars)")?.data()
                ?: doc.selectFirst("script:containsData(flashvars)")?.data()

            val title = Regex("""['"]?video_title['"]?\s*:\s*['"]([^'"]+)['"]""").find(scriptText.orEmpty())?.groupValues?.get(1)
                ?: doc.selectFirst("h1.title, h1, .headline h1, .video-details h1, p.title-video")?.text()?.trim()
                ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
                ?: "Video"

            val rawPoster = Regex("""['"]?preview_url['"]?\s*:\s*['"]([^'"]+)['"]""").find(scriptText.orEmpty())?.groupValues?.get(1)
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("a.thumb img.cover, #player-holder video[poster], video[poster]")?.attr("poster")
                ?: doc.selectFirst("a.thumb img.cover")?.attr("src")
            val poster = fixUrlNull(rawPoster, porntrexUrl)

            val description = doc.selectFirst(".videodesc .items-holder em.des-link, .videodesc .des-link, .videodesc .items-holder, .videodesc, .description-block, .video-details")?.text()
                ?.replace(Regex("^Description:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = porntrexHeaders
                this.plot = description
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
            val mp4Sources = extractParadiseMp4Sources(rawHtml)

            // If movie is split into multi-CD parts (e.g. CD1, CD2 ... CD7)
            if (mp4Sources.size > 1) {
                // Prefer the site's own "Part N" labels when their count matches
                val pagePartLabels = Regex("""Part\s+(\d+)""", RegexOption.IGNORE_CASE)
                    .findAll(rawHtml)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()

                val episodes = mp4Sources.mapIndexed { index, mp4Url ->
                    val partNum = index + 1
                    val label = pagePartLabels.getOrNull(index) ?: partNum.toString()
                    Episode(
                        data = mp4Url,
                        name = "Part $label (CD $partNum)",
                        episode = partNum,
                        posterUrl = poster
                    )
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.posterHeaders = defaultHeaders
                    this.plot = description
                    this.year = releaseYear
                    this.tags = categories
                    this.actors = actors
                    this.showStatus = ShowStatus.Completed
                }
            } else {
                val directUrl = mp4Sources.firstOrNull() ?: url
                newMovieLoadResponse(title, url, TvType.Movie, directUrl) {
                    this.posterUrl = poster
                    this.posterHeaders = defaultHeaders
                    this.plot = description
                    this.year = releaseYear
                    this.tags = categories
                    this.actors = actors
                }
            }
        }
    }

    // 4. DIRECT 1080P MP4 STREAM EXTRACTION
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // A. Direct MP4 Stream Link (Instant handler - no HTTP request needed)
        if (data.contains(".mp4", ignoreCase = true)) {
            val streamUrl = fixUrl(data, mainUrl)
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name 1080p",
                    url = streamUrl,
                    referer = "$mainUrl/",
                    quality = getQualityFromName("1080p"),
                    isM3u8 = false,
                    headers = mapOf("referer" to "$mainUrl/")
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

            var count = 0
            urlRegex.findAll(scriptContent).forEach { match ->
                val key = match.groupValues[1]
                val streamUrl = match.groupValues[2]
                if (streamUrl.startsWith("http")) {
                    val qualityLabel = qualityMap[key] ?: "720p"
                    val qualityValue = getQualityFromName(qualityLabel)

                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name $qualityLabel",
                            url = streamUrl,
                            referer = "$porntrexUrl/",
                            quality = qualityValue,
                            isM3u8 = streamUrl.contains(".m3u8"),
                            headers = mapOf("referer" to "$porntrexUrl/")
                        )
                    )
                    count++
                }
            }
            return count > 0
        }

        // C. ParadiseHill Movie Page URL or Part URL Branch
        val pageUrl = data.substringBefore("#")
        val response = app.get(pageUrl, headers = mapOf("referer" to "$mainUrl/")).text
        val mp4Sources = extractParadiseMp4Sources(response)
        if (mp4Sources.isEmpty()) return false

        // Check if a specific part was requested (e.g. #part=2 or #cd=2)
        val partMatch = Regex("""#(?:part|cd)=(\d+)""", RegexOption.IGNORE_CASE).find(data)
        val requestedPart = partMatch?.groupValues?.get(1)?.toIntOrNull()

        if (requestedPart != null) {
            val partIndex = requestedPart - 1
            val streamUrl = mp4Sources.getOrNull(partIndex) ?: mp4Sources.first()
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name Part $requestedPart (1080p)",
                    url = streamUrl,
                    referer = "$mainUrl/",
                    quality = getQualityFromName("1080p"),
                    isM3u8 = false,
                    headers = mapOf("referer" to "$mainUrl/")
                )
            )
            return true
        }

        val pagePartLabels = Regex("""Part\s+(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(response)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        var count = 0
        mp4Sources.forEachIndexed { index, mp4Url ->
            val label = if (mp4Sources.size > 1) {
                val partLabel = pagePartLabels.getOrNull(index) ?: (index + 1).toString()
                "Part $partLabel (1080p)"
            } else {
                "Full Movie (1080p)"
            }
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = mp4Url,
                    referer = "$mainUrl/",
                    quality = getQualityFromName("1080p"),
                    isM3u8 = false,
                    headers = mapOf("referer" to "$mainUrl/")
                )
            )
            count++
        }

        return count > 0
    }

    // 5. HELPER PARSERS & POSTER UPGRADERS
    private fun toHighResParadisePoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace("preview-", "")
    }

    /**
     * Multi-strategy ParadiseHill stream extraction. Tries, in order:
     * 1. The classic inline player array: var videoList = [ { src: '...mp4' }, ... ];
     * 2. Any player-config style key carrying an mp4 (kt_player flashvars, jwplayer file, ...)
     * 3. Absolute mp4 URLs anywhere in the page
     * All returned URLs are absolute.
     */
    private fun extractParadiseMp4Sources(rawHtml: String): List<String> {
        val found = LinkedHashSet<String>()

        Regex("""var\s+videoList\s*=\s*(\[[^;]+\]);""").find(rawHtml)?.let { match ->
            Regex("""["']src["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""").findAll(match.groupValues[1]).forEach {
                found += it.groupValues[1].replace("\\/", "/")
            }
        }

        if (found.isEmpty()) {
            Regex("""["'](?:src|file|video_url|url)["']\s*:\s*["']([^"']*\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(rawHtml).forEach { found += it.groupValues[1].replace("\\/", "/") }
        }

        if (found.isEmpty()) {
            Regex("""(https?(?::\\/\\/|://)[^\s"'<>]+\.mp4[^\s"'<>]*)""").findAll(rawHtml).forEach {
                found += it.groupValues[1].replace("\\/", "/")
            }
        }

        return found.asSequence()
            .filter { !it.startsWith("data:") }
            .map { fixUrl(it, mainUrl) }
            .distinct()
            .toList()
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
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseParadiseActorCard(element: Element): SearchResponse? {
        val href = element.attr("href").ifBlank { element.selectFirst("a[href]")?.attr("href") } ?: return null
        if (!href.contains("/actor/")) return null

        val name = element.selectFirst(".name")?.text()?.trim()
            ?: element.attr("title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: element.ownText().trim().ifBlank { element.text().trim() }
        if (name.isBlank()) return null

        val posterEl = element.selectFirst("img")
        val rawPoster = posterEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: posterEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        val highResPoster = toHighResParadisePoster(rawPoster)

        return newTvSeriesSearchResponse(name, fixUrl(href, mainUrl), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(highResPoster ?: rawPoster, mainUrl)
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
