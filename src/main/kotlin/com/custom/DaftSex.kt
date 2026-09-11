package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

/**
 * DaftSex Standalone Provider for CloudStream
 */
class DaftSex : MainAPI() {
    override var mainUrl = "https://daftsex.biz"
    override var name = "DaftSex"
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

    private val popularStudios = listOf(
        "Vixen" to "vixen",
        "Blacked" to "blacked",
        "Brazzers" to "brazzers",
        "Tushy" to "tushy",
        "Deeper" to "deeper",
        "Reality Kings" to "reality-kings",
        "Naughty America" to "naughty-america",
        "Pure Taboo" to "pure-taboo",
        "Slayed" to "slayed",
        "Mofos" to "mofos",
        "Evil Angel" to "evil-angel",
        "Wicked" to "wicked",
        "Twistys" to "twistys",
        "Babes" to "babes",
        "FakeHub" to "fakehub",
        "PropertySex" to "propertysex"
    )

    // 1. HOME PAGE CATALOG DEFINITIONS (Standard 6 Rows, Clean, No Emojis)
    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "actors" to "Actors",
        "studios" to "Studios",
        "vixen" to "Vixen",
        "blacked" to "Blacked",
        "brazzers" to "Brazzers"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when (request.data) {
            // Trending / Featured Row (Tushy 4K Playlist)
            "trending" -> {
                val url = if (page <= 1) "$mainUrl/playlist/tushy-4k" else "$mainUrl/playlist/tushy-4k/$page"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("a[href*='/movie/'], div.movie-item a")?.mapNotNull { parseMovieCard(it) }.orEmpty()
            }

            // Actors / Models (PornPics Trending Models with DaftSex video profile link)
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
                    val doc = runCatching { app.get("$mainUrl/models", headers = defaultHeaders).document }.getOrNull()
                    doc?.select("a[href*='/video/']")?.mapNotNull { parseActorCard(it) }.orEmpty()
                }
            }

            // Studios / Channels Directory (Prioritizing PornPics channel posters)
            "studios" -> {
                coroutineScope {
                    popularStudios.map { (sName, sSlug) ->
                        async {
                            val ppPoster = fetchPornPicsChannelPoster(sSlug)
                            val poster = if (!ppPoster.isNullOrBlank()) {
                                ppPoster
                            } else {
                                val studioDoc = runCatching { app.get("$mainUrl/video/$sSlug", headers = defaultHeaders).document }.getOrNull()
                                extractImg(studioDoc?.selectFirst("div.video-thumb, [data-src], img"))
                            }
                            newTvSeriesSearchResponse(sName, "$mainUrl/video/$sSlug", TvType.TvSeries) {
                                this.posterUrl = fixUrlNull(poster, mainUrl)
                                this.posterHeaders = if (poster?.contains("pornpics") == true) pornpicsHeaders else defaultHeaders
                            }
                        }
                    }.awaitAll()
                }
            }

            // Studio Specific Channels: Vixen, Blacked, Brazzers
            "vixen" -> {
                val url = if (page <= 1) "$mainUrl/video/vixen" else "$mainUrl/video/vixen/$page"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("a[href*='/movie/'], div.movie-item a")?.mapNotNull { parseMovieCard(it) }.orEmpty()
            }

            "blacked" -> {
                val url = if (page <= 1) "$mainUrl/video/blacked" else "$mainUrl/video/blacked/$page"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("a[href*='/movie/'], div.movie-item a")?.mapNotNull { parseMovieCard(it) }.orEmpty()
            }

            else -> {
                val url = if (page <= 1) "$mainUrl/video/brazzers" else "$mainUrl/video/brazzers/$page"
                val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
                doc?.select("a[href*='/movie/'], div.movie-item a")?.mapNotNull { parseMovieCard(it) }.orEmpty()
            }
        }

        val hasNextPage = items.size >= 12
        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = request.data != "actors")
        return newHomePageResponse(homePageList, hasNextPage)
    }

    // 2. SEARCH WITH PERFORMER MATCH PRIORITIZATION
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val slugQuery = query.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val queryWords = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val titleCaseQuery = queryWords.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        // 1. Search actors (Synthetic Performer Card with Dynamic Poster)
        val actorsJob = async {
            runCatching {
                val list = mutableListOf<SearchResponse>()
                if (queryWords.size in 1..4 && slugQuery.isNotBlank()) {
                    val actorDoc = runCatching { app.get("$mainUrl/video/$slugQuery", headers = defaultHeaders).document }.getOrNull()
                    val actorPoster = extractImg(actorDoc?.selectFirst("div.video-thumb, [data-src], img"))
                        ?: fetchPornPicsChannelPoster(slugQuery)

                    list.add(
                        newTvSeriesSearchResponse(
                            name = titleCaseQuery,
                            url = "$mainUrl/video/$slugQuery",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = fixUrlNull(actorPoster, mainUrl)
                            this.posterHeaders = if (actorPoster?.contains("pornpics") == true) pornpicsHeaders else defaultHeaders
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
                    val url = if (p <= 1) "$mainUrl/video/$slugQuery" else "$mainUrl/video/$slugQuery/$p"
                    val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull() ?: break
                    val items = doc.select("a[href*='/movie/'], div.movie-item a").mapNotNull { parseMovieCard(it) }
                    if (items.isEmpty()) break
                    list.addAll(items)
                }
                list.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }

        val actors = actorsJob.await()
        val videos = videosJob.await()

        (actors + videos).distinctBy { it.url }
    }

    // 3. LOAD RESPONSE (PERFORMER/CHANNEL OR VIDEO)
    override suspend fun load(url: String): LoadResponse {
        val isChannelOrPerformer = url.contains("/video/") && !url.contains("/movie/")

        if (isChannelOrPerformer) {
            val rawSlug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            val name = doc?.selectFirst("h1")?.text()?.trim()
                ?: rawSlug.replace("-", " ").split(" ").filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val slug = rawSlug.ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            val ppPoster = fetchPornPicsChannelPoster(slug)
            val poster = if (!ppPoster.isNullOrBlank()) {
                ppPoster
            } else {
                val daftPoster = extractImg(doc?.selectFirst("meta[property='og:image'], .profile, .img-holder, div.video-thumb, img"))
                daftPoster
            }

            val episodes = mutableListOf<Episode>()
            for (p in 1..modelPages.coerceIn(1, 10)) {
                val pageUrl = if (p <= 1) "$mainUrl/video/$slug" else "$mainUrl/video/$slug/$p"
                val pageDoc = runCatching { app.get(pageUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                val cards = pageDoc.select("a[href*='/movie/'], div.movie-item a")
                if (cards.isEmpty()) break
                cards.forEach { el ->
                    val linkEl = if (el.tagName() == "a") el else el.selectFirst("a[href*='/movie/']") ?: return@forEach
                    val link = linkEl.attr("href").ifBlank { null } ?: return@forEach

                    val title = linkEl.selectFirst("div.video-title, .video-title, .title, h1, h2, h3")?.text()?.trim()
                        ?.ifBlank { null }
                        ?: linkEl.attr("title").ifBlank { null }
                        ?: "DaftSex Video ${episodes.size + 1}"
                    val img = extractImg(el) ?: extractImg(linkEl)

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
                this.plot = "Videos for $name on DaftSex"
                this.showStatus = ShowStatus.Completed
            }
        } else {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.selectFirst("h1, .video-title")?.text()?.trim() ?: "DaftSex Video"
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: extractImg(doc.selectFirst("video[poster], .player, div.video-thumb, img"))

            val currentId = url.substringAfterLast("/").trim()
            val recommendations = doc.select("a[href*='/movie/']")
                .filter { a ->
                    val h = a.attr("href")
                    h.isNotBlank() && !h.contains(currentId)
                }
                .mapNotNull { parseMovieCard(it) }
                .distinctBy { it.url }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster, mainUrl)
                this.posterHeaders = defaultHeaders
                this.recommendations = recommendations
            }
        }
    }

    // 4. STREAM EXTRACTION (ARTPLAYER MULTI-RESOLUTION MP4 360P TO 4K)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var count = 0
        val movieUrl = fixUrl(data, mainUrl)
        val doc = runCatching { app.get(movieUrl, headers = defaultHeaders).document }.getOrNull()
        val rawHtml = doc?.html().orEmpty()

        // 1. Extract num parameter from page JS or URL fallback
        val numMatch = Regex("""['"]?num['"]?\s*[:=]\s*['"]?([a-zA-Z0-9_\-]+)['"]?""").find(rawHtml)?.groupValues?.get(1)
            ?: Regex("""num:\s*['"]([^'"]+)['"]""").find(rawHtml)?.groupValues?.get(1)
        val num = if (!numMatch.isNullOrBlank()) numMatch else movieUrl.trimEnd('/').substringAfterLast('/').substringBefore('.')

        val iframeReferer = "https://daftsex-biz.ibhan2.top/"
        val streamHeaders = mapOf(
            "Referer" to iframeReferer,
            "User-Agent" to (defaultHeaders["user-agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        )

        if (num.isNotBlank()) {
            // Step 1: Query moviesiframe2 endpoint for multi-resolution Artplayer streams
            val ajaxHeaders = mapOf(
                "User-Agent" to (defaultHeaders["user-agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"),
                "Referer" to movieUrl,
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*"
            )

            val ajaxRes = runCatching {
                app.post(
                    "$mainUrl/hash-daftsex",
                    data = mapOf("mix" to "moviesiframe2", "num" to num),
                    headers = ajaxHeaders
                ).text
            }.getOrNull()

            if (!ajaxRes.isNullOrBlank()) {
                val iframeMatch = Regex("""(?:https?://[^'"\s<>]+)?/iframe/(?:v2/|convert/v2/)[a-zA-Z0-9_\-/]+""")
                    .find(ajaxRes)?.value
                    ?: Regex("""(?:src=)?['"](https?://[^'"\s]+/iframe/(?:v2/|convert/v2/)[^'"\s]+|/iframe/(?:v2/|convert/v2/)[^'"\s]+)['"]""")
                        .find(ajaxRes)?.groupValues?.get(1)

                if (!iframeMatch.isNullOrBlank()) {
                    val convertPath = iframeMatch.replace("/iframe/v2/", "/iframe/convert/v2/")
                    val playerDomain = if (convertPath.startsWith("http")) convertPath else "https://daftsex-biz.ibhan2.top$convertPath"
                    val playerHtml = runCatching {
                        app.get(playerDomain, headers = mapOf("User-Agent" to (defaultHeaders["user-agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"), "Referer" to "$mainUrl/")).text
                    }.getOrNull()

                    if (!playerHtml.isNullOrBlank()) {
                        // Match Artplayer quality array: { html: '360p', url: '...' }
                        val qualityMatches = Regex("""html:\s*['"]([^'"]+)['"],\s*url:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).findAll(playerHtml)
                        for (qEntry in qualityMatches) {
                            val qLabel = qEntry.groupValues[1].trim()
                            val qUrl = qEntry.groupValues[2].trim()
                            if (qUrl.isBlank()) continue

                            val normalizedQuality = when {
                                qLabel.contains("4k", ignoreCase = true) || qLabel.contains("2160") -> "2160p"
                                qLabel.contains("1080") -> "1080p"
                                qLabel.contains("720") -> "720p"
                                qLabel.contains("480") -> "480p"
                                qLabel.contains("360") -> "360p"
                                else -> qLabel
                            }

                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "$name $qLabel MP4",
                                    url = qUrl,
                                    referer = iframeReferer,
                                    quality = getQualityFromName(normalizedQuality),
                                    isM3u8 = qUrl.contains(".m3u8"),
                                    headers = streamHeaders
                                )
                            )
                            count++
                        }

                        // Also match any direct video CDN links if quality array didn't match
                        if (count == 0) {
                            val directMatches = Regex("""['"](https?://[^'"\s<>]+\.mp4[^'"\s<>]*)['"]""").findAll(playerHtml)
                            for (m in directMatches) {
                                val qUrl = m.groupValues[1].trim()
                                val normalizedQuality = when {
                                    qUrl.contains("2160") || qUrl.contains("4k") -> "2160p"
                                    qUrl.contains("1080") -> "1080p"
                                    qUrl.contains("720") -> "720p"
                                    qUrl.contains("480") -> "480p"
                                    else -> "720p"
                                }
                                callback(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name $normalizedQuality MP4",
                                        url = qUrl,
                                        referer = iframeReferer,
                                        quality = getQualityFromName(normalizedQuality),
                                        isM3u8 = false,
                                        headers = streamHeaders
                                    )
                                )
                                count++
                            }
                        }
                    }
                }
            }

            // Step 2: Fallback to downvideo endpoint if Artplayer did not yield streams
            if (count == 0) {
                val downAjaxRes = runCatching {
                    app.post(
                        "$mainUrl/hash-daftsex",
                        data = mapOf("mix" to "downvideo", "num" to num, "op" to "down", "url" to "NQ=="),
                        headers = defaultHeaders + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data,
                            "Origin" to mainUrl
                        )
                    ).text
                }.getOrNull()

                if (!downAjaxRes.isNullOrBlank()) {
                    val downUrlMatch = Regex("""(?:https?://[^'"\s<>]+)?/download/v2/[a-zA-Z0-9_\-/]+""")
                        .find(downAjaxRes)?.value
                        ?: Regex("""(?:src=)?['"](https?://[^'"\s]+/download/v2/[^'"\s]+|/download/v2/[^'"\s]+)['"]""")
                            .find(downAjaxRes)?.groupValues?.get(1)

                    if (!downUrlMatch.isNullOrBlank()) {
                        val fullDownUrl = if (downUrlMatch.startsWith("http")) downUrlMatch else "https://daftsex-biz.ibhan2.top$downUrlMatch"
                        val downDoc = runCatching {
                            app.get(fullDownUrl, headers = mapOf("Referer" to "$mainUrl/", "referer" to "$mainUrl/")).document
                        }.getOrNull()

                        downDoc?.select("a.button-down, a[href*='vkuser.net'], a[href*='okcdn.ru'], a[download]")?.forEach { btn ->
                            val href = btn.attr("href").trim()
                            val text = btn.text().trim()
                            if (href.isNotBlank() && href.startsWith("http")) {
                                val normalizedQuality = when {
                                    text.contains("4k", ignoreCase = true) || text.contains("2160") -> "2160p"
                                    text.contains("1080") -> "1080p"
                                    text.contains("720") -> "720p"
                                    text.contains("480") -> "480p"
                                    text.contains("360") -> "360p"
                                    else -> "720p"
                                }
                                callback(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name ${if (text.isNotBlank()) text else "Direct"} MP4",
                                        url = href,
                                        referer = iframeReferer,
                                        quality = getQualityFromName(normalizedQuality),
                                        isM3u8 = href.contains(".m3u8"),
                                        headers = streamHeaders
                                    )
                                )
                                count++
                            }
                        }
                    }
                }
            }
        }

        // Step 3: Direct iframe in page HTML fallback
        if (count == 0) {
            val directIframe = doc?.selectFirst("iframe[src*='daftsex'], iframe[src*='ibhan2.top'], iframe[src*='/iframe/']")?.attr("src")
            if (!directIframe.isNullOrBlank()) {
                val convertPath = directIframe.replace("/iframe/v2/", "/iframe/convert/v2/")
                val playerDomain = if (convertPath.startsWith("http")) convertPath else "https://daftsex-biz.ibhan2.top$convertPath"
                val playerHtml = runCatching {
                    app.get(playerDomain, headers = mapOf("Referer" to "$mainUrl/", "referer" to "$mainUrl/")).text
                }.getOrNull()

                if (!playerHtml.isNullOrBlank()) {
                    val qualityMatches = Regex("""html:\s*['"]([^'"]+)['"],\s*url:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).findAll(playerHtml)
                    for (qEntry in qualityMatches) {
                        val qLabel = qEntry.groupValues[1].trim()
                        val qUrl = qEntry.groupValues[2].trim()
                        if (qUrl.isBlank()) continue

                        val normalizedQuality = when {
                            qLabel.contains("4k", ignoreCase = true) || qLabel.contains("2160") -> "2160p"
                            qLabel.contains("1080") -> "1080p"
                            qLabel.contains("720") -> "720p"
                            qLabel.contains("480") -> "480p"
                            qLabel.contains("360") -> "360p"
                            else -> qLabel
                        }

                        callback(
                            ExtractorLink(
                                source = name,
                                name = "$name $qLabel MP4",
                                url = qUrl,
                                referer = iframeReferer,
                                quality = getQualityFromName(normalizedQuality),
                                isM3u8 = qUrl.contains(".m3u8"),
                                headers = streamHeaders
                            )
                        )
                        count++
                    }
                }
            }
        }

        return count > 0
    }

    private suspend fun fetchPornPicsChannelPoster(channelSlug: String): String? {
        val cleanSlug = channelSlug.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (cleanSlug.isBlank()) return null
        return runCatching {
            val doc = app.get("$pornpicsUrl/channels/$cleanSlug/", headers = pornpicsHeaders).document
            // 1. Direct official channel logo avatar from entity-card-avatar
            val avatarEl = doc.selectFirst("div.entity-card-avatar img, .entity-card-avatar img, img[src*='hfma.pornpics.de'], img[data-src*='hfma.pornpics.de'], .channel-avatar img, .channel-logo img")
            val avatarRaw = avatarEl?.attr("src")?.ifBlank { null } ?: avatarEl?.attr("data-src")?.ifBlank { null }
            if (!avatarRaw.isNullOrBlank() && !avatarRaw.endsWith(".svg")) {
                return@runCatching fixUrl(avatarRaw, pornpicsUrl)
            }

            // 2. Channel gallery cover image fallback
            val imgEl = doc.select("li.thumb img, div.thumb-holder img, img.thumb_image, img[data-src*='cdni.pornpics.de'], img[src*='cdni.pornpics.de']")
                .firstOrNull { el ->
                    val s = el.attr("data-src").ifBlank { el.attr("src") }
                    s.isNotBlank() && !s.endsWith(".svg") && !s.contains("logo")
                }
            val raw = imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
            if (raw != null && !raw.endsWith(".svg") && !raw.contains("logo")) {
                fixUrlNull(raw, pornpicsUrl)
            } else null
        }.getOrNull()
    }

    // 5. HELPER CARD PARSERS
    private fun parseMovieCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/movie/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val title = linkEl.selectFirst("div.video-title, .video-title, .title, h1, h2, h3")?.text()?.trim()
            ?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("div.video-title, .video-title")?.text()?.trim()
            ?: return null

        val poster = extractImg(element) ?: extractImg(linkEl)

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseActorCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/video/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#") return null

        val name = linkEl.selectFirst("div.video-title, .video-title, .title, h1, h2, h3")?.text()?.trim()
            ?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("div.video-title, .video-title")?.text()?.trim()
            ?: return null

        val poster = extractImg(element) ?: extractImg(linkEl)

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

        val poster = extractImg(element.selectFirst("img")) ?: extractImg(element)

        return newTvSeriesSearchResponse(name, "$mainUrl/video/$slug", TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster, pornpicsUrl)
            this.posterHeaders = pornpicsHeaders
        }
    }

    private fun extractImg(element: Element?): String? {
        if (element == null) return null

        val target = if (element.hasAttr("data-src") || element.hasAttr("data-original") || element.hasAttr("data-thumb") || element.hasAttr("src")) {
            element
        } else {
            element.selectFirst("div.video-thumb, div[data-src], div[data-original], div[data-thumb], div.thumb, img, [style*='background']") ?: element
        }

        var raw = target.attr("data-src").ifBlank { null }
            ?: target.attr("data-original").ifBlank { null }
            ?: target.attr("data-thumb").ifBlank { null }
            ?: target.attr("data-lazy-src").ifBlank { null }
            ?: target.attr("data-image").ifBlank { null }
            ?: target.attr("content").ifBlank { null }
            ?: target.attr("src").ifBlank { null }

        if (raw.isNullOrBlank()) {
            val style = target.attr("style")
            val styleMatch = Regex("""url\(['"]?([^'"\)]+)['"]?\)""").find(style)
            raw = styleMatch?.groupValues?.get(1)
        }

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