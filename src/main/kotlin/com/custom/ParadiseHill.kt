package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * ParadiseHill - Dedicated CloudStream Provider for ParadiseHill.cc
 *
 * Features:
 * 1. Catalogs:
 *    - Popular Movies, Popular Performers, New Releases, Feature Films, Classics, Studios
 * 2. Search:
 *    - Performers, Movies, and Studios with multi-page depth
 * 3. Detail & Multi-Part Loading:
 *    - Full movies with multi-CD scene breakdown (CD1..CD7 / Part 1..Part N)
 *    - Performer profiles with all movie appearances
 *    - Studio releases
 * 4. High-Speed Streaming:
 *    - Direct 1080p MP4 video streams from v1.paradisehill.cc with byte-range and download headers
 */
class ParadiseHill : MainAPI() {
    override var mainUrl = "https://en.paradisehill.cc"
    override var name = "ParadiseHill"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie, TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "is18=1; _csrf-frontend=1"
    )

    // 1. HOME PAGE CATALOG DEFINITIONS
    override val mainPage = mainPageOf(
        "popular/?filter=all&sort=by_likes" to "Popular Movies",
        "actors/?sort=by_likes" to "Popular Performers",
        "all/?sort=created_at" to "New Releases",
        "category/feature-films/?sort=created_at" to "Feature Films",
        "category/classic/?sort=created_at" to "Classics",
        "studios/?sort=by_likes" to "Popular Studios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isActors = request.data.startsWith("actors")
        val isStudios = request.data.startsWith("studios")

        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "$mainUrl/${request.data}${sep}page=$page"
        }

        val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()

        val items: List<SearchResponse> = when {
            isActors -> {
                doc?.select("a[href*='/actor/'], .all-block .item, .item")?.mapNotNull {
                    parseParadiseActorCard(it)
                }.orEmpty()
            }
            isStudios -> {
                doc?.select("a[href*='/studio/'], .all-block .item, .item")?.mapNotNull {
                    parseParadiseStudioCard(it)
                }.orEmpty()
            }
            else -> {
                doc?.select(".all-block .item, .item, .all-films .item, div.item")?.mapNotNull {
                    parseParadiseMovieCard(it)
                }.orEmpty()
            }
        }

        val hasNextPage = items.size >= 12
        val homePageList = HomePageList(
            name = request.name,
            list = items.distinctBy { it.url },
            isHorizontalImages = isActors || isStudios
        )
        return newHomePageResponse(homePageList, hasNextPage)
    }

    companion object {
        var searchPages: Int = 2
        var actorPages: Int = 2
    }

    // 2. SEARCH (PERFORMERS, MOVIES, STUDIOS)
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim().replace(" ", "+")

        // 1. Search Performers (what=2)
        val actorsJob = async {
            runCatching {
                val actorsList = mutableListOf<SearchResponse>()
                for (p in 1..actorPages.coerceIn(1, 4)) {
                    val aUrl = if (p <= 1) "$mainUrl/search/?pattern=$cleanQuery&what=2" else "$mainUrl/search/?pattern=$cleanQuery&what=2&page=$p"
                    val aDoc = app.get(aUrl, headers = defaultHeaders).document
                    val pageItems = aDoc.select("a[href*='/actor/']").mapNotNull {
                        parseParadiseActorCard(it)
                    }
                    if (pageItems.isEmpty()) break
                    actorsList.addAll(pageItems)
                }
                actorsList.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        // 2. Search Movies (what=1)
        val moviesJob = async {
            runCatching {
                val moviesList = mutableListOf<SearchResponse>()
                for (p in 1..searchPages.coerceIn(1, 5)) {
                    val mUrl = if (p <= 1) "$mainUrl/search/?pattern=$cleanQuery&what=1" else "$mainUrl/search/?pattern=$cleanQuery&what=1&page=$p"
                    val mDoc = app.get(mUrl, headers = defaultHeaders).document
                    val pageItems = mDoc.select(".all-block .item, .item, .all-films .item").mapNotNull {
                        parseParadiseMovieCard(it)
                    }
                    if (pageItems.isEmpty()) break
                    moviesList.addAll(pageItems)
                }
                moviesList.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        val models = actorsJob.await().distinctBy { it.url }
        val movies = moviesJob.await().distinctBy { it.url }

        // Performers first, followed by Movies
        models + movies
    }

    // 3. LOAD (PERFORMER, STUDIO, OR MOVIE)
    override suspend fun load(url: String): LoadResponse {
        val isActorProfile = url.contains("/actor/")
        val isStudioProfile = url.contains("/studio/")

        if (isActorProfile) {
            // === PERFORMER PROFILE ===
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val urlSlug = url.trimEnd('/').substringAfterLast('/')

            val name = doc?.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                ?: doc?.selectFirst("title")?.text()?.replace(Regex("(?i)Porn Actor\\s*"), "")?.trim()?.ifBlank { null }
                ?: urlSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val rawPoster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc?.selectFirst(".profile-model-info img, .avatar img, img")?.attr("src")
            val poster = fixUrlNull(toHighResParadisePoster(rawPoster), mainUrl)

            val episodes = doc?.select(".all-block .item, .item, .all-films .item")?.mapIndexedNotNull { index, el ->
                val linkEl = el.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                val href = linkEl.attr("href")
                if (href.isBlank() || href.contains("/actor/")) return@mapIndexedNotNull null

                val title = el.selectFirst(".name a, .name, a.title")?.text()?.trim()
                    ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: "Movie ${index + 1}"

                val mRawPoster = el.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: el.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                val mPoster = fixUrlNull(toHighResParadisePoster(mRawPoster), mainUrl)

                Episode(
                    data = fixUrl(href, mainUrl),
                    name = title,
                    season = 1,
                    episode = index + 1,
                    posterUrl = mPoster
                )
            }.orEmpty()

            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.showStatus = ShowStatus.Completed
            }
        } else if (isStudioProfile) {
            // === STUDIO PROFILE ===
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val urlSlug = url.trimEnd('/').substringAfterLast('/')

            val studioName = doc?.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                ?: urlSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val rawPoster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc?.selectFirst(".studio-logo img, img")?.attr("src")
            val poster = fixUrlNull(toHighResParadisePoster(rawPoster), mainUrl)

            val episodes = doc?.select(".all-block .item, .item, .all-films .item")?.mapIndexedNotNull { index, el ->
                val linkEl = el.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                val href = linkEl.attr("href")
                if (href.isBlank() || href.contains("/studio/")) return@mapIndexedNotNull null

                val title = el.selectFirst(".name a, .name, a.title")?.text()?.trim()
                    ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: "Movie ${index + 1}"

                val mRawPoster = el.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: el.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                val mPoster = fixUrlNull(toHighResParadisePoster(mRawPoster), mainUrl)

                Episode(
                    data = fixUrl(href, mainUrl),
                    name = title,
                    season = 1,
                    episode = index + 1,
                    posterUrl = mPoster
                )
            }.orEmpty()

            return newTvSeriesLoadResponse(studioName, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.showStatus = ShowStatus.Completed
            }
        } else {
            // === FULL MOVIE DETAILS ===
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()

            val title = doc?.selectFirst("meta[property='og:title']")?.attr("content")
                ?: doc?.selectFirst("h1, .title h1")?.text()?.trim()
                ?: "Movie"

            val rawPoster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc?.selectFirst(".poster img, img.poster, div.poster img")?.attr("src")
            val poster = fixUrlNull(toHighResParadisePoster(rawPoster), mainUrl)

            val description = doc?.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc?.selectFirst(".story, div[itemprop='description'], .description")?.text()?.trim()

            val releaseYear = doc?.selectFirst("span[itemprop='releasedEvent']")?.text()
                ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            val categories = doc?.select("span[itemprop='genre'] a, a[href*='/category/']")
                ?.mapNotNull { it.text().trim().ifBlank { null } }
                ?.distinct()
                .orEmpty()

            val actors = doc?.select("a[href*='/actor/']")
                ?.mapNotNull { el ->
                    val actorName = el.text().trim()
                    if (actorName.isNotBlank() && !actorName.contains("Actors", ignoreCase = true)) {
                        ActorData(
                            actor = Actor(actorName, null),
                            role = null,
                            roleString = "Performer",
                            voiceActor = null
                        )
                    } else null
                }?.distinctBy { it.actor.name.lowercase() }.orEmpty()

            val rawHtml = doc?.html().orEmpty()
            val mp4Sources = extractParadiseMp4Sources(rawHtml)

            // If movie is split into multi-CD parts (CD1, CD2 ... CD7)
            if (mp4Sources.size > 1) {
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

                return newTvSeriesLoadResponse(cleanTitle(title), url, TvType.TvSeries, episodes) {
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
                return newMovieLoadResponse(cleanTitle(title), url, TvType.Movie, directUrl) {
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
        // Direct media stream URL (.mp4 / .m3u8)
        if (StreamSupport.isDirectMediaUrl(data)) {
            val streamUrl = fixUrl(data, mainUrl)
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name Direct 1080p MP4",
                    url = streamUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    isM3u8 = streamUrl.contains(".m3u8", true),
                    headers = mapOf("Referer" to "$mainUrl/")
                )
            )
            return true
        }

        // Full movie page URL
        val pageUrl = data.substringBefore("#")
        val response = runCatching { app.get(pageUrl, headers = defaultHeaders).text }.getOrNull()
            ?: return false
        val mp4Sources = extractParadiseMp4Sources(response)
        if (mp4Sources.isEmpty()) return false

        val partMatch = Regex("""#(?:part|cd)=(\d+)""", RegexOption.IGNORE_CASE).find(data)
        val requestedPart = partMatch?.groupValues?.get(1)?.toIntOrNull()

        if (requestedPart != null) {
            val partIndex = requestedPart - 1
            val streamUrl = mp4Sources.getOrNull(partIndex) ?: mp4Sources.first()
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name Part $requestedPart (1080p MP4)",
                    url = streamUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    isM3u8 = false,
                    headers = mapOf("Referer" to "$mainUrl/")
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
                "Part $partLabel (1080p MP4)"
            } else {
                "Full Movie (1080p MP4)"
            }
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = mp4Url,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    isM3u8 = false,
                    headers = mapOf("Referer" to "$mainUrl/")
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

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""&#?\w+;"""), " ")
        t = t.replace(Regex("""\[.*?\]|\(.*?\)|<.*?>"""), " ")
        t = t.replace(Regex("""(?i)\b(?:Blacked|Evil\s*Angel|Brazzers|Tushy|Vixen|Bang!?|Naughty\s*America|Sweet\s*Sinner|Wicked|Digital\s*Playground|Jules\s*Jordan|Reality\s*Kings|DDF|Mofos)(?:\s*\d{2,4})?\b"""), " ")
        t = t.replace(Regex("""(?i)\b(?:\d{3,4}p|4K|2160p|1080p|720p|480p|WEB-?DL|BDRip|DVDRip|HDRip|x264|x265|HEVC|AAC|MP3|SPLITSCENES|XXX|FULL|HD|VOSTFR|FRENCH)\b"""), " ")
        t = t.replace(Regex("""\b(?:19|20)\d{2}\b"""), " ")
        t = t.replace(Regex("""#(\d+)"""), "$1")
        t = t.replace(Regex("""(?i)\.torrent|\.html"""), "")
        t = t.replace(Regex("""[-–—:_/]+"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t.ifEmpty { raw.trim() }
    }

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

        return newMovieSearchResponse(cleanTitle(title), fixUrl(href, mainUrl), TvType.Movie) {
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

    private fun parseParadiseStudioCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/studio/']") ?: if (element.tagName() == "a") element else return null
        val href = linkEl.attr("href")
        if (href.isBlank() || !href.contains("/studio/")) return null

        val name = element.selectFirst(".name, h2, h3")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: linkEl.text().trim()
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
