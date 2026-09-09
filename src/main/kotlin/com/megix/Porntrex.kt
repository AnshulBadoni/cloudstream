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
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "most-popular/daily/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=video_viewed_today&from4=" to "Most popular today",
        "top-rated/daily/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&sort_by=rating_today&from4=" to "Top rated today",
        "models/?mode=async&function=get_block&block_id=list_models_models_list_items&sort_by=model_viewed&from_models=" to "Models & Stars",
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
        } else if (request.data == "models") {
            if (page <= 1) "$mainUrl/models/" else "$mainUrl/models/?page=$page"
        } else {
            if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/$page/"
        }

        val document = app.get(url).document

        val items = if (request.data.contains("list_models") || request.data == "models") {
            document.select(".list-models .item, #list_models_models_list_items .item, .item:has(a[href*='/models/'])").mapNotNull { element ->
                val title = element.selectFirst("strong.title, .title, a")?.text()?.trim() ?: return@mapNotNull null
                val href = fixUrl(element.selectFirst("a[href*='/models/'], a[href*='/pornstars/'], a")?.attr("href") ?: return@mapNotNull null)
                val poster = fixUrlNull(element.selectFirst("img.thumb, img")?.attr("data-src")?.ifBlank { null }
                    ?: element.selectFirst("img.thumb, img")?.attr("src"))

                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = poster
                    this.posterHeaders = mapOf("referer" to "$mainUrl/")
                }
            }
        } else {
            document.select("div.video-list div.video-item, .list-videos .item, #list_videos_common_videos_list_items .item, .video-preview-screen, .item").mapNotNull { element ->
                toSearchResult(element)
            }
        }

        val homePageList = HomePageList(request.name, items, isHorizontalImages = true)
        return newHomePageResponse(homePageList, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.trim().replace(" ", "-")}/?page=1"
        val document = app.get(url).document
        return document.select("div.video-list div.video-item, .list-videos .item, #list_videos_videos_list_search_result_items .item, .video-preview-screen").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    private fun extractFlashvar(key: String, text: String?): String? {
        if (text == null) return null
        val pattern = Regex("""['"]?$key['"]?\s*:\s*['"]([^'"]+)['"]""")
        return pattern.find(text)?.groupValues?.getOrNull(1)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // 1. Model / Performer Profile Page
        if (url.contains("/models/") || url.contains("/pornstars/")) {
            val name = document.selectFirst(".profile-model-info h1, .profile-model-info .name h1, h1.title, h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
                ?: url.trimEnd('/').substringAfterLast('/').replace("-", " ")

            val poster = fixUrlNull(document.selectFirst(".profile-model-info .img-holder img, .profile-model-info img, .img-holder img")?.attr("data-src")
                ?: document.selectFirst(".profile-model-info .img-holder img, .profile-model-info img, .img-holder img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content"))

            val bio = document.selectFirst(".profile-model-info .description-block, .profile-model-info .description, .model-description, .description-block")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

            val modelVideos = document.select("div.video-list div.video-item, .list-videos .item, .video-preview-screen, .video-item").mapNotNull { element ->
                toSearchResult(element)
            }

            return newMovieLoadResponse(name, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("referer" to "$mainUrl/")
                this.plot = bio ?: "Performer profile with ${modelVideos.size} videos."
                this.actors = listOf(ActorData(Actor(name, poster), roleString = "Performer", voiceActor = null))
                this.recommendations = modelVideos.ifEmpty { null }
            }
        }

        // 2. Video Details Page
        val scriptText = document.selectFirst("script:containsData(var flashvars)")?.data()

        val title = extractFlashvar("video_title", scriptText)
            ?: document.selectFirst("h1.title, h1, .headline h1, .video-details h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
            ?: "Video"

        val poster = fixUrlNull(extractFlashvar("preview_url", scriptText)
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("a.thumb img.cover, #player-holder video[poster], video[poster]")?.attr("poster")
            ?: document.selectFirst("a.thumb img.cover")?.attr("src"))

        val description = document.selectFirst(".videodesc .items-holder em.des-link, .videodesc .des-link, .videodesc .items-holder, .videodesc, .description-block, .video-details")?.text()
            ?.replace(Regex("^Description:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val ratingText = document.selectFirst(".vote-percentage, .rating")?.text()?.trim()
        val rating = ratingText?.filter { it.isDigit() }?.toIntOrNull()

        val durationText = document.selectFirst(".info-block .item span:has(i.fa-clock-o) em.badge, .info-block i.fa-clock-o + em, .durations, .duration")?.text()?.trim()
        val duration = durationText?.filter { it.isDigit() }?.toIntOrNull()

        val jsonTags = extractFlashvar("video_tags", scriptText)?.split(", ")?.map { it.replace("-", "").trim() }?.filter { it.isNotBlank() }
        val htmlTags = document.select("div.video-tags a, .block-details a[href*='/categories/'], .block-details a[href*='/tags/'], .item-categories a, .item-tags a, .tags a")
            .mapNotNull { it.text().trim().ifBlank { null } }
        val tags = (jsonTags.orEmpty() + htmlTags).distinct().ifEmpty { null }

        val actors = document.select(".block-details a[href*='/models/'], .block-details a[href*='/pornstars/'], .item-models a, a[href*='/models/'], a[href*='/pornstars/']")
            .mapNotNull { element ->
                val actorName = element.text().trim()
                if (actorName.isBlank()) null
                else ActorData(Actor(actorName, null), roleString = "Performer", voiceActor = null)
            }.distinctBy { it.actor.name }.ifEmpty { null }

        val recommendations = document.select("div#list_videos_related_videos div.video-list div.video-item, div.video-list div.video-item, .list-videos .item")
            .mapNotNull { element -> toSearchResult(element) }
            .ifEmpty { null }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
            this.plot = description
            this.rating = rating
            this.duration = duration
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        val flashvarsMatch = Regex("""var\s+flashvars\s*=\s*\{([^}]+)\}""").find(response)
        val scriptContent = flashvarsMatch?.groupValues?.get(1) ?: response

        val extracted = mutableListOf<ExtractorLink>()

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

                val link = newExtractorLink(
                    source = name,
                    name = "$name $qualityLabel",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityValue
                    this.referer = "$mainUrl/"
                }
                callback(link)
                extracted.add(link)
            }
        }

        return extracted.isNotEmpty()
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("p.inf a, strong.title, a.title, a[title], .title")?.text()?.trim()
            ?: element.selectFirst("a[title]")?.attr("title")?.trim() ?: return null
        val href = fixUrl(element.selectFirst("p.inf a, a[href*='/videos/'], a[href*='/video/'], a.thumb, a")?.attr("href") ?: return null)
        val poster = fixUrlNull(element.selectFirst("img.cover, img.thumb, img")?.attr("data-src")?.ifBlank { null }
            ?: element.selectFirst("img.cover, img.thumb, img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }
}


