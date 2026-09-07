package com.megix

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element

class Porntrex : MainAPI() {
    override var mainUrl = "https://www.porntrex.com"
    override var name = "Porntrex"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    // Simplified to native KVS URLs to fix the sorting issue
    override val mainPage = mainPageOf(
            "latest-updates" to "Latest Videos",
            "most-popular/daily" to "Most popular daily",
            "top-rated/daily" to "Top rated daily",
            "most-popular/weekly" to "Most popular weekly",
            "top-rated/weekly" to "Top rated weekly",
            "most-popular/monthly" to "Most popular monthly",
            "top-rated/monthly" to "Top rated monthly",
            "most-popular" to "Most popular all time",
            "top-rated" to "Top rated all time",
            "models" to "Models"
    )

    override suspend fun getMainPage(
            page: Int,
            request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}/"
        } else {
            "$mainUrl/${request.data}/${page}/"
        }
        
        val document = app.get(url).document
        
        val home = if (request.name == "Models") {
            document.select("a[href^=$mainUrl/models/], a[href^=/models/]").mapNotNull {
                val href = fixUrl(it.attr("href"))
                // Match actual model profile, skip sorting/pagination URLs
                if (href.matches(Regex(".*/models/[^/]+/?$"))) {
                    val img = it.selectFirst("img") ?: return@mapNotNull null
                    val title = img.attr("alt").ifEmpty { it.text() }.ifEmpty { href.trimEnd('/').substringAfterLast("/").replace("-", " ") }
                    if (title.isBlank()) return@mapNotNull null
                    val posterUrl = fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
                    
                    newTvSeriesSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = posterUrl
                        this.posterHeaders = mapOf(Pair("referer", "${mainUrl}/"))
                    }
                } else null
            }.distinctBy { it.url } // Prevent duplicates found in lists
        } else {
            document.select("div.video-list div.video-item").mapNotNull {
                it.toSearchResult()
            }
        }

        return newHomePageResponse(
                list = HomePageList(
                        name = request.name,
                        list = home,
                        isHorizontalImages = request.name != "Models"
                ),
                hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("p.inf a")?.text() ?: return null
        val href = fixUrl(this.selectFirst("p.inf a")!!.attr("href"))
        val poster = this.selectFirst("a.thumb img.cover")
        val posterUrl = fixUrlNull(poster?.attr("data-src")?.ifEmpty { poster.attr("src") })
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf(Pair("referer", "${mainUrl}/"))
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (page == 1) {
            "$mainUrl/search/${query.replace(" ", "-")}/"
        } else {
            "$mainUrl/search/${query.replace(" ", "-")}/$page/"
        }
        val document = app.get(url).document
        val results = document.select("div.video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Treat Models as a TV Series containing episodes (videos)
        if (url.contains("/models/")) {
            val title = document.selectFirst("h1")?.text() ?: "Model Profile"
            val poster = fixUrlNull(document.selectFirst("div.avatar img")?.attr("data-src")?.ifEmpty { document.selectFirst("div.avatar img")?.attr("src") } ?: "")
            
            val episodes = document.select("div.video-list div.video-item").mapNotNull {
                val epTitle = it.selectFirst("p.inf a")?.text() ?: return@mapNotNull null
                val epHref = fixUrl(it.selectFirst("p.inf a")!!.attr("href"))
                val epPoster = it.selectFirst("a.thumb img.cover")?.let { img ->
                    fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
                }
                
                newEpisode(epHref) {
                    this.name = epTitle
                    this.posterUrl = epPoster
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mapOf(Pair("referer", "${mainUrl}/"))
            }
        }

        // Standard Video Load
        val scriptContent = document.selectXpath("//script[contains(text(),'var flashvars')]").first()?.data()
        val jsonString = scriptContent?.substringAfter("var flashvars = ")
                ?.substringBefore("var player_obj")
                ?.substringBeforeLast(";")?.trim() ?: "{}"
                
        val jsonObject = try { JSONObject(jsonString) } catch(e: Exception) { JSONObject() }

        val title = jsonObject.optString("video_title").ifEmpty { document.selectFirst("h1")?.text() ?: "" }
        val poster = fixUrlNull(jsonObject.optString("preview_url"))
        val tags = jsonObject.optString("video_tags").split(", ").map { it.replace("-", "") }.filter { it.isNotBlank() && it.toIntOrNull() == null }
        
        val recommendations = document.select("div#list_videos_related_videos div.video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(Pair("referer", "${mainUrl}/"))
            this.plot = title
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val scriptContent = document.selectXpath("//script[contains(text(),'var flashvars')]").first()?.data()
        val jsonString = scriptContent?.substringAfter("var flashvars = ")
                ?.substringBefore("var player_obj")
                ?.substringBeforeLast(";")?.trim() ?: "{}"

        val jsonObject = try { JSONObject(jsonString) } catch(e: Exception) { JSONObject() }

        val extlinkList = mutableListOf<ExtractorLink>()
        for (i in 0 until 7) {
            val url: String
            val quality: String
            if (i == 0) {
                url = jsonObject.optString("video_url")
                quality = jsonObject.optString("video_url_text")
            } else if (i == 1) {
                url = jsonObject.optString("video_alt_url")
                quality = jsonObject.optString("video_alt_url_text")
            } else {
                url = jsonObject.optString("video_alt_url$i")
                quality = jsonObject.optString("video_alt_url${i}_text")
            }
            
            if (url.isEmpty()) continue
            
            extlinkList.add(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixUrl(url)
                ) {
                    this.referer = data
                    this.quality = Regex("(\\d+.)").find(quality)?.groupValues?.get(1)
                        ?.let { getQualityFromName(it) } ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                }
            )
        }
        extlinkList.forEach(callback)
        return extlinkList.isNotEmpty()
    }
}
