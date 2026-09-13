package com.custom

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Himeros - Master Aggregator Provider for CloudStream
 *
 * Catalogs:
 * 1. Recent -> https://www.data18.com/movies
 * 2. Models -> https://www.pornpics.de/pornstars/?gender=female&orientation=straight&s=trending
 * 3. Recent Series -> https://www.data18.com/movies/series
 * 4. Studios -> https://www.pornpics.de/channels/
 * 5. Showcase -> https://www.data18.com/movies/showcases
 *
 * Poster Enhancement:
 * - Queries IMDb suggestion API -> TMDb Search -> Fallback Data18/PornPics cover.
 *
 * Playback & Download:
 * - Episodic layout for movies:
 *   * Episode: "Full Movie" -> Resolves SpeedPorn (1080p direct stream) + 1337x Torrent Magnets + Scraper Network.
 *   * Episode: "Part 1 / Scene 1", "Part 2 / Scene 2"... -> Resolves ParadiseHill direct MP4 scene streams.
 */
class Himeros : MainAPI() {
    override var mainUrl = "https://www.data18.com"
    override var name = "Himeros"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie, TvType.TvSeries)

    val pornpicsUrl = "https://www.pornpics.de"
    val paradiseUrl = "https://en.paradisehill.cc"
    val speedpornUrl = "https://speedporn.net"
    val porntrexUrl = "https://www.porntrex.com"
    val epornerUrl = "https://www.eporner.com"

    val data18Headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://www.data18.com/",
        "Cookie" to "data_user_enter=UNK-en-1; data_user_captcha=1; data_user_navigation=1"
    )

    val pornpicsHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$pornpicsUrl/"
    )

    val paradiseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$paradiseUrl/",
        "Cookie" to "is18=1; _csrf-frontend=1"
    )

    val speedpornHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$speedpornUrl/"
    )

    private val jsonMapper = jacksonObjectMapper().apply {
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // 1. HOME PAGE CATALOG DEFINITIONS
    override val mainPage = mainPageOf(
        "d18_recent" to "Recent",
        "pp_models" to "Models",
        "d18_series" to "Recent Series",
        "pp_studios" to "Studios",
        "d18_showcases" to "Showcase"
    )

    // Data models for IMDb / TMDb / Paradise JSON
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ImdbImage(val imageUrl: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ImdbItem(val id: String? = null, val l: String? = null, val y: Int? = null, val i: ImdbImage? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ImdbSuggestionResponse(val d: List<ImdbItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbMovieItem(val title: String? = null, val poster_path: String? = null, val release_date: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSearchResponse(val results: List<TmdbMovieItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoSource(val src: String? = null, val type: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoListItem(val sources: List<VideoSource>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HimerosEpisodePayload(
        val type: String = "full_movie", // "full_movie", "paradise_part", "d18_scene", "d18_movie_ep"
        val title: String = "",
        val movieTitle: String? = null,
        val streamUrl: String? = null,
        val sceneUrl: String? = null,
        val partIndex: Int? = null,
        val performers: List<String>? = null
    )

    // Helper: Enhance Poster via IMDb -> TMDb -> Fallback
    suspend fun fetchEnhancedPoster(title: String, fallback: String?): String? {
        if (title.isBlank()) return fallback
        val cleanTitle = title
            .replace(Regex("""(?i)\b(vol\.?|volume|scene|part|xxx|4k|1080p|full\s+movie)\b.*"""), "")
            .replace(Regex("""[^\w\s]"""), " ")
            .trim()
            .ifBlank { title.trim() }

        // 1. IMDb Suggestion API
        val imdbPoster = runCatching {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
            val url = "https://v3.sg.media-imdb.com/suggestion/x/$encoded.json"
            val text = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
            val res = jsonMapper.readValue(text, ImdbSuggestionResponse::class.java)
            res.d?.firstOrNull { !it.i?.imageUrl.isNullOrBlank() }?.i?.imageUrl
        }.getOrNull()

        if (!imdbPoster.isNullOrBlank()) return imdbPoster

        // 2. TMDb Search API
        val tmdbPoster = runCatching {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?api_key=b058a5e30536f903e1c2cb1e360e2fd4&query=$encoded"
            val text = app.get(url).text
            val res = jsonMapper.readValue(text, TmdbSearchResponse::class.java)
            val path = res.results?.firstOrNull { !it.poster_path.isNullOrBlank() }?.poster_path
            if (path != null) "https://image.tmdb.org/t/p/w500$path" else null
        }.getOrNull()

        if (!tmdbPoster.isNullOrBlank()) return tmdbPoster

        return fallback
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // Row 1: Recent Movies from Data18
            "d18_recent" -> {
                val url = if (page <= 1) "$mainUrl/movies" else "$mainUrl/movies/page/$page"
                val doc = app.get(url, headers = data18Headers).document
                doc.select("div[id^='mitem'], div.boxep1, div.relative.content-div").mapNotNull {
                    parseData18MovieCard(it)
                }
            }

            // Row 2: Trending Models from PornPics
            "pp_models" -> {
                val url = if (page <= 1) {
                    "$pornpicsUrl/pornstars/?gender=female&orientation=straight&s=trending"
                } else {
                    "$pornpicsUrl/pornstars/?gender=female&orientation=straight&s=trending&page=$page"
                }
                val doc = app.get(url, headers = pornpicsHeaders).document
                doc.select("li.thumb-block, li:has(a[href*='/pornstars/']), div.thumb-holder, a[href*='/pornstars/']").mapNotNull {
                    parsePornPicsModelCard(it)
                }
            }

            // Row 3: Recent Series from Data18
            "d18_series" -> {
                val url = if (page <= 1) "$mainUrl/movies/series" else "$mainUrl/movies/series/page/$page"
                val doc = app.get(url, headers = data18Headers).document
                doc.select("div.boxep1, a[href*='/movie-series']").mapNotNull {
                    parseData18SeriesCard(it)
                }
            }

            // Row 4: Studios from PornPics
            "pp_studios" -> {
                val url = if (page <= 1) "$pornpicsUrl/channels/" else "$pornpicsUrl/channels/?page=$page"
                val doc = app.get(url, headers = pornpicsHeaders).document
                doc.select("li.thumb-block, li:has(a[href*='/channels/']), div.thumb-holder, a[href*='/channels/']").mapNotNull {
                    parsePornPicsStudioCard(it)
                }
            }

            // Row 5: Showcases from Data18
            "d18_showcases" -> {
                val url = if (page <= 1) "$mainUrl/movies/showcases" else "$mainUrl/movies/showcases/page/$page"
                val doc = app.get(url, headers = data18Headers).document
                doc.select("div.boxep1, a[href*='/showcase']").mapNotNull {
                    parseData18ShowcaseCard(it)
                }
            }

            else -> emptyList()
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = items.isNotEmpty()
        )
    }

    // --- CARD PARSERS ---

    private fun parseData18MovieCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/movies/']:not(:has(img))") ?: element.selectFirst("a[href*='/movies/']") ?: return null
        val href = linkEl.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val rawTitle = element.selectFirst("a.gen12, .gen12 a, div.gen12, p.genmed a, b a")?.text()?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: linkEl.attr("title").trim().ifBlank { null }
            ?: linkEl.text().trim().ifBlank { null }
            ?: return null

        val cleanTitle = rawTitle.replace(Regex("""^#\d+\s*"""), "").trim()
        if (cleanTitle.isBlank()) return null

        val imgEl = element.selectFirst("img")
        val poster = imgEl?.attr("src")?.ifBlank { null }
            ?: imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("data-original")

        return MovieSearchResponse(
            name = cleanTitle,
            url = href,
            apiName = this.name,
            type = TvType.Movie,
            posterUrl = poster
        )
    }

    private fun parsePornPicsModelCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/pornstars/']") ?: return null
        val href = linkEl.attr("href").let { if (it.startsWith("http")) it else "$pornpicsUrl$it" }
        val slug = href.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank() || slug == "pornstars" || href.contains("/list/")) return null

        val title = element.selectFirst(".name, span.title, .title, .thumb__title, .actor-name")?.text()?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        val imgEl = element.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("data-original")?.ifBlank { null }
            ?: imgEl?.attr("src")?.ifBlank { null }

        val fullPoster = if (poster != null && poster.startsWith("http")) poster else if (poster != null) "$pornpicsUrl$poster" else null

        return TvSeriesSearchResponse(
            name = title,
            url = href,
            apiName = this.name,
            type = TvType.TvSeries,
            posterUrl = fullPoster,
            posterHeaders = pornpicsHeaders
        )
    }

    private fun parseData18SeriesCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/movie-series']") ?: element.selectFirst("a[href*='/series/']") ?: return null
        val href = linkEl.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = element.selectFirst("p.genmed, b, a")?.text()?.trim() ?: linkEl.text().trim()
        if (title.isBlank()) return null

        val imgEl = element.selectFirst("img")
        val poster = imgEl?.attr("src")?.ifBlank { null } ?: imgEl?.attr("data-src")

        return TvSeriesSearchResponse(
            name = title,
            url = href,
            apiName = this.name,
            type = TvType.TvSeries,
            posterUrl = poster,
            posterHeaders = data18Headers
        )
    }

    private fun parsePornPicsStudioCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/channels/']") ?: return null
        val href = linkEl.attr("href").let { if (it.startsWith("http")) it else "$pornpicsUrl$it" }
        val slug = href.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank() || slug == "channels") return null

        val title = element.selectFirst(".name, span.title, .title, .thumb__title, .channel-name")?.text()?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        val imgEl = element.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("data-original")?.ifBlank { null }
            ?: imgEl?.attr("src")?.ifBlank { null }

        val fullPoster = if (poster != null && poster.startsWith("http")) poster else if (poster != null) "$pornpicsUrl$poster" else null

        return TvSeriesSearchResponse(
            name = title,
            url = href,
            apiName = this.name,
            type = TvType.TvSeries,
            posterUrl = fullPoster,
            posterHeaders = pornpicsHeaders
        )
    }

    private fun parseData18ShowcaseCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/showcase']") ?: element.selectFirst("a[href*='/movies/']") ?: return null
        val href = linkEl.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = element.selectFirst("b, p, a")?.text()?.trim() ?: linkEl.text().trim()
        if (title.isBlank()) return null

        val imgEl = element.selectFirst("img")
        val poster = imgEl?.attr("src")?.ifBlank { null } ?: imgEl?.attr("data-src")

        return MovieSearchResponse(
            name = title,
            url = href,
            apiName = this.name,
            type = TvType.Movie,
            posterUrl = poster
        )
    }

    // --- LOAD DETAIL PAGE ---

    override suspend fun load(url: String): LoadResponse? {
        return when {
            // 1. Data18 Series Page
            url.contains("/movie-series") || url.contains("/series/") -> loadData18Series(url)

            // 2. PornPics Performer / Model Page
            url.contains("/pornstars/") -> loadPornPicsModel(url)

            // 3. PornPics Studio / Channel Page
            url.contains("/channels/") -> loadPornPicsStudio(url)

            // 4. ParadiseHill Direct URL
            url.contains("paradisehill.cc") -> loadParadiseHillMovie(url)

            // 5. Data18 Movie Page (Default & Primary)
            else -> loadData18Movie(url)
        }
    }

    private suspend fun loadData18Movie(url: String): LoadResponse? {
        val doc = app.get(url, headers = data18Headers).document
        val title = doc.selectFirst("h1, .gen12 b, title")?.text()
            ?.replace(Regex("""(?i)\s*-\s*data18.*"""), "")
            ?.trim() ?: "Movie"

        val posterEl = doc.selectFirst("img.yborder, div.boxep1 img, img[src*='cdn.dt18.com/covers']")
        val rawPoster = posterEl?.attr("src")?.ifBlank { null } ?: posterEl?.attr("data-src")
        val enhancedPoster = fetchEnhancedPoster(title, rawPoster)

        val description = doc.selectFirst("div:contains(Story:), div:contains(Description:), p.genmed")?.text()?.trim()
        val year = doc.selectFirst("p:contains(Release Date:), p:contains(Year:), span.gen11")?.text()?.let {
            Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        val studio = doc.selectFirst("p:has(b:contains(Studio)) a, a[href*='/studios/']")?.text()?.trim()
        val actors = doc.select("a[href*='/name/']").mapNotNull {
            val name = it.text().trim()
            if (name.isNotBlank()) {
                val avatar = it.selectFirst("img")?.attr("src")
                ActorData(Actor(name, avatar))
            } else null
        }.distinctBy { it.actor.name }

        val tags = doc.select("a[href*='/categories/'], a[href*='/tags/']").map { it.text().trim() }.filter { it.isNotBlank() }

        // Build Episodic list:
        // Episode 1: "Full Movie"
        val episodes = mutableListOf<Episode>()
        episodes.add(
            Episode(
                data = jsonMapper.writeValueAsString(HimerosEpisodePayload(
                    type = "full_movie",
                    title = "Full Movie",
                    movieTitle = title,
                    performers = actors.map { it.actor.name }
                )),
                name = "Full Movie",
                season = 1,
                episode = 1,
                posterUrl = enhancedPoster
            )
        )

        // Parallel check: Query ParadiseHill for parts of this movie
        val paradiseParts = fetchParadiseHillMovieParts(title)
        if (paradiseParts.isNotEmpty()) {
            paradiseParts.forEachIndexed { index, partStream ->
                val epNum = index + 2
                episodes.add(
                    Episode(
                        data = jsonMapper.writeValueAsString(HimerosEpisodePayload(
                            type = "paradise_part",
                            title = "Part ${index + 1}",
                            movieTitle = title,
                            streamUrl = partStream,
                            partIndex = index + 1
                        )),
                        name = "Part ${index + 1}",
                        season = 1,
                        episode = epNum,
                        posterUrl = enhancedPoster
                    )
                )
            }
        } else {
            // Check Data18 scenes
            val sceneEls = doc.select("a[href*='/scenes/'], div[id^='scene_']")
            var sceneIdx = 1
            sceneEls.forEach { el ->
                val sceneLink = el.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                val sceneTitle = el.text().trim().ifBlank { "Scene $sceneIdx" }
                val epNum = sceneIdx + 1
                episodes.add(
                    Episode(
                        data = jsonMapper.writeValueAsString(HimerosEpisodePayload(
                            type = "d18_scene",
                            title = sceneTitle,
                            movieTitle = title,
                            sceneUrl = sceneLink,
                            partIndex = sceneIdx,
                            performers = actors.map { it.actor.name }
                        )),
                        name = sceneTitle,
                        season = 1,
                        episode = epNum,
                        posterUrl = enhancedPoster
                    )
                )
                sceneIdx++
            }
        }

        return TvSeriesLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.TvSeries,
            episodes = episodes,
            posterUrl = enhancedPoster,
            year = year,
            plot = description,
            tags = tags,
            actors = actors
        )
    }

    private suspend fun fetchParadiseHillMovieParts(title: String): List<String> {
        return runCatching {
            val cleanTitle = title.replace(Regex("""(?i)\b(vol\.?|volume|4k|1080p)\b.*"""), "").trim()
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            val searchUrl = "$paradiseUrl/search/?pattern=$encoded&what=1"
            val html = app.get(searchUrl, headers = paradiseHeaders).text

            val filmHref = Regex("""href=['"](/[^'"\s]+-[0-9a-f]+/?|/[a-zA-Z0-9_\-]+/)['"]""").findAll(html)
                .map { it.groupValues[1] }
                .firstOrNull { !it.contains("/search") && !it.contains("/page") && !it.contains("/actor") && !it.contains("/studio") }

            if (filmHref != null) {
                val fullFilmUrl = if (filmHref.startsWith("http")) filmHref else "$paradiseUrl$filmHref"
                val filmHtml = app.get(fullFilmUrl, headers = paradiseHeaders).text
                val videoListMatch = Regex("""var\s+videoList\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(filmHtml)?.groupValues?.get(1)
                if (videoListMatch != null) {
                    val list = jsonMapper.readValue(videoListMatch, Array<VideoListItem>::class.java)
                    val urls = list.flatMap { item -> item.sources?.mapNotNull { it.src } ?: emptyList() }
                    if (urls.isNotEmpty()) return@runCatching urls
                }
            }
            emptyList<String>()
        }.getOrDefault(emptyList())
    }

    private suspend fun loadData18Series(url: String): LoadResponse? {
        val doc = app.get(url, headers = data18Headers).document
        val title = doc.selectFirst("h1, title")?.text()?.replace(Regex("""(?i)\s*-\s*data18.*"""), "")?.trim() ?: "Series"
        val posterEl = doc.selectFirst("img.yborder, div.boxep1 img, img")
        val rawPoster = posterEl?.attr("src")?.ifBlank { null } ?: posterEl?.attr("data-src")
        val enhancedPoster = fetchEnhancedPoster(title, rawPoster)

        val movieLinks = doc.select("a[href*='/movies/']").filter { el ->
            el.attr("href").matches(Regex(""".*/movies/\d+.*"""))
        }.distinctBy { el -> el.attr("href") }

        val episodes = movieLinks.mapIndexed { index, el ->
            val href = el.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val epTitle = el.text().trim().ifBlank { "Volume #${index + 1}" }
            Episode(
                data = jsonMapper.writeValueAsString(HimerosEpisodePayload(
                    type = "d18_movie_ep",
                    title = epTitle,
                    movieTitle = epTitle,
                    sceneUrl = href
                )),
                name = epTitle,
                season = 1,
                episode = index + 1,
                posterUrl = enhancedPoster
            )
        }

        return TvSeriesLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.TvSeries,
            episodes = episodes,
            posterUrl = enhancedPoster
        )
    }

    private suspend fun loadPornPicsModel(url: String): LoadResponse? {
        val doc = app.get(url, headers = pornpicsHeaders).document
        val name = doc.selectFirst("h1, .performer-name, .actor-name")?.text()?.trim() ?: "Model"
        val avatar = doc.selectFirst("img.avatar, .entity-card-avatar img, img")?.attr("src")

        val episodes = mutableListOf<Episode>()
        val spSearchUrl = "$speedpornUrl/?s=${URLEncoder.encode(name, "UTF-8")}"
        val spDoc = runCatching { app.get(spSearchUrl, headers = speedpornHeaders).document }.getOrNull()
        spDoc?.select(".video-block a.thumb, a[href*='speedporn.net/']")?.take(30)?.forEachIndexed { idx, el ->
            val epTitle = el.attr("title").ifBlank { el.text() }.trim()
            val epHref = el.attr("href")
            val epPoster = el.selectFirst("img")?.attr("src")
            if (epTitle.isNotBlank() && epHref.isNotBlank()) {
                episodes.add(
                    Episode(
                        data = jsonMapper.writeValueAsString(HimerosEpisodePayload(
                            type = "full_movie",
                            title = epTitle,
                            movieTitle = epTitle,
                            streamUrl = epHref,
                            performers = listOf(name)
                        )),
                        name = epTitle,
                        season = 1,
                        episode = idx + 1,
                        posterUrl = epPoster ?: avatar
                    )
                )
            }
        }

        return TvSeriesLoadResponse(
            name = name,
            url = url,
            apiName = this.name,
            type = TvType.TvSeries,
            episodes = episodes,
            posterUrl = avatar
        )
    }

    private suspend fun loadPornPicsStudio(url: String): LoadResponse? {
        val doc = app.get(url, headers = pornpicsHeaders).document
        val studioName = doc.selectFirst("h1, .channel-name")?.text()?.trim() ?: "Studio"
        val logo = doc.selectFirst(".channel-avatar img, .entity-card-avatar img, img")?.attr("src")

        val episodes = mutableListOf<Episode>()
        val spSearchUrl = "$speedpornUrl/?s=${URLEncoder.encode(studioName, "UTF-8")}"
        val spDoc = runCatching { app.get(spSearchUrl, headers = speedpornHeaders).document }.getOrNull()
        spDoc?.select(".video-block a.thumb, a[href*='speedporn.net/']")?.take(30)?.forEachIndexed { idx, el ->
            val epTitle = el.attr("title").ifBlank { el.text() }.trim()
            val epHref = el.attr("href")
            val epPoster = el.selectFirst("img")?.attr("src")
            if (epTitle.isNotBlank() && epHref.isNotBlank()) {
                episodes.add(
                    Episode(
                        data = jsonMapper.writeValueAsString(HimerosEpisodePayload(
                            type = "full_movie",
                            title = epTitle,
                            movieTitle = epTitle,
                            streamUrl = epHref
                        )),
                        name = epTitle,
                        season = 1,
                        episode = idx + 1,
                        posterUrl = epPoster ?: logo
                    )
                )
            }
        }

        return TvSeriesLoadResponse(
            name = studioName,
            url = url,
            apiName = this.name,
            type = TvType.TvSeries,
            episodes = episodes,
            posterUrl = logo
        )
    }

    private suspend fun loadParadiseHillMovie(url: String): LoadResponse? {
        val html = app.get(url, headers = paradiseHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Film"
        val poster = doc.selectFirst("img.poster, img[src*='paradisehill']")?.attr("src")
        val enhancedPoster = fetchEnhancedPoster(title, poster)

        val videoListMatch = Regex("""var\s+videoList\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        val episodes = mutableListOf<Episode>()

        // Episode 1: Full Movie
        episodes.add(
            Episode(
                data = jsonMapper.writeValueAsString(HimerosEpisodePayload(
                    type = "full_movie",
                    title = "Full Movie",
                    movieTitle = title
                )),
                name = "Full Movie",
                season = 1,
                episode = 1,
                posterUrl = enhancedPoster
            )
        )

        if (videoListMatch != null) {
            val list = jsonMapper.readValue(videoListMatch, Array<VideoListItem>::class.java)
            val urls = list.flatMap { item -> item.sources?.mapNotNull { it.src } ?: emptyList() }
            urls.forEachIndexed { index, partUrl ->
                episodes.add(
                    Episode(
                        data = jsonMapper.writeValueAsString(HimerosEpisodePayload(
                            type = "paradise_part",
                            title = "Part ${index + 1}",
                            movieTitle = title,
                            streamUrl = partUrl,
                            partIndex = index + 1
                        )),
                        name = "Part ${index + 1}",
                        season = 1,
                        episode = index + 2,
                        posterUrl = enhancedPoster
                    )
                )
            }
        }

        return TvSeriesLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.TvSeries,
            episodes = episodes,
            posterUrl = enhancedPoster
        )
    }

    // --- PLAYBACK & STREAM RESOLVER (loadLinks) ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching {
            jsonMapper.readValue(data, HimerosEpisodePayload::class.java)
        }.getOrNull() ?: return false

        coroutineScope {
            when (payload.type) {
                // 1. Full Movie: Resolve via SpeedPorn + 1337x Torrents + Scrapers
                "full_movie" -> {
                    val searchTitle = payload.movieTitle ?: payload.title

                    // Launch SpeedPorn Resolver
                    launch {
                        resolveSpeedPornStreams(searchTitle, callback)
                    }

                    // Launch 1337x Torrent Resolver
                    launch {
                        resolve1337xTorrents(searchTitle, callback)
                    }

                    // Launch Scraper Network Search (Eporner / PornTrex fallback)
                    launch {
                        resolveScraperNetworkStreams(searchTitle, payload.performers, callback)
                    }
                }

                // 2. ParadiseHill Part: Direct MP4 stream
                "paradise_part" -> {
                    val src = payload.streamUrl
                    if (!src.isNullOrBlank()) {
                        callback(
                            ExtractorLink(
                                source = "ParadiseHill",
                                name = "ParadiseHill - ${payload.title} (Direct MP4)",
                                url = src,
                                referer = "$paradiseUrl/",
                                quality = Qualities.P1080.value,
                                isM3u8 = false
                            )
                        )
                    }
                    Unit
                }

                // 3. Data18 Scene: Match scene clips across Eporner & PornTrex
                "d18_scene" -> {
                    val searchTerms = mutableListOf<String>()
                    payload.performers?.firstOrNull()?.let { searchTerms.add("$it ${payload.movieTitle ?: ""}") }
                    searchTerms.add(payload.title)

                    searchTerms.forEach { term ->
                        launch {
                            resolveScraperNetworkStreams(term, payload.performers, callback)
                        }
                    }
                }

                // 4. Data18 Movie Episode in Series
                "d18_movie_ep" -> {
                    val searchTitle = payload.movieTitle ?: payload.title
                    launch {
                        resolveSpeedPornStreams(searchTitle, callback)
                    }
                    launch {
                        resolve1337xTorrents(searchTitle, callback)
                    }
                }

                else -> {}
            }
            Unit
        }
        return true
    }

    // --- STREAM RESOLVERS ---

    private suspend fun resolveSpeedPornStreams(title: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val clean = title.replace(Regex("""(?i)\b(vol\.?|volume|4k|1080p)\b.*"""), "").trim()
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val searchUrl = "$speedpornUrl/?s=$encoded"
            val doc = app.get(searchUrl, headers = speedpornHeaders).document

            val firstMatch = doc.selectFirst(".video-block a.thumb, a[href*='speedporn.net/']")?.attr("href")
            if (!firstMatch.isNullOrBlank()) {
                val videoDoc = app.get(firstMatch, headers = speedpornHeaders).document
                val iframeSrc = videoDoc.selectFirst("iframe[src*='voe.sx'], iframe[src*='streamtape'], iframe[src*='dood'], iframe[src*='vtube']")?.attr("src")
                if (!iframeSrc.isNullOrBlank()) {
                    loadExtractor(iframeSrc, firstMatch, subtitleCallback = {}, callback = callback)
                }

                val html = videoDoc.html()
                val mp4Match = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(html)?.value
                if (!mp4Match.isNullOrBlank()) {
                    callback(
                        ExtractorLink(
                            source = "SpeedPorn",
                            name = "SpeedPorn - Full Movie (1080p)",
                            url = mp4Match,
                            referer = "$speedpornUrl/",
                            quality = Qualities.P1080.value,
                            isM3u8 = false
                        )
                    )
                }
            }
        }
    }

    private suspend fun resolve1337xTorrents(title: String, callback: (ExtractorLink) -> Unit) {
        val mirrors = listOf("https://1337x.to", "https://1377x.to", "https://1337x.st", "https://1337x.is")
        val cleanTitle = title.replace(Regex("""[^\w\s]"""), " ").trim()
        val query = URLEncoder.encode(cleanTitle, "UTF-8")

        for (mirror in mirrors) {
            val success = runCatching {
                val searchUrl = "$mirror/search/$query/1/"
                val res = app.get(searchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).document
                val torrentLinks = res.select("td.name a[href*='/torrent/']").take(4)
                if (torrentLinks.isNotEmpty()) {
                    torrentLinks.forEach { tLink ->
                        val tUrl = mirror + tLink.attr("href")
                        val tDoc = app.get(tUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).document
                        val magnet = tDoc.selectFirst("a[href^='magnet:?']")?.attr("href")
                        val size = tDoc.selectFirst("span:contains(Total size) + span, li:contains(Total size) span")?.text() ?: ""
                        val seeders = tDoc.selectFirst("span.seeds")?.text() ?: ""
                        if (!magnet.isNullOrBlank()) {
                            callback(
                                ExtractorLink(
                                    source = "1337x Torrent",
                                    name = "1337x Torrent [Seeds: $seeders | Size: $size]",
                                    url = magnet,
                                    referer = "$mirror/",
                                    quality = Qualities.P1080.value,
                                    isM3u8 = false
                                )
                            )
                        }
                    }
                    true
                } else false
            }.getOrDefault(false)
            if (success) break
        }
    }

    private suspend fun resolveScraperNetworkStreams(
        query: String,
        performers: List<String>?,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val cleanQuery = query.replace(Regex("""(?i)\b(vol\.?|volume|full\s+movie)\b.*"""), "").trim()
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

            val epSearchUrl = "$epornerUrl/search/$encoded/"
            val epDoc = app.get(epSearchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).document
            val epVideo = epDoc.selectFirst("div.mb, div.video-box, a[href*='/video-']")?.selectFirst("a[href*='/video-']")?.attr("href")
            if (!epVideo.isNullOrBlank()) {
                val fullUrl = if (epVideo.startsWith("http")) epVideo else "$epornerUrl$epVideo"
                val epPage = app.get(fullUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
                val dloadMatches = Regex("""href=['"](/dload/[^'"]+)['"]""").findAll(epPage)
                dloadMatches.take(3).forEach { m ->
                    val dUrl = "$epornerUrl${m.groupValues[1]}"
                    callback(
                        ExtractorLink(
                            source = "Eporner",
                            name = "Eporner HD",
                            url = dUrl,
                            referer = "$epornerUrl/",
                            quality = Qualities.P1080.value,
                            isM3u8 = false
                        )
                    )
                }
            }
        }
    }

    // --- SEARCH ---

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

        return coroutineScope {
            val d18Deferred = async {
                runCatching {
                    val url = "$mainUrl/movies/search?k=$encoded"
                    val doc = app.get(url, headers = data18Headers).document
                    doc.select("div[id^='mitem'], div.boxep1").mapNotNull {
                        parseData18MovieCard(it)
                    }
                }.getOrDefault(emptyList())
            }

            val spDeferred = async {
                runCatching {
                    val url = "$speedpornUrl/?s=$encoded"
                    val doc = app.get(url, headers = speedpornHeaders).document
                    doc.select(".video-block, a.thumb").mapNotNull { el ->
                        val linkEl = el.selectFirst("a[href*='speedporn.net/']") ?: el
                        val href = linkEl.attr("href")
                        val title = linkEl.attr("title").ifBlank { linkEl.text() }.trim()
                        val poster = el.selectFirst("img")?.attr("src")
                        if (title.isNotBlank() && href.isNotBlank()) {
                            MovieSearchResponse(
                                name = title,
                                url = href,
                                apiName = this@Himeros.name,
                                type = TvType.Movie,
                                posterUrl = poster
                            )
                        } else null
                    }
                }.getOrDefault(emptyList())
            }

            val results = mutableListOf<SearchResponse>()
            results.addAll(d18Deferred.await())
            results.addAll(spDeferred.await())
            results.distinctBy { it.url }
        }
    }
}
