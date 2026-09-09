package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    override val mainPage = mainPageOf(
        "latest-updates" to "Latest Videos",
        "most-popular/daily/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=video_viewed_today&from4=" to "Most popular daily",
        "top-rated/daily/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=rating_today&from4=" to "Top rated daily",
        "models" to "Models & Stars"
    )

    companion object {
        var searchPages: Int = 2
        var modelPages: Int = 3
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data == "models") {
            if (page <= 1) "$mainUrl/models/" else "$mainUrl/models/$page/"
        } else if (request.data == "latest-updates") {
            if (page <= 1) "$mainUrl/latest-updates/" else "$mainUrl/latest-updates/$page/"
        } else {
            "$mainUrl/${request.data}$page"
        }

        val document = app.get(url, headers = mapOf("referer" to "$mainUrl/")).document

        val homeItems = if (request.data == "models") {
            document.select(
                "div.list-models div.item, #list_models_models_list_items .item, #list_models_common_models_list_items .item, .list-models .item, .item:has(a[href*='/models/'])"
            ).mapNotNull { element ->
                toModelSearchResult(element)
            }
        } else {
            document.select(
                "div.video-list div.video-item, div.video-preview-screen, #list_videos_common_videos_list_norm .item, #list_videos_latest_videos_list_norm .item, #list_videos_most_popular_videos_list_norm .item, #list_videos_top_rated_videos_list_norm .item, .list-videos .item, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])"
            ).mapNotNull { element ->
                toSearchResult(element)
            }
        }

        return newHomePageResponse(
            item = HomePageList(
                name = request.name,
                list = homeItems,
                isHorizontalImages = request.data != "models"
            ),
            hasNext = homeItems.isNotEmpty()
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.thumb, a[href*='/video/'], a[href*='/videos/'], a:has(img)") ?: return null
        val rawHref = linkElement.attr("href").ifBlank { return null }
        val href = fixUrl(rawHref)
        if (href.contains("/models/") || href.contains("/model/")) {
            return toModelSearchResult(element)
        }

        val title = element.selectFirst("span.title, a.title, strong.title, .title, a[title]")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: linkElement.attr("title").trim()

        if (title.isBlank()) return null

        val img = element.selectFirst("img.thumb, img.cover, img.preview, img")
        val rawPoster = img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-lazy")?.ifBlank { null }
            ?: img?.attr("src")?.ifBlank { null }
        val poster = fixUrlNull(rawPoster)

        val durationText = element.selectFirst("span.duration, .durations, .time, .duration")?.text()?.trim()
        val quality = if (element.selectFirst("span.is-hd, .hd, .badge-hd") != null) SearchQuality.Hdtv else null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
            this.quality = quality
            this.score = Score.from(100, 100)
        }
    }

    private fun toModelSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a[href*='/models/'], a[href*='/model/'], a.thumb, a") ?: return null
        val rawHref = linkElement.attr("href").ifBlank { return null }
        val href = fixUrl(rawHref)

        val name = element.selectFirst("span.title, a.title, strong.title, .title, a.name, .name, span.name")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: linkElement.attr("title").trim()

        if (name.isBlank() || name.equals("models", ignoreCase = true)) return null

        val img = element.selectFirst("img.thumb, img.cover, img.preview, img")
        val rawPoster = img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-lazy")?.ifBlank { null }
            ?: img?.attr("src")?.ifBlank { null }
        val poster = fixUrlNull(rawPoster)

        return newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    private fun toEpisodeResult(element: Element, episodeNum: Int): Episode? {
        val linkElement = element.selectFirst("a.thumb, a[href*='/video/'], a[href*='/videos/'], a:has(img)") ?: return null
        val rawHref = linkElement.attr("href").ifBlank { return null }
        val href = fixUrl(rawHref)
        if (href.contains("/models/") || href.contains("/model/")) return null

        val title = element.selectFirst("span.title, a.title, strong.title, .title, a[title]")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: linkElement.attr("title").trim()
            ?: "Video $episodeNum"

        val img = element.selectFirst("img.thumb, img.cover, img.preview, img")
        val rawPoster = img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-lazy")?.ifBlank { null }
            ?: img?.attr("src")?.ifBlank { null }
        val poster = fixUrlNull(rawPoster)

        val durationText = element.selectFirst("span.duration, .durations, .time, .duration")?.text()?.trim()
        val durationSeconds = if (!durationText.isNullOrBlank()) {
            val parts = durationText.filter { it.isDigit() || it == ':' }.split(':')
            if (parts.size == 2) {
                val mins = parts[0].toIntOrNull() ?: 0
                val secs = parts[1].toIntOrNull() ?: 0
                mins * 60 + secs
            } else if (parts.size == 3) {
                val hours = parts[0].toIntOrNull() ?: 0
                val mins = parts[1].toIntOrNull() ?: 0
                val secs = parts[2].toIntOrNull() ?: 0
                hours * 3600 + mins * 60 + secs
            } else {
                null
            }
        } else null

        return newEpisode(href) {
            this.name = title
            this.season = 1
            this.episode = episodeNum
            this.posterUrl = poster
            this.runTime = durationSeconds?.div(60)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val searchClean = query.trim().replace(" ", "-")

        val modelDeferred = async {
            runCatching {
                val modelUrl = "$mainUrl/models/$searchClean/"
                val modelDoc = app.get(modelUrl, headers = mapOf("referer" to "$mainUrl/")).document
                val modelItems = modelDoc.select("div.list-models div.item, #list_models_models_list_items .item, #list_models_common_models_list_items .item, .list-models .item, .item:has(a[href*='/models/'])")
                    .mapNotNull { toModelSearchResult(it) }

                if (modelItems.isEmpty()) {
                    val directName = modelDoc.selectFirst("h1.title, h1.headline, h1, .profile-model-info h1, .model-info h1")?.text()?.trim()
                    val directImg = modelDoc.selectFirst(".profile-model-info img, .avatar img, .model-avatar img, a.thumb img")?.attr("src")
                    if (!directName.isNullOrBlank() && (directName.contains(query, ignoreCase = true) || query.contains(directName, ignoreCase = true))) {
                        listOf(
                            newTvSeriesSearchResponse(directName, modelUrl, TvType.TvSeries) {
                                this.posterUrl = fixUrlNull(directImg)
                                this.posterHeaders = mapOf("referer" to "$mainUrl/")
                            }
                        )
                    } else {
                        emptyList()
                    }
                } else {
                    modelItems
                }
            }.getOrDefault(emptyList())
        }

        val videoDeferred = async {
            val maxPages = searchPages.coerceIn(1, 4)
            val pagesToFetch = (1..maxPages).toList()
            val videoResults = pagesToFetch.map { p ->
                async {
                    runCatching {
                        val videoUrl = if (p == 1) "$mainUrl/search/$searchClean/" else "$mainUrl/search/$searchClean/$p/"
                        val videoDoc = app.get(videoUrl, headers = mapOf("referer" to "$mainUrl/")).document
                        videoDoc.select("div.video-list div.video-item, div.video-preview-screen, #list_videos_videos_search_search_result_items .item, #list_videos_common_videos_list_items .item, .list-videos .item, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])")
                            .mapNotNull { toSearchResult(it) }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.url }
            videoResults
        }

        val modelRes = modelDeferred.await()
        val videoRes = videoDeferred.await()

        val queryLower = query.lowercase().trim()
        val sortedModels = modelRes.sortedWith(
            compareBy<SearchResponse> {
                val n = it.name.lowercase().trim()
                when {
                    n == queryLower -> 0
                    n.startsWith(queryLower) -> 1
                    n.contains(queryLower) -> 2
                    else -> 3
                }
            }.thenBy { it.name.length }
        )

        sortedModels + videoRes
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = mapOf(
                "referer" to "$mainUrl/",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
            )
        ).document

        // 1. Model / Performer Collection Page
        if (url.contains("/models/") || url.contains("/model/")) {
            val name = document.selectFirst("h1.title, h1.headline, h1, .profile-model-info h1, .model-info h1, meta[property='og:title']")?.text()
                ?.substringBefore("|")?.trim()
                ?: "Model Profile"

            val rawPoster = document.selectFirst(".profile-model-info img, .avatar img, .model-avatar img, a.thumb img.cover")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            val poster = fixUrlNull(rawPoster)

            val bio = document.selectFirst(".description-block, .profile-model-info .description-block, .profile-model-info .description, .model-description, .videodesc")?.text()
                ?.replace(Regex("^Description:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

            val videoElements = document.select(
                "div.video-list div.video-item, div.video-preview-screen, #list_videos_common_videos_list_norm .item, #list_videos_model_videos_items .item, #list_videos_common_videos_list_items .item, .list-videos .item, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])"
            ).toMutableList()

            val maxPagesAvailable = document.selectFirst(".pagination-holder li.page-playlist, .pagination li.page-playlist")?.attr("data-max")?.toIntOrNull()
                ?: document.select(".pagination-holder li.page a, .pagination li.page a").mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull()
                ?: 1

            val targetPages = modelPages.coerceIn(1, 10).coerceAtMost(maxPagesAvailable)
            if (targetPages > 1) {
                val baseUrl = url.trimEnd('/')
                for (page in 2..targetPages) {
                    runCatching {
                        val pageUrl = "$baseUrl/$page/"
                        val pageDoc = app.get(pageUrl, headers = mapOf("referer" to "$mainUrl/")).document
                        val extraElements = pageDoc.select(
                            "div.video-list div.video-item, div.video-preview-screen, #list_videos_common_videos_list_norm .item, #list_videos_model_videos_items .item, #list_videos_common_videos_list_items .item, .list-videos .item, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])"
                        )
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
                this.posterHeaders = mapOf("referer" to "$mainUrl/")
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

        val actorsList = document.select(
            "#tab_video_info .item:has(span.title-item:matches((?i)model)) a[href*='/models/']:not(.js-open-suggest), .block-details .item:has(span.title-item:matches((?i)model)) a[href*='/models/']:not(.js-open-suggest), .item:has(span.title-item:matches((?i)model)) a[href*='/models/']:not(.js-open-suggest), #tab_video_info .items-holder a[href*='/models/']:not(.js-open-suggest), .block-details .items-holder a[href*='/models/']:not(.js-open-suggest)"
        )
            .mapNotNull { it.text().replace(Regex("""^\+\s*\|\s*Suggest""", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.isNotBlank() && it.length > 1 && !it.equals("models", ignoreCase = true) && !it.equals("suggest", ignoreCase = true) && !it.equals("all", ignoreCase = true) }
            .distinct()

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

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
            this.plot = description
            this.tags = tags
            this.duration = durationMinutes
            this.rating = ratingPercent
            this.actors = actorsList.map {
                ActorData(
                    Actor(it, null),
                    roleString = "Performer",
                    voiceActor = null
                )
            }
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            data,
            headers = mapOf(
                "referer" to "$mainUrl/",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
            )
        ).text

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
            val videoUrl = match.groupValues[2]

            if (videoUrl.isNotBlank() && (videoUrl.startsWith("http://") || videoUrl.startsWith("https://"))) {
                val label = qualityMap[key] ?: key
                val qualityInt = Regex("""(\d{3,4})[pP]?""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value

                val isM3u8 = videoUrl.contains(".m3u8")

                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name $label",
                        url = videoUrl,
                        referer = "$mainUrl/",
                        quality = qualityInt,
                        isM3u8 = isM3u8
                    )
                )
                count++
            }
        }

        return count > 0
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