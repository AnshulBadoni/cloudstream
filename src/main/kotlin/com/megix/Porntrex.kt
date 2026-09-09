package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Porntrex : MainAPI() {
    override var mainUrl = "https://www.porntrex.com"
    override var name = "PornTrex"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries, TvType.Movie)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "confirmed=1; age_verified=1; kt_tplayer=1; kt_lang=en; kt_ips=1"
    )

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

        val document = app.get(url, headers = defaultHeaders).document

        val items = if (request.data.startsWith("models")) {
            document.select("div.list-models div.item, #list_models_models_list_items .item, .list-models .item, .item:has(a[href*='/models/']), .item:has(a[href*='/model/'])").mapNotNull { element ->
                toModelSearchResult(element)
            }
        } else {
            document.select("div.video-list div.video-item, .list-videos .item, #list_videos_common_videos_list_items .item, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])").mapNotNull { element ->
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

        val modelResults = mutableListOf<SearchResponse>()
        val videoResults = mutableListOf<SearchResponse>()

        val queryWords = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }

        // 1. Search matching models (whole word matching)
        runCatching {
            val modelDoc = app.get(modelSearchUrl, headers = defaultHeaders).document
            val rawModels = modelDoc.select("div.list-models div.item, #list_models_models_list_items .item, .list-models .item, .item:has(a[href*='/models/']), .item:has(a[href*='/model/'])")
                .mapNotNull { toModelSearchResult(it) }

            // Filter: Model name must match all query words on whole-word boundaries
            val filteredModels = rawModels.filter { model ->
                val modelName = model.name.lowercase()
                queryWords.all { word ->
                    Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(modelName)
                }
            }.sortedWith(
                compareByDescending<SearchResponse> {
                    it.name.equals(query.trim(), ignoreCase = true)
                }.thenByDescending {
                    it.name.startsWith(query.trim(), ignoreCase = true)
                }
            )

            modelResults.addAll(filteredModels)
        }

        // 2. Search videos
        runCatching {
            val videoDoc = app.get(videoSearchUrl, headers = defaultHeaders).document
            val videos = videoDoc.select("div.video-list div.video-item, .list-videos .item, #list_videos_videos_list_search_result_items .item, div.video-preview-screen, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])")
                .mapNotNull { toSearchResult(it) }
            videoResults.addAll(videos)
        }

        // Return Models at the top, followed by videos
        return (modelResults + videoResults).distinctBy { it.url }
    }

    private fun extractFlashvar(key: String, text: String?): String? {
        if (text == null) return null
        val pattern = Regex("""['"]?$key['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        // 1. Model / Performer Profile Page - Treat as TV Series
        if (url.contains("/models/") || url.contains("/pornstars/") || url.contains("/model/")) {
            val name = document.selectFirst("h1, .profile-model-info h1, .profile-model-info .name h1, h1.title, .headline h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
                ?: url.trimEnd('/').substringAfterLast('/').replace("-", " ")

            val poster = fixUrlNull(
                document.selectFirst(".profile-model-info img, .img-holder img, .model-avatar img, .avatar img, img.cover, img.thumb")?.attr("data-src")?.ifBlank { null }
                    ?: document.selectFirst(".profile-model-info img, .img-holder img, .model-avatar img, .avatar img, img.cover, img.thumb")?.attr("data-original")?.ifBlank { null }
                    ?: document.selectFirst(".profile-model-info img, .img-holder img, .model-avatar img, .avatar img, img.cover, img.thumb")?.attr("src")
                    ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            )

            val bio = document.selectFirst(".description-block, .profile-model-info .description-block, .profile-model-info .description, .model-description, .videodesc")?.text()
                ?.replace(Regex("^Description:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

            // Find all video items on the performer's page
            val videoElements = document.select(
                "#list_videos_models_videos_items .item, " +
                "#list_videos_model_videos_items .item, " +
                "#list_videos_common_videos_list_norm .item, " +
                "#list_videos_common_videos_list_items .item, " +
                ".list-videos .item, " +
                "div.video-list div.video-item, " +
                "div.video-preview-screen, " +
                ".item:has(a[href*='/video/']), " +
                ".item:has(a[href*='/videos/'])"
            )

            val episodes = videoElements.mapIndexedNotNull { index, element ->
                toEpisodeResult(element, index + 1)
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
                this.plot = if (!bio.isNullOrBlank()) bio else "Complete collection of $name's videos (${episodes.size} videos)"
            }
        }

        // 2. Video Details Page
        val htmlContent = document.html()

        val title = extractFlashvar("video_title", htmlContent)
            ?: document.selectFirst("h1.title, h1, .headline h1, .video-details h1, p.title-video")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
            ?: "Video"

        val poster = fixUrlNull(
            extractFlashvar("preview_url", htmlContent)
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("a.thumb img.cover, #player-holder video[poster], video[poster]")?.attr("poster")
                ?: document.selectFirst("a.thumb img.cover")?.attr("src")
        )

        val description = document.selectFirst(".videodesc .items-holder em.des-link, .videodesc .des-link, .videodesc .items-holder, .videodesc, .description-block, .video-details")?.text()
            ?.replace(Regex("^Description:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

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

        val jsonTags = extractFlashvar("video_tags", htmlContent)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val htmlTags = document.select("div.video-tags a, .block-details a[href*='/categories/']:not(.js-open-suggest), .block-details a[href*='/tags/']:not(.js-open-suggest), .item-categories a, .item-tags a, .tags a")
            .mapNotNull { it.text().trim().ifBlank { null } }
        val tags = (actorsList.map { it.actor.name } + jsonTags.orEmpty() + htmlTags).distinct().ifEmpty { null }

        val recommendations = document.select("div#list_videos_related_videos div.video-list div.video-item, div.video-list div.video-item, .list-videos .item, #list_videos_related_videos .item")
            .mapNotNull { element -> toSearchResult(element) }
            .ifEmpty { null }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
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
        val response = app.get(data, headers = defaultHeaders).text
        var count = 0

        // Extract quality labels (e.g. video_url_text: '1080p', video_alt_url_text: '720p', etc.)
        val textRegex = Regex("""['"]?(video_url|video_alt_url\d*)_text['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val qualityMap = mutableMapOf<String, String>()
        textRegex.findAll(response).forEach { match ->
            val key = match.groupValues[1]
            val quality = match.groupValues[2].trim()
            qualityMap[key] = quality
        }

        // Extract direct video stream links
        val urlRegex = Regex("""['"]?(video_url|video_alt_url\d*)['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        urlRegex.findAll(response).forEach { match ->
            val key = match.groupValues[1]
            var streamUrl = match.groupValues[2]
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .trim()

            if (streamUrl.startsWith("//")) {
                streamUrl = "https:$streamUrl"
            }

            if (streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) {
                val qualityLabel = qualityMap[key] ?: Regex("""(\d{3,4}p)""").find(streamUrl)?.value ?: "720p"
                val qualityValue = getQualityFromName(qualityLabel)

                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name $qualityLabel",
                        url = streamUrl,
                        referer = "$mainUrl/",
                        quality = qualityValue,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "$mainUrl/"
                        )
                    )
                )
                count++
            }
        }

        // Fallback for HTML5 video/source elements
        if (count == 0) {
            val doc = org.jsoup.Jsoup.parse(response)
            doc.select("video source, #player-holder video source").forEach { source ->
                val src = fixUrlNull(source.attr("src")) ?: return@forEach
                val res = source.attr("res").ifBlank { source.attr("title") }.ifBlank { "720p" }
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name $res",
                        url = src,
                        referer = "$mainUrl/",
                        quality = getQualityFromName(res),
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "$mainUrl/"
                        )
                    )
                )
                count++
            }
        }

        return count > 0
    }

    private fun getBestPoster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        val rawUrl = img.attr("data-src").ifBlank { null }
            ?: img.attr("data-original").ifBlank { null }
            ?: img.attr("data-lazy").ifBlank { null }
            ?: img.attr("src").ifBlank { null }
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
        val title = element.selectFirst("strong.title, .title, p.inf a, .name, a")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: return null
        val poster = getBestPoster(element)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
        }
    }

    private fun toEpisodeResult(element: Element, episodeNum: Int): Episode? {
        // Target video links directly, ignoring channel/model sub-tags
        val linkEl = element.selectFirst("a[href*='/video/'], a[href*='/videos/']")
            ?: element.selectFirst("a.thumb, strong.title a, .title a")
            ?: return null

        val href = fixUrl(linkEl.attr("href"))
        if (!href.contains("/video/") && !href.contains("/videos/")) return null

        val title = element.selectFirst("strong.title a, .title a, strong.title, .title")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: "Video $episodeNum"

        val poster = getBestPoster(element)
        val duration = element.selectFirst(".durations, .duration, .time, .video-duration, span.min, .time-length")?.text()?.trim()

        return Episode(
            data = href,
            name = title,
            season = 1,
            episode = episodeNum,
            posterUrl = poster,
            description = duration
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/video/'], a[href*='/videos/'], a.thumb") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (!href.contains("/video/") && !href.contains("/videos/")) return null
        val title = element.selectFirst("strong.title a, .title a, strong.title, .title")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null
        val poster = getBestPoster(element)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
        }
    }
}
