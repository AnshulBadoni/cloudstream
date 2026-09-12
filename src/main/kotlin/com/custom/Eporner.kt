package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Eporner : MainAPI() {
    override var mainUrl = "https://www.eporner.com"
    override var name = "Eporner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie, TvType.TvSeries)

    companion object {
        var modelPages: Int = 3
        var maxSearchPages: Int = 2

        val defaultHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to "https://www.eporner.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        val pornpicsHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to "https://www.pornpics.de/"
        )

        val popularChannels = listOf(
            "Vixen" to "vixen",
            "Tushy" to "tushy",
            "Blacked" to "blacked",
            "Brazzers" to "brazzers-network",
            "BangBros" to "bangbros-videos",
            "Scoreland" to "scoreland",
            "New Sensations" to "new-sensations",
            "Pure Taboo" to "pure-taboo",
            "Slayed" to "slayed",
            "Reality Kings" to "reality-kings",
            "Mofos" to "mofos",
            "Evil Angel" to "evil-angel",
            "Wicked" to "wicked",
            "Twistys" to "twistys",
            "Babes" to "babes",
            "Deeper" to "deeper"
        )

        fun transformHash(raw: String): String {
            if (raw.length != 32) return raw
            fun base36(hexChunk: String): String {
                val num = hexChunk.toLongOrNull(16) ?: return ""
                return java.lang.Long.toString(num, 36)
            }
            val p1 = base36(raw.substring(0, 8))
            val p2 = base36(raw.substring(8, 16))
            val p3 = base36(raw.substring(16, 24))
            val p4 = base36(raw.substring(24, 32))
            return p1 + p2 + p3 + p4
        }
    }

    // 1. HOME CATALOGS
    override val mainPage = mainPageOf(
        "$mainUrl/cat/all/PROD-pro/SORT-top-weekly/?quality=2160&duration_min=1800" to "Weekly Top",
        "actors" to "Actors",
        "$mainUrl/cat/pornstar/?quality=1080&duration_min=1740" to "Recent",
        "$mainUrl/cat/all/PROD-pro/SORT-top-monthly/?quality=1080" to "Monthly Top",
        "$mainUrl/channels/" to "Studios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val name = request.name

        if (name == "Actors" || path == "actors") {
            val actors = TrailerHelper.fetchPornPicsTrendingActors(page, mainUrl, "pornstar")
            return newHomePageResponse(
                HomePageList(name = name, list = actors, isHorizontalImages = false),
                hasNext = actors.isNotEmpty()
            )
        }

        if (name == "Studios" || path.contains("/channels/")) {
            val studios = withContext(Dispatchers.IO) {
                popularChannels.map { (sName, sSlug) ->
                    async {
                        val ppPoster = TrailerHelper.fetchPornPicsStudioLogo(sSlug)
                        TvSeriesSearchResponse(
                            name = sName,
                            url = "$mainUrl/channel/$sSlug/",
                            apiName = this@Eporner.name,
                            type = TvType.TvSeries,
                            posterUrl = ppPoster ?: "$mainUrl/favicon.ico",
                            posterHeaders = if (ppPoster != null) pornpicsHeaders else defaultHeaders
                        )
                    }
                }.awaitAll()
            }
            return newHomePageResponse(
                HomePageList(name = name, list = studios, isHorizontalImages = false),
                hasNext = false
            )
        }

        val fullUrl = if (page <= 1) {
            path
        } else {
            if (path.contains("?")) {
                val base = path.substringBefore("?")
                val query = path.substringAfter("?")
                "$base$page/?$query"
            } else {
                "${path.trimEnd('/')}/$page/"
            }
        }

        val doc = runCatching { app.get(fullUrl, headers = defaultHeaders).document }.getOrNull()
        val items = doc?.select("div.mb, div.mbblock, div[id^='vf'], div.post")
            ?.mapNotNull { parseVideoCard(it) }
            ?.distinctBy { it.url }
            ?: emptyList()

        val finalItems = if (items.isEmpty() && doc != null) {
            doc.select("a[href*='/video-'], a[href*='/hd-porn/']")
                .mapNotNull { parseAnchorCard(it) }
                .distinctBy { it.url }
        } else items

        return newHomePageResponse(
            HomePageList(name = name, list = finalItems, isHorizontalImages = true),
            hasNext = finalItems.isNotEmpty()
        )
    }

    private fun parseVideoCard(el: Element): SearchResponse? {
        val linkEl = el.selectFirst("a[href*='/video-'], a[href*='/hd-porn/'], a") ?: return null
        val href = linkEl.attr("href").ifBlank { null } ?: return null
        if (href.contains("/pornstar/") || href.contains("/channel/") || href.contains("/cat/") || href == "#") return null

        val imgEl = el.selectFirst("img") ?: linkEl.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("src")?.ifBlank { null }

        val title = el.selectFirst(".mbtit, .mbtitle, .title, h2, h3, a[title]")?.text()?.trim()
            ?.ifBlank { null }
            ?: imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: return null

        return MovieSearchResponse(
            name = title,
            url = fixUrl(href, mainUrl),
            apiName = this.name,
            type = TvType.Movie,
            posterUrl = fixUrlNull(poster, mainUrl),
            posterHeaders = defaultHeaders
        )
    }

    private fun parseAnchorCard(a: Element): SearchResponse? {
        val href = a.attr("href").ifBlank { null } ?: return null
        if (!href.contains("/video-") && !href.contains("/hd-porn/")) return null

        val imgEl = a.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("src")?.ifBlank { null }
        val title = a.attr("title").ifBlank { null }
            ?: imgEl?.attr("alt")?.ifBlank { null }
            ?: a.text().trim().ifBlank { null }
            ?: return null

        return MovieSearchResponse(
            name = title,
            url = fixUrl(href, mainUrl),
            apiName = this.name,
            type = TvType.Movie,
            posterUrl = fixUrlNull(poster, mainUrl),
            posterHeaders = defaultHeaders
        )
    }

    // 2. SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val encoded = URLEncoder.encode(cleanQuery.replace(" ", "-"), "UTF-8").lowercase()

        // 1. Direct Performer / Channel Card if matching
        val isActorOrChannel = popularChannels.any { it.first.equals(cleanQuery, ignoreCase = true) || it.second.equals(cleanQuery, ignoreCase = true) }
        val ppLogo = TrailerHelper.fetchPornPicsStudioLogo(encoded)
        if (ppLogo != null || isActorOrChannel) {
            results.add(
                TvSeriesSearchResponse(
                    name = cleanQuery.replaceFirstChar { it.uppercase() },
                    url = "$mainUrl/channel/$encoded/",
                    apiName = this.name,
                    type = TvType.TvSeries,
                    posterUrl = ppLogo ?: "$mainUrl/favicon.ico",
                    posterHeaders = if (ppLogo != null) pornpicsHeaders else defaultHeaders
                )
            )
        } else {
            // Check if Pornstar exists
            val starDoc = runCatching { app.get("$mainUrl/pornstar/$encoded/", headers = defaultHeaders).document }.getOrNull()
            if (starDoc != null && !starDoc.title().contains("404") && starDoc.select("a[href*='/video-']").isNotEmpty()) {
                val starPoster = starDoc.selectFirst("div.profile img, .img-holder img, meta[property='og:image']")?.attr("src")
                    ?: starDoc.selectFirst("meta[property='og:image']")?.attr("content")
                results.add(
                    TvSeriesSearchResponse(
                        name = cleanQuery.replaceFirstChar { it.uppercase() },
                        url = "$mainUrl/pornstar/$encoded/",
                        apiName = this.name,
                        type = TvType.TvSeries,
                        posterUrl = fixUrlNull(starPoster, mainUrl),
                        posterHeaders = defaultHeaders
                    )
                )
            }
        }

        // 2. Search Videos
        for (p in 1..maxSearchPages.coerceIn(1, 5)) {
            val searchUrl = if (p <= 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/$p/"
            val doc = runCatching { app.get(searchUrl, headers = defaultHeaders).document }.getOrNull() ?: break
            val vids = doc.select("div.mb, div.mbblock, div[id^='vf'], a[href*='/video-'], a[href*='/hd-porn/']")
                .mapNotNull { if (it.tagName() == "a") parseAnchorCard(it) else parseVideoCard(it) }
            if (vids.isEmpty()) break
            results.addAll(vids)
        }

        return results.distinctBy { it.url }
    }

    // 3. LOAD DETAILS (CHANNELS/MODELS AS TVSERIES, VIDEOS AS MOVIE)
    override suspend fun load(url: String): LoadResponse? {
        val isChannelOrModel = url.contains("/channel/") || url.contains("/pornstar/")

        if (isChannelOrModel) {
            val slug = url.trimEnd('/').substringAfterLast('/')
            val isChannel = url.contains("/channel/")
            val prefix = if (isChannel) "channel" else "pornstar"

            val targetUrl = "${url.trimEnd('/')}/"
            val doc = runCatching { app.get(targetUrl, headers = defaultHeaders).document }.getOrNull()
            val rawName = doc?.selectFirst("h1, .profile-title, .title")?.text()?.trim()
                ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            val name = rawName.replaceFirstChar { it.uppercase() }

            val ppPoster = if (isChannel) {
                TrailerHelper.fetchPornPicsStudioLogo(slug)
            } else {
                TrailerHelper.fetchPornPicsActorAvatar(slug) ?: TrailerHelper.fetchPornPicsStudioLogo(slug)
            }
            val poster = if (!ppPoster.isNullOrBlank()) {
                ppPoster
            } else {
                doc?.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc?.selectFirst(".profile img, .img-holder img, img")?.attr("src")
            }

            val episodes = mutableListOf<Episode>()

            for (p in 1..modelPages.coerceIn(1, 10)) {
                val pageUrl = if (p <= 1) "$mainUrl/$prefix/$slug/" else "$mainUrl/$prefix/$slug/$p/"
                val pageDoc = runCatching { app.get(pageUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                val cards = pageDoc.select("div.mb, div.mbblock, div[id^='vf'], a[href*='/video-'], a[href*='/hd-porn/']")
                if (cards.isEmpty()) break

                cards.forEach { el ->
                    val linkEl = if (el.tagName() == "a") el else el.selectFirst("a[href*='/video-'], a[href*='/hd-porn/']") ?: return@forEach
                    val link = linkEl.attr("href").ifBlank { null } ?: return@forEach
                    if (link.contains("/pornstar/") || link.contains("/channel/") || link == "#") return@forEach

                    val imgEl = el.selectFirst("img") ?: linkEl.selectFirst("img")
                    val title = el.selectFirst(".mbtit, .mbtitle, .title, h2, h3")?.text()?.trim()
                        ?.ifBlank { null }
                        ?: imgEl?.attr("alt")?.ifBlank { null }
                        ?: linkEl.attr("title").ifBlank { null }
                        ?: "Scene ${episodes.size + 1}"
                    val img = imgEl?.attr("data-src")?.ifBlank { null } ?: imgEl?.attr("src")?.ifBlank { null }

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

            if (episodes.isEmpty()) {
                val cleanQuery = slug.replace("-", "+")
                for (p in 1..modelPages.coerceIn(1, 10)) {
                    val searchUrl = if (p <= 1) "$mainUrl/search/$cleanQuery/" else "$mainUrl/search/$cleanQuery/$p/"
                    val searchDoc = runCatching { app.get(searchUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                    val cards = searchDoc.select("div.mb, div.mbblock, div[id^='vf'], a[href*='/video-'], a[href*='/hd-porn/']")
                    if (cards.isEmpty()) break

                    cards.forEach { el ->
                        val linkEl = if (el.tagName() == "a") el else el.selectFirst("a[href*='/video-'], a[href*='/hd-porn/']") ?: return@forEach
                        val link = linkEl.attr("href").ifBlank { null } ?: return@forEach
                        if (link.contains("/pornstar/") || link.contains("/channel/") || link == "#") return@forEach

                        val imgEl = el.selectFirst("img") ?: linkEl.selectFirst("img")
                        val title = el.selectFirst(".mbtit, .mbtitle, .title, h2, h3")?.text()?.trim()
                            ?.ifBlank { null }
                            ?: imgEl?.attr("alt")?.ifBlank { null }
                            ?: linkEl.attr("title").ifBlank { null }
                            ?: "Scene ${episodes.size + 1}"
                        val img = imgEl?.attr("data-src")?.ifBlank { null } ?: imgEl?.attr("src")?.ifBlank { null }

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
            }

            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = fixUrlNull(poster, url)
                this.posterHeaders = if (poster?.contains("pornpics") == true) pornpicsHeaders else defaultHeaders
                this.plot = "Videos featuring $name on Eporner"
                this.showStatus = ShowStatus.Completed
            }
        } else {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.selectFirst("h1, meta[property='og:title']")?.text()?.trim()
                ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?: "Eporner Video"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("video[poster], img")?.attr("poster")

            val actors = doc.select("a[href*='/pornstar/']").map { it.text().trim() }.filter { it.isNotBlank() }
            val tags = doc.select("a[href*='/cat/'], a[href*='/search/']").map { it.text().trim() }.filter { it.isNotBlank() }

            val recommendations = doc.select("div.mb, div.mbblock, a[href*='/video-'], a[href*='/hd-porn/']")
                .mapNotNull { if (it.tagName() == "a") parseAnchorCard(it) else parseVideoCard(it) }
                .filter { it.url != url }
                .distinctBy { it.url }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, mainUrl)
                this.posterHeaders = defaultHeaders
                this.plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        }
    }

    // 4. STREAM EXTRACTION (DIRECT MP4 DOWNLOAD RESOLUTION FROM 240P UP TO 4K)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (TrailerHelper.handleTrailerStream(data, name, callback)) {
            return true
        }

        val movieUrl = fixUrl(data, mainUrl)
        val doc = runCatching { app.get(movieUrl, headers = defaultHeaders).document }.getOrNull() ?: return false
        val html = doc.html()
        var count = 0

        // 1. Direct /dload/ links in page (240p to 2160p 4K)
        val dloadLinks = doc.select("a[href*='/dload/']")
        for (a in dloadLinks) {
            val href = a.attr("href").ifBlank { null } ?: continue
            val text = a.text()
            val quality = when {
                text.contains("2160p") || text.contains("4K") -> Qualities.P2160.value
                text.contains("1440p") || text.contains("2K") -> Qualities.P1440.value
                text.contains("1080p") -> Qualities.P1080.value
                text.contains("720p") -> Qualities.P720.value
                text.contains("480p") -> Qualities.P480.value
                text.contains("360p") -> Qualities.P360.value
                text.contains("240p") -> Qualities.P240.value
                else -> Qualities.P720.value
            }

            callback(
                ExtractorLink(
                    source = name,
                    name = "$name ${quality}p MP4",
                    url = fixUrl(href, mainUrl),
                    referer = "$mainUrl/",
                    quality = quality,
                    headers = defaultHeaders
                )
            )
            count++
        }

        // 2. Schema contentUrl / direct video source
        val schemaUrl = Regex(""""contentUrl":\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (!schemaUrl.isNullOrBlank()) {
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name HD MP4 (Direct)",
                    url = schemaUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    headers = defaultHeaders
                )
            )
            count++
        }

        // 3. Fallback to video data-vid
        val dataVid = doc.selectFirst("video#EPvideo, video[data-vid]")?.attr("data-vid")
        if (!dataVid.isNullOrBlank()) {
            val vidClean = if (dataVid.startsWith("http")) dataVid else "https://gvideo.eporner.com/$dataVid"
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name Stream",
                    url = vidClean,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    headers = defaultHeaders
                )
            )
            count++
        }

        return count > 0
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
