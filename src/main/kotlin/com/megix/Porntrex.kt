package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.custom.TrailerHelper
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

class Porntrex : MainAPI() {
    override var mainUrl = "https://www.porntrex.com"
    override var name = "PornTrex"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries, TvType.Movie)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "most-popular/daily/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=video_viewed_today&from4=" to "Trending",
        "actors" to "Actors",
        "latest-updates" to "Trending",
        "search/vixen/" to "Vixen",
        "search/tushy/" to "Tushy",
        "search/blacked/" to "Blacked"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "actors" || request.name.equals("actors", ignoreCase = true) || request.name.equals("actor", ignoreCase = true)) {
            val ppActors = TrailerHelper.fetchPornPicsTrendingActors(page, mainUrl, "models")
            val list = if (ppActors.isNotEmpty()) {
                ppActors
            } else {
                val modelUrl = if (page <= 1) "$mainUrl/models/" else "$mainUrl/models/?from_models=" + (if (page < 10) "0$page" else "$page")
                val doc = runCatching { app.get(modelUrl, headers = defaultHeaders).document }.getOrNull()
                doc?.select("div.list-models div.item, #list_models_models_list_items .item, .list-models .item, .item:has(a[href*='/models/']), .item:has(a[href*='/model/'])")?.mapNotNull { element ->
                    toModelSearchResult(element)
                } ?: emptyList()
            }
            return newHomePageResponse(
                HomePageList(request.name, list, isHorizontalImages = false),
                hasNext = list.isNotEmpty()
            )
        }

        val url = if (request.data.contains("mode=async")) {
            "$mainUrl/${request.data}$page"
        } else if (request.data.startsWith("search/")) {
            val clean = request.data.trimEnd('/')
            if (page <= 1) "$mainUrl/$clean/" else "$mainUrl/$clean/$page/"
        } else {
            if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/$page/"
        }

        val document = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
        val items = document?.select("div.video-list div.video-item, .list-videos .item, #list_videos_common_videos_list_items .item, .item")?.mapNotNull { element ->
            toSearchResult(element)
        } ?: emptyList()

        val homePageList = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = true)
        return newHomePageResponse(homePageList, hasNext = items.isNotEmpty())
    }

    companion object {
        var searchPages: Int = 2
        var modelPages: Int = 3
        val avatarCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(" ", "-")
        val modelDirectUrl = "$mainUrl/models/$cleanQuery/"
        val modelSearchUrl = "$mainUrl/models/search/$cleanQuery/"

        val results = mutableListOf<SearchResponse>()
        val queryWords = query.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }

        // 1a. Check direct performer profile
        runCatching {
            val directDoc = app.get(modelDirectUrl, headers = defaultHeaders).document
            val h1 = directDoc.selectFirst(".profile-model-info h1, .profile-model h1, .profile-model-info .name h1, h1")?.text()?.trim()
            if (!h1.isNullOrBlank() && queryWords.all { word -> h1.contains(word, ignoreCase = true) }) {
                val posterEl = directDoc.selectFirst(".profile-model-info .img-holder img, .profile-model .img-holder img, .img-holder img, .profile-model img:not(.cover-img), .profile-model-info img")
                val rawPoster = posterEl?.attr("data-src")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
                    ?: posterEl?.attr("src")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
                    ?: directDoc.selectFirst(".profile-model img.cover-img")?.attr("data-src")
                    ?: directDoc.selectFirst("meta[property='og:image']")?.attr("content")
                val poster = fixUrlNull(rawPoster)
                val modelRes = newTvSeriesSearchResponse(h1, modelDirectUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.posterHeaders = mapOf("referer" to "$mainUrl/")
                }
                results.add(modelRes)
            }
        }

        // 1b. Search models directory
        runCatching {
            val modelDoc = app.get(modelSearchUrl, headers = defaultHeaders).document
            val models = modelDoc.select("div.list-models div.item, #list_models_models_list_items .item, .list-models .item, .item:has(a[href*='/models/']), .item:has(a[href*='/model/'])")
                .mapNotNull { toModelSearchResult(it) }

            val matchingModels = models.filter { model ->
                val name = model.name
                queryWords.all { word ->
                    val wordPattern = Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE)
                    wordPattern.containsMatchIn(name) || name.contains(word, ignoreCase = true)
                }
            }.sortedWith(
                compareBy<SearchResponse> { model ->
                    val name = model.name.lowercase().trim()
                    val q = query.lowercase().trim()
                    when {
                        name == q -> 0
                        name.startsWith(q) -> 1
                        else -> 2
                    }
                }.thenBy { it.name.length }
            )

            results.addAll(matchingModels)
        }

        // 2. Search videos across configured number of pages
        runCatching {
            val maxPages = searchPages.coerceIn(1, 4)
            for (page in 1..maxPages) {
                runCatching {
                    val url = if (page <= 1) "$mainUrl/search/$cleanQuery/" else "$mainUrl/search/$cleanQuery/$page/"
                    val videoDoc = app.get(url, headers = defaultHeaders).document
                    val videoElements = videoDoc.select("div.video-list div.video-item, .list-videos .item, #list_videos_common_videos_list_items .item, .item")
                    val pageResults = videoElements.mapNotNull { toSearchResult(it) }
                    results.addAll(pageResults)
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        // 1. Model / Performer Collection Page
        if (url.contains("/models/") || url.contains("/pornstars/") || url.contains("/model/")) {
            val slug = url.trimEnd('/').substringAfterLast('/').lowercase().trim()
            val rawName = document.selectFirst(".profile-model-info h1, .profile-model h1, .profile-model-info .name h1, h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
                ?: slug.replace("-", " ").split(" ").filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            val name = rawName.replaceFirstChar { it.uppercase() }

            val ppPoster = TrailerHelper.fetchPornPicsActorAvatar(slug) ?: TrailerHelper.fetchPornPicsStudioLogo(slug)
            val posterEl = document.selectFirst(".profile-model-info .img-holder img, .profile-model .img-holder img, .img-holder img, .profile-model img:not(.cover-img), .profile-model-info img")
            val rawPoster = posterEl?.attr("data-src")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
                ?: posterEl?.attr("src")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
                ?: document.selectFirst(".profile-model img.cover-img")?.attr("data-src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            val poster = if (!ppPoster.isNullOrBlank()) ppPoster else fixUrlNull(rawPoster)

            val bio = document.selectFirst(".profile-model-info .des, .profile-model .des, .description-block, .profile-model-info .description")?.text()?.trim()

            if (!poster.isNullOrBlank()) {
                avatarCache[name.lowercase().trim()] = poster
                if (slug.isNotBlank()) {
                    avatarCache[slug] = poster
                }
            }

            val videoElements = document.select("div.video-list div.video-item, .list-videos .item, #list_videos_common_videos_list_items .item, .item").toMutableList()

            val maxPagesAvailable = document.selectFirst(".pagination-holder li.page-playlist, .pagination li.page-playlist")?.attr("data-max")?.toIntOrNull()
                ?: document.select(".pagination-holder li.page a, .pagination li.page a").mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull()
                ?: 1

            val targetPages = modelPages.coerceIn(1, 10).coerceAtMost(maxPagesAvailable)
            if (targetPages > 1) {
                val baseUrl = url.trimEnd('/')
                for (page in 2..targetPages) {
                    runCatching {
                        val pageUrl = "$baseUrl/$page/"
                        val pageDoc = app.get(pageUrl, headers = defaultHeaders).document
                        val extraElements = pageDoc.select("div.video-list div.video-item, .list-videos .item, #list_videos_common_videos_list_items .item, .item")
                        videoElements.addAll(extraElements)
                    }
                }
            }

            val episodes = videoElements.mapNotNull { element ->
                toEpisodeResult(element, 1)
            }.distinctBy { it.data }.mapIndexed { index, ep ->
                ep.copy(episode = index + 1)
            }

            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = if (ppPoster != null) TrailerHelper.pornpicsHeaders else defaultHeaders
                this.plot = if (!bio.isNullOrBlank()) bio else "Complete collection of $name's videos (${episodes.size} videos)"
                this.showStatus = ShowStatus.Completed
            }
        }

        // 2. Video Details Page
        val scriptText = document.selectFirst("script:containsData(var flashvars)")?.data()
            ?: document.selectFirst("script:containsData(flashvars)")?.data()

        val title = extractFlashvar("video_title", scriptText)
            ?: document.selectFirst("h1.title, h1, .headline h1, .video-details h1, p.title-video")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
            ?: "Video"

        val poster = fixUrlNull(
            extractFlashvar("preview_url", scriptText)
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("a.thumb img.cover, #player-holder video[poster], video[poster]")?.attr("poster")
                ?: document.selectFirst("a.thumb img.cover")?.attr("src")
        )

        val description = document.selectFirst(".videodesc .items-holder em.des-link, .videodesc .des-link, .videodesc .items-holder, .videodesc, .description-block, .video-details")?.text()
            ?.replace(Regex("^Description:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val modelElements = document.select(
            "#tab_video_info .item:has(span.title-item:matches((?i)model)) a[href*='/models/']:not(.js-open-suggest), .block-details .item:has(span.title-item:matches((?i)model)) a[href*='/models/']:not(.js-open-suggest), .item:has(span.title-item:matches((?i)model)) a[href*='/models/']:not(.js-open-suggest), #tab_video_info .items-holder a[href*='/models/']:not(.js-open-suggest), .block-details .items-holder a[href*='/models/']:not(.js-open-suggest)"
        )
        val modelEntries = modelElements.mapNotNull { el ->
            val rawName = el.text().replace(Regex("""^\+\s*\|\s*Suggest""", RegexOption.IGNORE_CASE), "").trim()
            val rawHref = el.attr("href")
            if (rawName.isNotBlank() && rawName.length > 1 &&
                !rawName.equals("models", ignoreCase = true) &&
                !rawName.equals("suggest", ignoreCase = true) &&
                !rawName.equals("all", ignoreCase = true)) {
                Pair(rawName, fixUrl(rawHref))
            } else null
        }.distinctBy { it.first.lowercase().trim() }

        val actorsData = coroutineScope {
            modelEntries.map { (actorName, modelUrl) ->
                async {
                    val key = actorName.lowercase().trim()
                    val slug = modelUrl.trimEnd('/').substringAfterLast('/').lowercase().trim()
                    var avatar = avatarCache[key] ?: avatarCache[slug]

                    if (avatar == null) {
                        avatar = TrailerHelper.fetchPornPicsActorAvatar(slug) ?: TrailerHelper.fetchPornPicsActorAvatar(key)
                    }

                    if (avatar == null && modelUrl.isNotBlank()) {
                        avatar = runCatching {
                            withTimeoutOrNull(2000L) {
                                val mDoc = app.get(modelUrl, headers = defaultHeaders).document
                                val posterEl = mDoc.selectFirst(".profile-model-info .img-holder img, .profile-model .img-holder img, .img-holder img, .profile-model img:not(.cover-img), .profile-model-info img")
                                val rawPoster = posterEl?.attr("data-src")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
                                    ?: posterEl?.attr("src")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
                                    ?: mDoc.selectFirst(".profile-model img.cover-img")?.attr("data-src")
                                    ?: mDoc.selectFirst("meta[property='og:image']")?.attr("content")
                                fixUrlNull(rawPoster)
                            }
                        }.getOrNull()

                        if (!avatar.isNullOrBlank()) {
                            avatarCache[key] = avatar
                            avatarCache[slug] = avatar
                        }
                    }

                    ActorData(
                        actor = Actor(actorName, avatar),
                        role = null,
                        roleString = "Performer",
                        voiceActor = null
                    )
                }
            }.awaitAll()
        }

        val actorsList = modelEntries.map { it.first }

        val durationText = document.selectFirst(".durations, .video-duration, span.duration, .time")?.text()?.trim()
        val durationMinutes = if (!durationText.isNullOrBlank()) {
            val parts = durationText.filter { it.isDigit() || it == ':' }.split(':')
            if (parts.size == 2) {
                val mins = parts[0].toIntOrNull() ?: 0
                val secs = parts[1].toIntOrNull() ?: 0
                mins + (if (secs >= 30) 1 else 0)
            } else if (parts.size == 3) {
                val hours = parts[0].toIntOrNull() ?: 0
                val mins = parts[1].toIntOrNull() ?: 0
                hours * 60 + mins
            } else {
                null
            }
        } else null

        val ratingText = document.selectFirst(".rating-container .voters, .rate, .rating, .vote-percent, .voters")?.text()
        val ratingPercent = Regex("""(\d{1,3})%""").find(ratingText.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d{1,3})""").find(ratingText.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

        val jsonTags = extractFlashvar("video_tags", scriptText)?.split(", ")?.map { it.replace("-", "").trim() }?.filter { it.isNotBlank() }
        val htmlTags = document.select("div.video-tags a, #tab_video_info .block-details a[href*='/categories/']:not(.js-open-suggest), #tab_video_info .block-details a[href*='/tags/']:not(.js-open-suggest), .item-categories a, .item-tags a, .tags a")
            .mapNotNull { it.text().trim().ifBlank { null } }
        val tags = (actorsList + jsonTags.orEmpty() + htmlTags).distinct().filter { it.length > 1 }.ifEmpty { null }

        val recommendations = document.select("div#list_videos_related_videos div.video-list div.video-item, div.video-list div.video-item, .list-videos .item, #list_videos_related_videos .item")
            .mapNotNull { element -> toSearchResult(element) }
            .ifEmpty { null }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
            this.plot = description
            this.tags = tags
            this.duration = durationMinutes
            this.rating = ratingPercent
            this.actors = actorsData
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (com.custom.TrailerHelper.handleTrailerStream(data, name, callback)) {
            return true
        }

        val response = app.get(data, headers = mapOf("referer" to "$mainUrl/")).text

        val flashvarsMatch = Regex("""var\s+flashvars\s*=\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL).find(response)
        val scriptContent = flashvarsMatch?.groupValues?.get(1) ?: response

        var count = 0

        val urlRegex = Regex("""(video_url|video_alt_url\d*)\s*:\s*['"]([^'"]+)['"]""")
        val textRegex = Regex("""(video_url_text|video_alt_url\d*_text)\s*:\s*['"]([^'"]+)['"]""")

        val qualityMap = mutableMapOf<String, String>()
        textRegex.findAll(scriptContent).forEach { match ->
            val key = match.groupValues[1].replace("_text", "")
            val quality = match.groupValues[2]
            qualityMap[key] = quality
        }

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
                        referer = "$mainUrl/",
                        quality = qualityValue,
                        isM3u8 = streamUrl.contains(".m3u8"),
                        headers = mapOf("referer" to "$mainUrl/")
                    )
                )
                count++
            }
        }

        return count > 0
    }

    private fun getBestPoster(element: Element): String? {
        val rawUrl = element.selectFirst(".img-holder img, div.img img, img.thumb, img.cover, img")?.attr("data-src")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
            ?: element.selectFirst(".img-holder img, div.img img, img.thumb, img.cover, img")?.attr("data-original")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
            ?: element.selectFirst(".img-holder img, div.img img, img.thumb, img.cover, img")?.attr("src")?.takeIf { !it.contains("data:image") && it.isNotBlank() }
            ?: return null

        var fixed = fixUrlNull(rawUrl) ?: return null
        if (fixed.endsWith("/preview.jpg")) {
            fixed = fixed.replace("/preview.jpg", "/preview_big.jpg")
        }
        return fixed
    }

    private fun toModelSearchResult(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/models/'], a[href*='/pornstars/'], a[href*='/model/']") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        val title = element.selectFirst("strong.title, .title, p.inf a, a")?.text()?.trim() ?: return null
        val poster = getBestPoster(element)

        if (!poster.isNullOrBlank()) {
            avatarCache[title.lowercase().trim()] = poster
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug.isNotBlank()) {
                avatarCache[slug.lowercase().trim()] = poster
            }
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    private fun toEpisodeResult(element: Element, episodeNum: Int): Episode? {
        val linkEl = element.selectFirst("p.inf a, a[href*='/video/'], a[href*='/videos/'], a.thumb, a") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (!href.contains("/video/") && !href.contains("/videos/")) return null
        val title = element.selectFirst("strong.title a, .title a, p.inf a, strong.title, .title")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: "Video $episodeNum"
        val poster = getBestPoster(element)
        val duration = element.selectFirst(".durations, .duration, .time, .video-duration, span.min")?.text()?.trim()

        val runTimeMinutes = if (!duration.isNullOrBlank()) {
            val parts = duration.filter { it.isDigit() || it == ':' }.split(':')
            if (parts.size == 2) {
                val mins = parts[0].toIntOrNull() ?: 0
                val secs = parts[1].toIntOrNull() ?: 0
                mins + (if (secs >= 30) 1 else 0)
            } else if (parts.size == 3) {
                val hours = parts[0].toIntOrNull() ?: 0
                val mins = parts[1].toIntOrNull() ?: 0
                hours * 60 + mins
            } else {
                null
            }
        } else null

        return Episode(
            data = href,
            name = title,
            season = 1,
            episode = episodeNum,
            posterUrl = poster,
            description = duration,
            runTime = runTimeMinutes
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("p.inf a, a[href*='/video/'], a[href*='/videos/'], a.thumb, a") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (!href.contains("/video/") && !href.contains("/videos/")) return null
        val title = element.selectFirst("strong.title a, .title a, p.inf a, strong.title, .title")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null } ?: return null
        val poster = getBestPoster(element)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    private fun extractFlashvar(key: String, script: String?): String? {
        if (script.isNullOrBlank()) return null
        val regex = Regex("""['"]?$key['"]?\s*:\s*['"]([^'"]+)['"]""")
        return regex.find(script)?.groupValues?.get(1)
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }
}