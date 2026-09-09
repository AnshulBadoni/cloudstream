package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Porntrex : MainAPI() {
    override var mainUrl = "https://www.porntrex.com"
    override var name = "PornTrex"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates" to "Latest Videos",
        "most-popular/daily/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=video_viewed_today&from4=" to "Most popular daily",
        "top-rated/daily/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=rating_today&from4=" to "Top rated daily",
        "models" to "Models",
        "most-popular/weekly/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=video_viewed_week&from4=" to "Most popular weekly",
        "top-rated/weekly/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=rating_week&from4=" to "Top rated weekly",
        "most-popular/monthly/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=video_viewed_month&from4=" to "Most popular monthly",
        "top-rated/monthly/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=rating_month&from4=" to "Top rated monthly",
        "most-popular/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=video_viewed&from4=" to "Most popular all time",
        "top-rated/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=rating&from4=" to "Top rated all time"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("mode=async")) {
            "$mainUrl/${request.data}$page"
        } else if (request.data.startsWith("models")) {
            if (page <= 1) "$mainUrl/models/" else "$mainUrl/models/?from_models=" + (if (page < 10) "0$page" else "$page")
        } else {
            if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/$page/"
        }

        val document = app.get(url).document

        val items = if (request.data.startsWith("models")) {
            document.select("div.list-models div.item, #list_models_models_list_items .item, .list-models .item, .item:has(a[href*='/models/'])").mapNotNull { element ->
                toModelSearchResult(element)
            }
        } else {
            document.select("div.video-list div.video-item, .list-videos .item, #list_videos_common_videos_list_items .item, .item").mapNotNull { element ->
                toSearchResult(element)
            }
        }

        val homePageList = HomePageList(request.name, items, isHorizontalImages = true)
        return newHomePageResponse(homePageList, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(" ", "-")
        val videoSearchUrl = "$mainUrl/search/$cleanQuery/?page=1"
        val modelSearchUrl = "$mainUrl/models/search/$cleanQuery/"

        val results = mutableListOf<SearchResponse>()

        // 1. Search matching models first
        runCatching {
            val modelDoc = app.get(modelSearchUrl).document
            val models = modelDoc.select("div.list-models div.item, #list_models_models_list_items .item, .list-models .item, .item:has(a[href*='/models/'])")
                .mapNotNull { toModelSearchResult(it) }
            results.addAll(models)
        }

        // 2. Search videos
        runCatching {
            val videoDoc = app.get(videoSearchUrl).document
            val videos = videoDoc.select("div.video-list div.video-item, .list-videos .item, #list_videos_videos_list_search_result_items .item, div.video-preview-screen, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])")
                .mapNotNull { toSearchResult(it) }
            results.addAll(videos)
        }

        return results.distinctBy { it.url }
    }

    private fun extractFlashvar(key: String, text: String?): String? {
        if (text == null) return null
        val pattern = Regex("""['"]?$key['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.groupValues?.getOrNull(1)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // 1. Model / Performer Profile Page - Treat as TV Series
        if (url.contains("/models/") || url.contains("/pornstars/")) {
            val name = document.selectFirst("h1, .profile-model-info h1, .profile-model-info .name h1, h1.title")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
                ?: url.trimEnd('/').substringAfterLast('/').replace("-", " ")

            val poster = fixUrlNull(
                document.selectFirst(".profile-model-info img, .img-holder img, img.cover, img.thumb")?.attr("data-src")?.ifBlank { null }
                    ?: document.selectFirst(".profile-model-info img, .img-holder img, img.cover, img.thumb")?.attr("src")
                    ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            )

            val bio = document.selectFirst(".description-block, .profile-model-info .description-block, .profile-model-info .description, .model-description, .videodesc")?.text()
                ?.replace(Regex("^Description:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

            // Find all video items on the performer's page
            val videoElements = document.select(
                "#list_videos_model_videos_items .item, #list_videos_common_videos_list_items .item, .list-videos .item, div.video-list div.video-item, div.video-preview-screen, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])"
            )

            val episodes = videoElements.mapIndexedNotNull { index, element ->
                toEpisodeResult(element, index + 1)
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(name, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("referer" to "$mainUrl/")
                this.plot = bio ?: "Complete collection of $name's videos (${episodes.size}+ videos)"
            }
        }

        // 2. Video Details Page
        val scriptText = document.selectFirst("script:containsData(var flashvars)")?.data()

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

        // Extract Actors / Cast list
        val actorsList = document.select(".block-details a[href*='/models/']:not(.js-open-suggest), .block-details a[href*='/pornstars/']:not(.js-open-suggest), .item-models a, a[href*='/models/']:not(.js-open-suggest)")
            .mapNotNull {
                val cleanName = it.text().replace(Regex("""^\+\s*\|\s*Suggest""", RegexOption.IGNORE_CASE), "").trim()
                if (cleanName.isNotBlank() && cleanName.length > 1) {
                    val actorImg = it.selectFirst("img")?.attr("data-src")?.ifBlank { null }
                        ?: it.selectFirst("img")?.attr("src")
                    ActorData(
                        actor = Actor(cleanName, fixUrlNull(actorImg)),
                        roleString = "Model"
                    )
                } else null
            }.distinctBy { it.actor.name }

        val fullPlot = if (actorsList.isNotEmpty()) {
            "Starring: " + actorsList.joinToString(", ") { it.actor.name } + if (!description.isNullOrBlank()) "\n\n$description" else ""
        } else {
            description
        }

        val jsonTags = extractFlashvar("video_tags", scriptText)?.split(", ")?.map { it.replace("-", "").trim() }?.filter { it.isNotBlank() }
        val htmlTags = document.select("div.video-tags a, .block-details a[href*='/categories/']:not(.js-open-suggest), .block-details a[href*='/tags/']:not(.js-open-suggest), .item-categories a, .item-tags a, .tags a")
            .mapNotNull { it.text().trim().ifBlank { null } }
        val tags = (actorsList.map { it.actor.name } + jsonTags.orEmpty() + htmlTags).distinct().ifEmpty { null }

        val recommendations = document.select("div#list_videos_related_videos div.video-list div.video-item, div.video-list div.video-item, .list-videos .item, #list_videos_related_videos .item")
            .mapNotNull { element -> toSearchResult(element) }
            .ifEmpty { null }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
            this.plot = fullPlot
            this.tags = tags
            this.recommendations = recommendations
            this.actors = actorsList.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        
        // Multi-line safe flashvars extraction
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
                        type = ExtractorLinkType.VIDEO
                    )
                )
                count++
            }
        }

        return count > 0
    }

    private fun getBestPoster(element: Element): String? {
        val rawUrl = element.selectFirst("img.cover, img.thumb, .img-holder img, img")?.attr("data-src")?.ifBlank { null }
            ?: element.selectFirst("img.cover, img.thumb, .img-holder img, img")?.attr("data-original")?.ifBlank { null }
            ?: element.selectFirst("img.cover, img.thumb, .img-holder img, img")?.attr("src")
            ?: return null

        var fixed = fixUrlNull(rawUrl) ?: return null
        if (fixed.endsWith("/preview.jpg")) {
            fixed = fixed.replace("/preview.jpg", "/preview_big.jpg")
        }
        return fixed
    }

    private fun toModelSearchResult(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/models/'], a[href*='/pornstars/']") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        val title = element.selectFirst("strong.title, .title, p.inf a, a")?.text()?.trim() ?: return null
        val poster = getBestPoster(element)

        return newTvSeriesSearchResponse(title, href, TvType.NSFW) {
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

        return newEpisode(href) {
            this.name = title
            this.posterUrl = poster
            this.description = duration
            this.episode = episodeNum
            this.season = 1
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("p.inf a, a[href*='/video/'], a[href*='/videos/'], a.thumb, a") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (!href.contains("/video/") && !href.contains("/videos/")) return null
        val title = element.selectFirst("strong.title a, .title a, p.inf a, strong.title, .title")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null } ?: return null
        val poster = getBestPoster(element)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }
}