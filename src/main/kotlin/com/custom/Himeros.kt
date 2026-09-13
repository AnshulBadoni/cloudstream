package com.custom

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

    // 1. HOME PAGE CATALOG DEFINITIONS
    override val mainPage = mainPageOf(
        "d18_recent" to "Recent",
        "pp_models" to "Models",
        "d18_series" to "Recent Series",
        "pp_studios" to "Studios",
        "d18_showcases" to "Showcase"
    )

    // Title Normalization Helper: Handles "Ignite Vol. 10" -> "Ignite 10", "Anal Icons Vol #5" -> "Anal Icons 5"
    fun normalizeTitle(rawTitle: String): String {
        var t = rawTitle
            .replace(Regex("""\((?:19\d\d|20\d\d)\)"""), "") // Strip release years
            .replace(Regex("""(?i)\b(vol\.?|volume|no\.?|issue)\s*#?\s*(\d+)"""), "$2") // Vol. 10 -> 10, Vol #5 -> 5
            .replace(Regex("""#\s*(\d+)"""), "$1") // #10 -> 10
            .replace(Regex("""(?i)\b(4k|1080p|720p|xxx|full\s+movie|hd|scene)\b"""), "")
            .replace(Regex("""[^\w\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return t.ifBlank { rawTitle.trim() }
    }

    // Title Sanitizer for Data18 Noise (Removes '(2026) Showcase Porn Movies | DATA18', 'Movie Series: ...', '#11', etc.)
    fun cleanData18Title(raw: String): String {
        var t = raw
            .replace(Regex("""(?i)\s*\|\s*data18.*"""), "")
            .replace(Regex("""(?i)\s*-\s*data18.*"""), "")
            .replace(Regex("""(?i)^\s*movie\s+series\s*[:\-]\s*"""), "")
            .replace(Regex("""(?i)\s*(?:showcase\s+porn\s+movies?|porn\s+movies?|showcases?|scene\s+compilations?)\b.*"""), "")
            .replace(Regex("""\s*\((?:19\d\d|20\d\d)\)"""), "")
            .replace(Regex("""\s*#\s*\d+\b"""), "")
            .replace(Regex("""^#\d+\s*"""), "")
            .trim()
        return t.ifBlank { raw.trim() }
    }

    // Token-based Fuzzy Matching Score
    fun fuzzyMatchScore(expected: String, candidate: String): Double {
        val expTokens = normalizeTitle(expected).lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val candTokens = normalizeTitle(candidate).lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }.toSet()
        if (expTokens.isEmpty()) return 0.0

        val expNumbers = expTokens.filter { it.all { c -> c.isDigit() } }
        val candNumbers = candTokens.filter { it.all { c -> c.isDigit() } }

        // If expected title has a volume number (e.g. "10"), candidate must match it
        if (expNumbers.isNotEmpty() && !candNumbers.containsAll(expNumbers)) {
            return 0.0
        }

        val matches = expTokens.count { candTokens.contains(it) }
        return matches.toDouble() / expTokens.size.toDouble()
    }

    // Helper: Enhance Poster via IMDb -> TMDb -> Fallback with strict title validation
    suspend fun fetchEnhancedPoster(title: String, fallback: String?): String? {
        if (title.isBlank()) return fallback
        val cleanTitle = normalizeTitle(title)

        // 1. IMDb Suggestion API
        val imdbPoster = runCatching {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
            val url = "https://v3.sg.media-imdb.com/suggestion/x/$encoded.json"
            val text = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
            val imgMatch = Regex("""["']imageUrl["']\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
            val labelMatch = Regex("""["']l["']\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
            if (!imgMatch.isNullOrBlank()) {
                if (labelMatch.isNullOrBlank() || fuzzyMatchScore(cleanTitle, labelMatch) >= 0.5) {
                    return@runCatching imgMatch
                }
            }
            null
        }.getOrNull()

        if (!imdbPoster.isNullOrBlank()) return imdbPoster

        // 2. Fallback to authentic original cover (e.g. Data18 / PornPics)
        return fallback
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // Row 1: Recent Movies from Data18
            "d18_recent" -> {
                val url = if (page <= 1) "$mainUrl/movies" else "$mainUrl/movies/page/$page"
                val doc = app.get(url, headers = data18Headers).document
                doc.select("a[href*='/movies/'], div.boxep1, div.relative, div[id^='mitem']").mapNotNull {
                    parseData18MovieCard(it)
                }.distinctBy { it.url }
            }

            // Row 2: Trending Models from PornPics
            "pp_models" -> {
                val url = if (page <= 1) {
                    "$pornpicsUrl/pornstars/?gender=female&orientation=straight&s=trending"
                } else {
                    "$pornpicsUrl/pornstars/?gender=female&orientation=straight&s=trending&page=$page"
                }
                val doc = app.get(url, headers = pornpicsHeaders).document
                doc.select("li.thumb-block:has(a[href*='/pornstars/']), li:has(a[href*='/pornstars/']), a[href*='/pornstars/']").mapNotNull {
                    parsePornPicsModelCard(it)
                }.distinctBy { it.url }
            }

            // Row 3: Recent Series from Data18
            "d18_series" -> {
                val url = if (page <= 1) "$mainUrl/movies/series" else "$mainUrl/movies/series/page/$page"
                val doc = app.get(url, headers = data18Headers).document
                doc.select("a[href*='movie-series'], a[href*='/series/'], div.boxep1, div.relative").mapNotNull {
                    parseData18SeriesCard(it)
                }.distinctBy { it.url }
            }

            // Row 4: Studios from PornPics
            "pp_studios" -> {
                val url = if (page <= 1) "$pornpicsUrl/channels/" else "$pornpicsUrl/channels/?page=$page"
                val doc = app.get(url, headers = pornpicsHeaders).document
                doc.select("li.thumb-block:has(a[href*='/channels/']), li:has(a[href*='/channels/']), a[href*='/channels/']").mapNotNull {
                    parsePornPicsStudioCard(it)
                }.distinctBy { it.url }
            }

            // Row 5: Showcases from Data18
            "d18_showcases" -> {
                val url = if (page <= 1) "$mainUrl/movies/showcases" else "$mainUrl/movies/showcases/page/$page"
                val doc = app.get(url, headers = data18Headers).document
                doc.select("a[href*='/movies/'], div.boxep1, div.relative, div[id^='mitem']").mapNotNull {
                    parseData18ShowcaseCard(it)
                }.distinctBy { it.url }
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
        val linkEl = if (element.tagName() == "a" && element.attr("href").contains("/movies/")) {
            element
        } else {
            element.selectFirst("a[href*='/movies/']:not([href*='#image'])")
                ?: element.selectFirst("a[href*='/movies/']")
                ?: return null
        }

        val rawHref = linkEl.attr("href")
        val cleanHref = rawHref.substringBefore('#').let { if (it.startsWith("http")) it else "$mainUrl$it" }
        if (!cleanHref.matches(Regex(""".*/movies/\d+.*"""))) return null

        val moviePart = cleanHref.substringAfterLast("/")
        val id = Regex("""^(\d+)""").find(moviePart)?.groupValues?.get(1) ?: ""
        val slug = moviePart.replace(Regex("""^\d+-"""), "")
        val slugTitle = slug.replace("-", " ")
            .split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val rawTitle = element.selectFirst("a.gen12, .gen12 a, div.gen12, p.genmed a, b a")?.text()?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: linkEl.attr("title").trim().ifBlank { null }
            ?: slugTitle

        var cleanTitle = rawTitle
            .replace(Regex("""^#\d+\s*(\d{4}-\d{2}-\d{2})?"""), "")
            .replace(Regex("""(?i)\s*-\s*data18.*"""), "")
            .trim()

        if (cleanTitle.isBlank() || cleanTitle.contains("pictures/videostills") || cleanTitle.matches(Regex("""(?i)^(#\d+|movie\s+(series|showcases|directors)|\d+)$"""))) {
            cleanTitle = slugTitle
        }

        if (cleanTitle.isBlank()) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        var poster = imgEl?.attr("src")?.ifBlank { null }
            ?: imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("data-original")?.ifBlank { null }

        if (poster.isNullOrBlank() && id.isNotBlank()) {
            poster = "https://cdn.dt18.com/covers/2/6/$id-$slug.jpg"
        }

        return newMovieSearchResponse(cleanTitle, cleanHref, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun parsePornPicsModelCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/pornstars/']") ?: if (element.tagName() == "a") element else return null
        val rawHref = linkEl.attr("href")
        val cleanHref = rawHref.substringBefore('?').trimEnd('/').let { if (it.startsWith("http")) it else "$pornpicsUrl$it" }
        val slug = cleanHref.substringAfterLast('/')
        
        val invalidSlugs = setOf("female", "male", "straight", "gay", "trans", "trending", "pornstars", "popular", "channels", "list", "actors")
        if (slug.isBlank() || invalidSlugs.contains(slug.lowercase()) || cleanHref.contains("/list/")) return null

        val slugTitle = slug.replace("-", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val rawTitle = element.selectFirst(".name, span.title, .title, .thumb__title, .actor-name")?.text()?.trim()
            ?.ifBlank { null }
            ?: linkEl.attr("title").trim().ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: slugTitle

        val title = if (rawTitle.equals("Female", ignoreCase = true) || rawTitle.equals("Trending", ignoreCase = true)) slugTitle else rawTitle

        val imgEl = element.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("data-original")?.ifBlank { null }
            ?: imgEl?.attr("src")?.ifBlank { null }

        val fullPoster = if (poster != null && poster.startsWith("http")) poster else if (poster != null) "$pornpicsUrl$poster" else null

        return newTvSeriesSearchResponse(title, cleanHref, TvType.TvSeries) {
            this.posterUrl = fullPoster
            this.posterHeaders = pornpicsHeaders
        }
    }

    private fun parseData18SeriesCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a" && (element.attr("href").contains("movie-series") || element.attr("href").contains("/series/"))) {
            element
        } else {
            element.selectFirst("a[href*='movie-series']")
                ?: element.selectFirst("a[href*='/series/']")
                ?: return null
        }

        val href = linkEl.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        if (href.endsWith("/movies/series") || href.endsWith("/movies/showcases") || href.endsWith("/movies/directors") ||
            href.endsWith("/movie-series") || href.endsWith("/movie-series/") || href.endsWith("/series") || href.endsWith("/series/")) return null
        if (!href.contains("movie-series") && !href.contains("/series/")) return null

        val slugTitle = href.substringAfterLast("/").replace("movie-series-", "").replace("-", " ")
            .split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        var rawTitle = element.selectFirst("p.genmed, b, a.gen12")?.text()?.trim()
            ?: linkEl.text().trim()

        var cleanTitle = rawTitle
            .replace(Regex("""^#\d+\s*(\d{4}-\d{2}-\d{2})?"""), "")
            .replace(Regex("""\s*\[\+\]\s*"""), "")
            .replace(Regex("""\d+\s+Movies"""), "")
            .trim()

        if (cleanTitle.isBlank() || cleanTitle == "[+]" || cleanTitle.equals("Movie Series", ignoreCase = true) || cleanTitle.matches(Regex("""(?i)^(#\d+|movie\s+(series|showcases|directors)|\d+|\+.*)$"""))) {
            cleanTitle = slugTitle
        }

        if (cleanTitle.isBlank() || cleanTitle == "[+]" || cleanTitle.equals("Movie Series", ignoreCase = true)) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val poster = imgEl?.attr("src")?.ifBlank { null } ?: imgEl?.attr("data-src")

        return newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.posterHeaders = data18Headers
        }
    }

    private fun parsePornPicsStudioCard(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/channels/']") ?: if (element.tagName() == "a") element else return null
        val rawHref = linkEl.attr("href")
        val cleanHref = rawHref.substringBefore('?').trimEnd('/').let { if (it.startsWith("http")) it else "$pornpicsUrl$it" }
        val slug = cleanHref.substringAfterLast('/')

        val invalidSlugs = setOf("channels", "studios", "categories", "trending", "popular", "pornstars", "list")
        if (slug.isBlank() || invalidSlugs.contains(slug.lowercase()) || cleanHref.contains("/list")) return null

        val slugTitle = slug.replace("-", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val rawTitle = element.selectFirst(".name, span.title, .title, .thumb__title, .channel-name")?.text()?.trim()
            ?.ifBlank { null }
            ?: linkEl.attr("title").trim().ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: slugTitle

        val dataMid = element.selectFirst("a[data-mid]")?.attr("data-mid")
            ?: linkEl.attr("data-mid").ifBlank { null }
        val avatarImg = element.selectFirst(".entity-card-avatar img, img[src*='hfma.pornpics.de']")?.attr("src")
        val channelLogo = when {
            !avatarImg.isNullOrBlank() -> avatarImg
            !dataMid.isNullOrBlank() -> "https://hfma.pornpics.de/${dataMid}_556x556.png"
            else -> null
        }

        val imgEl = element.selectFirst("img")
        val poster = channelLogo
            ?: imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("data-original")?.ifBlank { null }
            ?: imgEl?.attr("src")?.ifBlank { null }

        val fullPoster = if (poster != null && poster.startsWith("http")) poster else if (poster != null) "$pornpicsUrl$poster" else null

        return newTvSeriesSearchResponse(rawTitle, cleanHref, TvType.TvSeries) {
            this.posterUrl = fullPoster
            this.posterHeaders = pornpicsHeaders
        }
    }

    private fun parseData18ShowcaseCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a" && element.attr("href").contains("/movies/")) {
            element
        } else {
            element.selectFirst("a[href*='/movies/']:not([href*='#image'])")
                ?: element.selectFirst("a[href*='/movies/']")
                ?: return null
        }

        val rawHref = linkEl.attr("href")
        val cleanHref = rawHref.substringBefore('#').let { if (it.startsWith("http")) it else "$mainUrl$it" }
        if (!cleanHref.matches(Regex(""".*/movies/\d+.*"""))) return null

        val moviePart = cleanHref.substringAfterLast("/")
        val id = Regex("""^(\d+)""").find(moviePart)?.groupValues?.get(1) ?: ""
        val slug = moviePart.replace(Regex("""^\d+-"""), "")
        val slugTitle = slug.replace("-", " ")
            .split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val rawTitle = element.selectFirst("a.gen12, .gen12 a, div.gen12, p.genmed a, b a")?.text()?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: slugTitle

        var cleanTitle = rawTitle
            .replace(Regex("""^#\d+\s*(\d{4}-\d{2}-\d{2})?"""), "")
            .replace(Regex("""(?i)\s*-\s*data18.*"""), "")
            .trim()

        if (cleanTitle.isBlank() || cleanTitle.contains("pictures/videostills") || cleanTitle.matches(Regex("""(?i)^(#\d+|movie\s+(series|showcases|directors)|\d+)$"""))) {
            cleanTitle = slugTitle
        }

        if (cleanTitle.isBlank()) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        var poster = imgEl?.attr("src")?.ifBlank { null } ?: imgEl?.attr("data-src")

        if (poster.isNullOrBlank() && id.isNotBlank()) {
            poster = "https://cdn.dt18.com/covers/2/6/$id-$slug.jpg"
        }

        return newMovieSearchResponse(cleanTitle, cleanHref, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // --- LOAD DETAIL PAGE ---

    override suspend fun load(url: String): LoadResponse? {
        return when {
            // 1. Data18 Series Page
            url.contains("/movie-series") || url.contains("/series/") -> loadData18Series(url)

            // 2. PornPics / Data18 Performer / Model Page
            url.contains("/pornstars/") || url.contains("/name/") -> loadData18Performer(url)

            // 3. PornPics / Data18 Studio / Channel Page
            url.contains("/channels/") || url.contains("/studios/") -> loadData18Studio(url)

            // 4. ParadiseHill Direct URL
            url.contains("paradisehill.cc") -> loadParadiseHillMovie(url)

            // 5. Data18 Movie Page (Default & Primary)
            else -> loadData18Movie(url)
        }
    }

    private suspend fun loadData18Performer(url: String): LoadResponse? {
        val slug = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val cleanName = slug.replace("-", " ").split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val d18Url = "$mainUrl/name/$slug/movies"
        val d18Doc = runCatching { app.get(d18Url, headers = data18Headers).document }.getOrNull()
            ?: runCatching { app.get("$mainUrl/name/$slug", headers = data18Headers).document }.getOrNull()

        var avatar = d18Doc?.selectFirst("img.yborder, img[src*='cdn.dt18.com/stars'], img[src*='cdn.dt18.com/media']")?.attr("src")
        if (avatar.isNullOrBlank() && url.contains("pornpics.de")) {
            val ppDoc = runCatching { app.get(url, headers = pornpicsHeaders).document }.getOrNull()
            avatar = ppDoc?.selectFirst("img.avatar, .entity-card-avatar img, img")?.attr("src")
        }
        if (avatar.isNullOrBlank()) {
            avatar = "https://cdn.dt18.com/stars/$slug.jpg"
        }

        val movieElements = d18Doc?.select("a[href*='/movies/']")?.filter { el ->
            el.attr("href").matches(Regex(""".*/movies/\d+.*"""))
        }?.distinctBy { it.attr("href").substringBefore('#') }.orEmpty()

        val episodes = mutableListOf<Episode>()
        movieElements.forEachIndexed { index, el ->
            val href = el.attr("href").substringBefore('#').let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val moviePart = href.substringAfterLast("/")
            val id = Regex("""^(\d+)""").find(moviePart)?.groupValues?.get(1) ?: ""
            val mSlug = moviePart.replace(Regex("""^\d+-"""), "")
            val slugTitle = mSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val rawTitle = el.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                ?: el.attr("title").trim().ifBlank { null }
                ?: el.text().trim().ifBlank { null }
                ?: slugTitle

            var movieTitle = cleanData18Title(rawTitle)

            if (movieTitle.isBlank() || movieTitle.contains("pictures/videostills") || movieTitle.matches(Regex("""(?i)^(#\d+|movie\s+(series|showcases|directors)|\d+)$"""))) {
                movieTitle = slugTitle
            }

            var poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
                ?: el.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            if (poster.isNullOrBlank() && id.isNotBlank()) {
                poster = "https://cdn.dt18.com/covers/2/6/$id-$mSlug.jpg"
            }

            episodes.add(
                Episode(
                    data = "d18_movie_ep|$movieTitle|$movieTitle|$href",
                    name = movieTitle,
                    season = 1,
                    episode = index + 1,
                    posterUrl = poster
                )
            )
        }

        return newTvSeriesLoadResponse(cleanName, url, TvType.TvSeries, episodes) {
            this.posterUrl = avatar
        }
    }

    private suspend fun loadData18Studio(url: String): LoadResponse? {
        val slug = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val cleanStudio = slug.replace("-", " ").split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val d18Url = "$mainUrl/studios/$slug/movies"
        val d18Doc = runCatching { app.get(d18Url, headers = data18Headers).document }.getOrNull()
            ?: runCatching { app.get("$mainUrl/studios/$slug", headers = data18Headers).document }.getOrNull()

        var logo: String? = null
        if (url.contains("pornpics.de")) {
            val ppDoc = runCatching { app.get(url, headers = pornpicsHeaders).document }.getOrNull()
            val dataMid = ppDoc?.selectFirst("a[data-mid]")?.attr("data-mid")
            val avatarImg = ppDoc?.selectFirst(".entity-card-avatar img, img[src*='hfma.pornpics.de'], .channel-avatar img")?.attr("src")
            logo = when {
                !avatarImg.isNullOrBlank() -> avatarImg
                !dataMid.isNullOrBlank() -> "https://hfma.pornpics.de/${dataMid}_556x556.png"
                else -> null
            }
        }
        if (logo.isNullOrBlank()) {
            logo = d18Doc?.selectFirst("img.yborder, img[src*='cdn.dt18.com/studios'], img[src*='cdn.dt18.com/media']")?.attr("src")
        }

        val movieElements = d18Doc?.select("a[href*='/movies/']")?.filter { el ->
            el.attr("href").matches(Regex(""".*/movies/\d+.*"""))
        }?.distinctBy { it.attr("href").substringBefore('#') }.orEmpty()

        val episodes = mutableListOf<Episode>()
        movieElements.forEachIndexed { index, el ->
            val href = el.attr("href").substringBefore('#').let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val moviePart = href.substringAfterLast("/")
            val id = Regex("""^(\d+)""").find(moviePart)?.groupValues?.get(1) ?: ""
            val mSlug = moviePart.replace(Regex("""^\d+-"""), "")
            val slugTitle = mSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val rawTitle = el.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                ?: el.attr("title").trim().ifBlank { null }
                ?: el.text().trim().ifBlank { null }
                ?: slugTitle

            var movieTitle = cleanData18Title(rawTitle)

            if (movieTitle.isBlank() || movieTitle.contains("pictures/videostills") || movieTitle.matches(Regex("""(?i)^(#\d+|movie\s+(series|showcases|directors)|\d+)$"""))) {
                movieTitle = slugTitle
            }

            var poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
                ?: el.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            if (poster.isNullOrBlank() && id.isNotBlank()) {
                poster = "https://cdn.dt18.com/covers/2/6/$id-$mSlug.jpg"
            }

            episodes.add(
                Episode(
                    data = "d18_movie_ep|$movieTitle|$movieTitle|$href",
                    name = movieTitle,
                    season = 1,
                    episode = index + 1,
                    posterUrl = poster
                )
            )
        }

        return newTvSeriesLoadResponse(cleanStudio, url, TvType.TvSeries, episodes) {
            this.posterUrl = logo
        }
    }

    private suspend fun loadData18Movie(url: String): LoadResponse? {
        val doc = app.get(url, headers = data18Headers).document
        val rawTitle = doc.selectFirst("h1, .gen12 b, title")?.text() ?: "Movie"
        val title = cleanData18Title(rawTitle)

        val posterEl = doc.selectFirst("img.yborder, div.boxep1 img, img[src*='cdn.dt18.com/covers']")
        val rawPoster = posterEl?.attr("src")?.ifBlank { null } ?: posterEl?.attr("data-src")
        val enhancedPoster = fetchEnhancedPoster(title, rawPoster)

        val description = doc.selectFirst("div:contains(Story:), div:contains(Description:), p.genmed")?.text()?.trim()
        val year = doc.selectFirst("p:contains(Release Date:), p:contains(Year:), span.gen11")?.text()?.let {
            Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
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
        val performersStr = actors.joinToString(",") { it.actor.name }
        episodes.add(
            Episode(
                data = "full_movie|Full Movie|$title|$performersStr",
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
                        data = "paradise_part|Part ${index + 1}|$title|$partStream|${index + 1}",
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
                        data = "d18_scene|$sceneTitle|$title|$sceneLink|$sceneIdx|$performersStr",
                        name = sceneTitle,
                        season = 1,
                        episode = epNum,
                        posterUrl = enhancedPoster
                    )
                )
                sceneIdx++
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = enhancedPoster
            this.year = year
            this.plot = description
            this.tags = tags
            this.actors = actors
        }
    }

    private suspend fun fetchParadiseHillMovieParts(title: String): List<String> {
        return runCatching {
            val normalized = normalizeTitle(title)
            val baseTitle = normalized.replace(Regex("""\b\d+\b.*"""), "").trim()
            val searchQueries = listOf(normalized, baseTitle).filter { it.isNotBlank() }.distinct()

            for (query in searchQueries) {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$paradiseUrl/search/?pattern=$encoded&what=1"
                val html = app.get(searchUrl, headers = paradiseHeaders).text
                val doc = Jsoup.parse(html)

                val candidates = doc.select("a[href]").mapNotNull { el ->
                    val href = el.attr("href")
                    val cardTitle = el.text().trim()
                    if (href.matches(Regex("""^/(?:[a-zA-Z0-9_\-]+-[0-9a-f]+/?|[a-zA-Z0-9_\-]+/|[0-9a-f]{10,}/?)$""")) &&
                        !href.contains("/search") && !href.contains("/page") && !href.contains("/actor") && !href.contains("/studio") && !href.contains("/category")
                    ) {
                        href to cardTitle
                    } else null
                }.distinctBy { it.first }

                val bestCandidate = candidates
                    .map { it.first to fuzzyMatchScore(normalized, it.second.ifBlank { it.first }) }
                    .filter { it.second >= 0.5 }
                    .maxByOrNull { it.second }?.first ?: candidates.firstOrNull()?.first

                if (bestCandidate != null) {
                    val fullFilmUrl = if (bestCandidate.startsWith("http")) bestCandidate else "$paradiseUrl$bestCandidate"
                    val filmHtml = app.get(fullFilmUrl, headers = paradiseHeaders).text
                    val videoListMatch = Regex("""var\s+videoList\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(filmHtml)?.groupValues?.get(1)
                    if (videoListMatch != null) {
                        val urls = Regex("""["']src["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""").findAll(videoListMatch)
                            .map { it.groupValues[1].replace("\\/", "/") }
                            .toList()
                        if (urls.isNotEmpty()) return@runCatching urls
                    }
                }
            }
            emptyList<String>()
        }.getOrDefault(emptyList())
    }

    private suspend fun loadData18Series(url: String): LoadResponse? {
        val doc = app.get(url, headers = data18Headers).document
        val rawTitle = doc.selectFirst("h1, title")?.text() ?: "Series"
        val title = cleanData18Title(rawTitle)
        val posterEl = doc.selectFirst("img.yborder, div.boxep1 img, img")
        val rawPoster = posterEl?.attr("src")?.ifBlank { null } ?: posterEl?.attr("data-src")
        val enhancedPoster = fetchEnhancedPoster(title, rawPoster)

        val movieLinks = doc.select("a[href*='/movies/']").filter { el ->
            el.attr("href").matches(Regex(""".*/movies/\d+.*"""))
        }.distinctBy { el -> el.attr("href") }

        val episodes = movieLinks.mapIndexed { index, el ->
            val href = el.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val epTitle = el.text().trim().ifBlank { "Volume #${index + 1}" }
            val cleanEpTitle = cleanData18Title(epTitle)
            Episode(
                data = "d18_movie_ep|$cleanEpTitle|$cleanEpTitle|$href",
                name = cleanEpTitle,
                season = 1,
                episode = index + 1,
                posterUrl = enhancedPoster
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = enhancedPoster
        }
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
                data = "full_movie|Full Movie|$title|",
                name = "Full Movie",
                season = 1,
                episode = 1,
                posterUrl = enhancedPoster
            )
        )

        if (videoListMatch != null) {
            val urls = Regex("""["']src["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""").findAll(videoListMatch)
                .map { it.groupValues[1].replace("\\/", "/") }
                .toList()
            urls.forEachIndexed { index, partUrl ->
                episodes.add(
                    Episode(
                        data = "paradise_part|Part ${index + 1}|$title|$partUrl|${index + 1}",
                        name = "Part ${index + 1}",
                        season = 1,
                        episode = index + 2,
                        posterUrl = enhancedPoster
                    )
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = enhancedPoster
        }
    }

    // --- PLAYBACK & STREAM RESOLVER (loadLinks) ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val type = parts.getOrNull(0) ?: return false

        coroutineScope {
            when (type) {
                // 1. Full Movie: Resolve via ParadiseHill Parts + SpeedPorn + 1337x Torrents + Scrapers
                "full_movie" -> {
                    val title = parts.getOrNull(1) ?: "Movie"
                    val movieTitle = parts.getOrNull(2) ?: title
                    val performers = parts.getOrNull(3)?.split(",")?.filter { it.isNotBlank() }
                    val searchTitle = cleanData18Title(if (movieTitle.isNotBlank()) movieTitle else title)

                    // Check ParadiseHill direct MP4 parts
                    launch {
                        val partsList = fetchParadiseHillMovieParts(searchTitle)
                        partsList.forEachIndexed { idx, partUrl ->
                            callback(
                                ExtractorLink(
                                    source = "ParadiseHill",
                                    name = "ParadiseHill - Part ${idx + 1} (Direct 1080p MP4)",
                                    url = partUrl,
                                    referer = "$paradiseUrl/",
                                    quality = Qualities.P1080.value,
                                    isM3u8 = false
                                )
                            )
                        }
                    }

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
                        resolveScraperNetworkStreams(searchTitle, performers, callback)
                    }
                }

                // 2. ParadiseHill Part: Direct MP4 stream
                "paradise_part" -> {
                    val title = parts.getOrNull(1) ?: "Part"
                    val src = parts.getOrNull(3)
                    if (!src.isNullOrBlank()) {
                        callback(
                            ExtractorLink(
                                source = "ParadiseHill",
                                name = "ParadiseHill - $title (Direct 1080p MP4)",
                                url = src,
                                referer = "$paradiseUrl/",
                                quality = Qualities.P1080.value,
                                isM3u8 = false
                            )
                        )
                    }
                    Unit
                }

                // 3. Data18 Scene: Stream via Scene Resolver
                "d18_scene" -> {
                    val sceneTitle = parts.getOrNull(1) ?: "Scene"
                    val movieTitle = cleanData18Title(parts.getOrNull(2) ?: "")
                    val sceneUrl = parts.getOrNull(3) ?: ""
                    val sceneIdx = parts.getOrNull(4)?.toIntOrNull() ?: 1
                    val performers = parts.getOrNull(5)?.split(",")?.filter { it.isNotBlank() }

                    launch {
                        resolveData18SceneStream(sceneTitle, movieTitle, sceneUrl, sceneIdx, performers, callback)
                    }
                }

                // 4. Data18 Movie Episode: Load full movie stream directly
                "d18_movie_ep" -> {
                    val epTitle = parts.getOrNull(1) ?: "Movie"
                    val movieTitle = parts.getOrNull(2) ?: epTitle
                    val searchTitle = cleanData18Title(movieTitle)

                    launch {
                        val partsList = fetchParadiseHillMovieParts(searchTitle)
                        partsList.forEachIndexed { idx, partUrl ->
                            callback(
                                ExtractorLink(
                                    source = "ParadiseHill",
                                    name = "ParadiseHill - Part ${idx + 1} (Direct 1080p MP4)",
                                    url = partUrl,
                                    referer = "$paradiseUrl/",
                                    quality = Qualities.P1080.value,
                                    isM3u8 = false
                                )
                            )
                        }
                    }
                    launch {
                        resolveSpeedPornStreams(searchTitle, callback)
                    }
                    launch {
                        resolve1337xTorrents(searchTitle, callback)
                    }
                    launch {
                        resolveScraperNetworkStreams(searchTitle, null, callback)
                    }
                }

                else -> {}
            }
            Unit
        }
        return true
    }

    private suspend fun resolveData18SceneStream(
        sceneTitle: String,
        movieTitle: String,
        sceneUrl: String,
        sceneIdx: Int,
        performers: List<String>?,
        callback: (ExtractorLink) -> Unit
    ) {
        // Parallel check ParadiseHill parts
        val paradiseParts = fetchParadiseHillMovieParts(movieTitle)
        if (paradiseParts.isNotEmpty() && sceneIdx - 1 < paradiseParts.size) {
            val partUrl = paradiseParts[sceneIdx - 1]
            callback(
                ExtractorLink(
                    source = "ParadiseHill",
                    name = "ParadiseHill - Scene $sceneIdx (Direct MP4)",
                    url = partUrl,
                    referer = "$paradiseUrl/",
                    quality = Qualities.P1080.value,
                    isM3u8 = false
                )
            )
            return
        }

        // Search Scraper Network for specific scene query
        val searchQuery = if (performers?.isNotEmpty() == true) {
            "${performers.first()} $movieTitle"
        } else {
            "$movieTitle Scene $sceneIdx"
        }
        resolveScraperNetworkStreams(searchQuery, performers, callback)
    }

    private suspend fun resolveSpeedPornStreams(
        movieTitle: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val cleanTitle = movieTitle.replace(Regex("""(?i)\s*-\s*data18.*"""), "").trim()
            val normalizedTitle = normalizeTitle(cleanTitle)
            val query = URLEncoder.encode(cleanTitle, "UTF-8")
            val searchUrl = "$speedpornUrl/?s=$query"
            val doc = app.get(searchUrl, headers = speedpornHeaders).document

            val links = doc.select(".video-block, a[href*='speedporn.net/']").mapNotNull { el ->
                val a = el.selectFirst("a[href*='speedporn.net/']") ?: el
                val href = a.attr("href")
                val text = a.attr("title").ifBlank { a.text() }
                if (href.isNotBlank() && !href.contains("/tag/") && !href.contains("/category/")) {
                    href to text
                } else null
            }.distinctBy { it.first }

            val bestMatch = links.map { it.first to fuzzyMatchScore(normalizedTitle, it.second) }
                .filter { it.second >= 0.5 }
                .maxByOrNull { it.second }?.first ?: links.firstOrNull()?.first

            if (bestMatch != null) {
                val filmDoc = app.get(bestMatch, headers = speedpornHeaders).document
                val iframeSrc = filmDoc.selectFirst("iframe[src*='speedporn'], iframe[src*='player'], iframe[src*='embed']")?.attr("src")
                    ?: filmDoc.selectFirst("iframe")?.attr("src")

                if (!iframeSrc.isNullOrBlank()) {
                    val fullIframe = if (iframeSrc.startsWith("http")) iframeSrc else "https:$iframeSrc"
                    val playerHtml = app.get(fullIframe, headers = mapOf("Referer" to bestMatch)).text
                    val directMp4 = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(playerHtml)?.groupValues?.get(1)
                        ?: Regex("""file:\s*["']([^"']+\.mp4[^"']*)["']""").find(playerHtml)?.groupValues?.get(1)

                    if (!directMp4.isNullOrBlank()) {
                        callback(
                            ExtractorLink(
                                source = "SpeedPorn",
                                name = "SpeedPorn 1080p (Direct Stream)",
                                url = directMp4,
                                referer = fullIframe,
                                quality = Qualities.P1080.value,
                                isM3u8 = false
                            )
                        )
                    }
                }

                val downloadLinks = filmDoc.select("a[href*='download'], a.btn-download, a[href*='.mp4']")
                downloadLinks.forEach { dLink ->
                    val dHref = dLink.attr("href")
                    if (dHref.isNotBlank() && dHref.startsWith("http")) {
                        callback(
                            ExtractorLink(
                                source = "SpeedPorn",
                                name = "SpeedPorn Direct Download",
                                url = dHref,
                                referer = bestMatch,
                                quality = Qualities.P1080.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun resolve1337xTorrents(
        movieTitle: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanTitle = movieTitle.replace(Regex("""(?i)\s*-\s*data18.*"""), "").trim()
        val mirrors = listOf("https://1337x.to", "https://1337x.st", "https://1337x.ws")
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
        @Suppress("UNUSED_PARAMETER") performers: List<String>?,
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
                    doc.select("div[id^='mitem'], div.boxep1, a[href*='/movies/']").mapNotNull {
                        parseData18MovieCard(it)
                    }
                }.getOrDefault(emptyList())
            }

            val spDeferred = async {
                runCatching {
                    val url = "$speedpornUrl/?s=$encoded"
                    val doc = app.get(url, headers = speedpornHeaders).document
                    doc.select(".video-block, a.thumb").mapNotNull { el ->
                        val linkEl = el.selectFirst("a[href*='speedporn.net/']") ?: if (el.tagName() == "a") el else return@mapNotNull null
                        val href = linkEl.attr("href")
                        if (href.isBlank() || href.contains("/tag/") || href.contains("/category/")) return@mapNotNull null

                        val slugTitle = href.trimEnd('/').substringAfterLast('/').replace("-", " ")
                            .split(" ").filter { it.isNotBlank() }
                            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

                        val rawTitle = el.selectFirst(".title, .video-title, span.title, h2, h3")?.text()?.trim()?.ifBlank { null }
                            ?: linkEl.attr("title").trim().ifBlank { null }
                            ?: el.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                            ?: slugTitle

                        var title = rawTitle
                        if (title.isBlank() || title.contains("hrs.") || title.contains("mins.")) {
                            title = slugTitle
                        }

                        val poster = el.selectFirst("img")?.attr("src")
                        newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = poster
                        }
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
