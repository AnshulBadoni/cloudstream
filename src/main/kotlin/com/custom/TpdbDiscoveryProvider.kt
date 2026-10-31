package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

open class TpdbDiscoveryProvider : MainAPI() {

    override var mainUrl = "https://theporndb.net"
    override var name = "TPDB Discovery"
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override var lang = "en"
    override val isNsfw = true
    override val hasMainPage = true
    override val hasDownloadSupport = true

    var backendBaseUrl = "https://cloudstream-gl1n.onrender.com"

    companion object {
        private const val TPDB_API_KEY = "8ABvbgloweVLDeD3HBq6x9eHpL3lMJE8qEuBtdmb213d0c62"
        private val TPDB_HEADERS = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $TPDB_API_KEY"
        )
    }

    override val mainPage = listOf(
        MainPageData("Trending Scenes", "https://api.theporndb.net/scenes?sort=trending"),
        MainPageData("Latest Scenes", "https://api.theporndb.net/scenes?sort=date")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "${request.data}&page=$page"
        val resp = runCatching {
            app.get(url, headers = TPDB_HEADERS)
        }.getOrNull() ?: return null

        val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return null
        val dataArr = json.optJSONArray("data") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()

        for (i in 0 until dataArr.length()) {
            val scene = dataArr.optJSONObject(i) ?: continue
            val id = scene.optString("id")
            if (id.isBlank()) continue
            val title = scene.optString("title", "Untitled")
            val rawPoster = scene.optString("poster")
            val poster = if (rawPoster.isNotBlank()) rawPoster else scene.optJSONObject("background")?.optString("full")
            val date = scene.optString("date")
            val year = if (!date.isNullOrBlank()) date.take(4).toIntOrNull() else null

            val encTitle = URLEncoder.encode(title, "UTF-8")
            items.add(newMovieSearchResponse(title, "tpdb://movie/$id?title=$encTitle", TvType.Movie) {
                this.posterUrl = if (!poster.isNullOrBlank()) poster else null
                this.year = year
            })
        }

        return HomePageResponse(
            items = listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "https://api.theporndb.net/scenes?q=$encodedQuery"

        val resp = runCatching {
            app.get(searchUrl, headers = TPDB_HEADERS)
        }.getOrNull() ?: return emptyList()

        val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return emptyList()
        val dataArr = json.optJSONArray("data") ?: JSONArray()
        val results = mutableListOf<SearchResponse>()

        for (i in 0 until dataArr.length()) {
            val scene = dataArr.optJSONObject(i) ?: continue
            val id = scene.optString("id")
            if (id.isBlank()) continue
            val title = scene.optString("title", "Untitled")
            val rawPoster = scene.optString("poster")
            val poster = if (rawPoster.isNotBlank()) rawPoster else scene.optJSONObject("background")?.optString("full")
            val date = scene.optString("date")
            val year = if (!date.isNullOrBlank()) date.take(4).toIntOrNull() else null

            val encTitle = URLEncoder.encode(title, "UTF-8")
            results.add(newMovieSearchResponse(title, "tpdb://movie/$id?title=$encTitle", TvType.Movie) {
                this.posterUrl = if (!poster.isNullOrBlank()) poster else null
                this.year = year
            })
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.removePrefix("tpdb://movie/").trim()
        val tpdbId = cleanUrl.substringBefore("?").trim()
        val titleFromUrl = if (cleanUrl.contains("title=")) {
            runCatching { URLDecoder.decode(cleanUrl.substringAfter("title=").substringBefore("&"), "UTF-8") }.getOrNull()
        } else null

        val apiUrl = "https://api.theporndb.net/scenes/$tpdbId"
        val resp = runCatching {
            app.get(apiUrl, headers = TPDB_HEADERS)
        }.getOrNull()

        val root = runCatching { if (resp != null) JSONObject(resp.text) else null }.getOrNull()
        val scene = root?.optJSONObject("data")

        val title = scene?.optString("title") ?: titleFromUrl ?: "Scene $tpdbId"
        val rawPoster = scene?.optString("poster")
        val poster = if (!rawPoster.isNullOrBlank()) rawPoster else scene?.optJSONObject("background")?.optString("full")
        val description = scene?.optString("description")
        val date = scene?.optString("date")
        val year = if (!date.isNullOrBlank()) date.take(4).toIntOrNull() else null

        val tagsList = mutableListOf<String>()
        val tagsArr = scene?.optJSONArray("tags")
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                val tagObj = tagsArr.optJSONObject(i)
                val tagName = tagObj?.optString("name")
                if (!tagName.isNullOrBlank()) tagsList.add(tagName)
            }
        }

        val actorsList = mutableListOf<ActorData>()
        val perfArr = scene?.optJSONArray("performers")
        if (perfArr != null) {
            for (i in 0 until perfArr.length()) {
                val perfObj = perfArr.optJSONObject(i) ?: continue
                val perfName = perfObj.optString("name")
                val perfImage = perfObj.optString("image")
                if (perfName.isNotBlank()) {
                    actorsList.add(ActorData(Actor(perfName, if (!perfImage.isNullOrBlank()) perfImage else null)))
                }
            }
        }

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = "tpdb://movie/$tpdbId?title=$encodedTitle"
        ) {
            this.posterUrl = if (!poster.isNullOrBlank()) poster else null
            this.plot = if (!description.isNullOrBlank()) description else null
            this.year = year
            this.tags = tagsList
            this.actors = actorsList
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.removePrefix("tpdb://movie/").trim()
        val tpdbId = cleanData.substringBefore("?").trim()
        val queryParam = if (cleanData.contains("?")) "?" + cleanData.substringAfter("?") else ""
        val emitted = AtomicInteger(0)

        val backendEndpoint = "$backendBaseUrl/api/v1/streams/movie/$tpdbId$queryParam"

        val resp = runCatching {
            app.get(
                backendEndpoint,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "CloudStream-TpdbDiscovery/1.0"
                ),
                timeout = 15L
            )
        }.getOrNull()

        if (resp != null && resp.code == 200) {
            val json = runCatching { JSONObject(resp.text) }.getOrNull()
            val resultsArr = json?.optJSONArray("results")

            if (resultsArr != null) {
                for (i in 0 until resultsArr.length()) {
                    val item = resultsArr.optJSONObject(i) ?: continue
                    val streamUrl = item.optString("url").ifBlank { item.optString("streamUrl") }
                    if (streamUrl.isBlank()) continue

                    val source = item.optString("source").ifBlank { item.optString("sourceName", "TPDB") }
                    val quality = item.optString("quality", "1080p")
                    val isM3u8 = item.optBoolean("isM3u8", false) || streamUrl.contains(".m3u8")

                    val headersMap = mutableMapOf<String, String>()
                    val headersObj = item.optJSONObject("headers")
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            headersMap[key] = headersObj.optString(key)
                        }
                    }

                    if (isM3u8 || streamUrl.contains(".mp4")) {
                        val qual = parseQuality(quality)
                        callback(
                            ExtractorLink(
                                source = source,
                                name = "[${source.uppercase()}] $quality",
                                url = streamUrl,
                                referer = headersMap["Referer"] ?: "",
                                quality = qual,
                                isM3u8 = isM3u8,
                                headers = headersMap
                            )
                        )
                        emitted.incrementAndGet()
                    } else {
                        val loaded = loadExtractor(
                            url = streamUrl,
                            referer = headersMap["Referer"] ?: "",
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                        if (loaded) {
                            emitted.incrementAndGet()
                        }
                    }
                }
            }
        }

        // Hybrid Device Fallback: extract SpeedPorn embeds directly on phone if title is available
        val titleFromUrl = if (cleanData.contains("title=")) {
            runCatching { URLDecoder.decode(cleanData.substringAfter("title=").substringBefore("&"), "UTF-8") }.getOrNull()
        } else null

        if (titleFromUrl != null && titleFromUrl.isNotBlank()) {
            val cleanSlug = titleFromUrl.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val beforeColon = titleFromUrl.substringBefore(":").trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val afterHyphen = if (titleFromUrl.contains("-")) titleFromUrl.substringAfterLast("-").trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') else ""
            if (cleanSlug.length > 2) {
                val candidateSlugs = linkedSetOf(
                    cleanSlug,
                    cleanSlug.replace("-v", "-"),
                    cleanSlug.replace(Regex("-(\\d+)"), "-v$1")
                )
                if (beforeColon.length > 3 && beforeColon != cleanSlug) {
                    candidateSlugs.add(beforeColon)
                    candidateSlugs.add(beforeColon.replace("-v", "-"))
                    candidateSlugs.add(beforeColon.replace(Regex("-(\\d+)"), "-v$1"))
                }
                if (afterHyphen.length > 3 && afterHyphen != cleanSlug) {
                    candidateSlugs.add(afterHyphen)
                }
                for (slug in candidateSlugs) {
                    val directUrl = "https://speedporn.net/$slug/"
                    val found = runCatching {
                        Himeros().loadLinks(directUrl, isCasting, subtitleCallback) { link ->
                            callback(link)
                            emitted.incrementAndGet()
                        }
                    }.getOrDefault(false)
                    if (found) break
                }
            }
        }

        return emitted.get() > 0
    }

    private fun parseQuality(q: String): Int {
        val clean = q.lowercase().replace("p", "").trim()
        return when (clean) {
            "2160", "4k" -> Qualities.P2160.value
            "1080" -> Qualities.P1080.value
            "720" -> Qualities.P720.value
            "480" -> Qualities.P480.value
            "360" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}

@CloudstreamPlugin
class TpdbDiscoveryPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(TpdbDiscoveryProvider())
    }
}
