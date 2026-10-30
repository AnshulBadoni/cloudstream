package com.custom

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

@JsonIgnoreProperties(ignoreUnknown = true)
data class TpdbStreamResponse(
    val status: String = "",
    val results: List<TpdbStreamItem> = emptyList(),
    val cached: Boolean = false,
    val retryAfter: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TpdbStreamItem(
    val source: String = "",
    val quality: String = "1080p",
    val url: String = "",
    val isM3u8: Boolean = false,
    val size: Long? = null,
    val headers: Map<String, String> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TpdbSceneSearchResponse(
    val data: List<TpdbSceneItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TpdbSceneItem(
    val id: String = "",
    val title: String = "",
    val site: TpdbSite? = null,
    val date: String? = null,
    val poster: String? = null,
    val background: TpdbBackground? = null,
    val description: String? = null,
    val performers: List<TpdbPerformer> = emptyList(),
    val tags: List<TpdbTag> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TpdbSite(val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TpdbBackground(val large: String? = null, val full: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TpdbPerformer(val name: String = "", val image: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TpdbTag(val name: String = "")

open class TpdbDiscoveryProvider : MainAPI() {

    override var mainUrl = "https://theporndb.net"
    override var name = "TPDB Discovery"
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override var lang = "en"
    override val isNsfw = true
    override val hasMainPage = true
    override val hasDownloadSupport = true

    var backendBaseUrl = "https://cloudstream-gl1n.onrender.com"

    private val jsonMapper = jacksonObjectMapper()

    override val mainPage = listOf(
        MainPageData("Trending Scenes", "https://api.theporndb.net/scenes?sort=trending"),
        MainPageData("Latest Scenes", "https://api.theporndb.net/scenes?sort=date")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "${request.data}&page=$page"
        val resp = runCatching {
            app.get(url, headers = mapOf("Accept" to "application/json"))
        }.getOrNull() ?: return null

        val searchData = runCatching {
            jsonMapper.readValue<TpdbSceneSearchResponse>(resp.text)
        }.getOrNull() ?: return null

        val items = searchData.data.map { scene ->
            val displayTitle = if (scene.site?.name != null) "${scene.site.name} - ${scene.title}" else scene.title
            newMovieSearchResponse(displayTitle, "tpdb://movie/${scene.id}", TvType.Movie) {
                this.posterUrl = scene.poster
                this.year = scene.date?.take(4)?.toIntOrNull()
            }
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
            app.get(searchUrl, headers = mapOf("Accept" to "application/json"))
        }.getOrNull() ?: return emptyList()

        val searchData = runCatching {
            jsonMapper.readValue<TpdbSceneSearchResponse>(resp.text)
        }.getOrNull() ?: return emptyList()

        return searchData.data.map { scene ->
            val displayTitle = if (scene.site?.name != null) "${scene.site.name} - ${scene.title}" else scene.title
            newMovieSearchResponse(displayTitle, "tpdb://movie/${scene.id}", TvType.Movie) {
                this.posterUrl = scene.poster
                this.year = scene.date?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val tpdbId = url.removePrefix("tpdb://movie/").trim()

        val apiUrl = "https://api.theporndb.net/scenes/$tpdbId"
        val resp = runCatching {
            app.get(apiUrl, headers = mapOf("Accept" to "application/json"))
        }.getOrNull() ?: return null

        val node = runCatching {
            jsonMapper.readTree(resp.text).get("data")
        }.getOrNull() ?: return null

        val scene = jsonMapper.treeToValue(node, TpdbSceneItem::class.java)
        val displayTitle = if (scene.site?.name != null) "${scene.site.name} - ${scene.title}" else scene.title

        return newMovieLoadResponse(
            name = displayTitle,
            url = url,
            type = TvType.Movie,
            dataUrl = "tpdb://movie/$tpdbId"
        ) {
            this.posterUrl = scene.poster
            this.plot = scene.description
            this.year = scene.date?.take(4)?.toIntOrNull()
            this.tags = scene.tags.map { it.name }
            this.actors = scene.performers.map { ActorData(Actor(it.name, it.image)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tpdbId = data.removePrefix("tpdb://movie/").trim()
        val emitted = AtomicInteger(0)

        val backendEndpoint = "$backendBaseUrl/api/v1/streams/movie/$tpdbId"

        val resp = runCatching {
            app.get(
                backendEndpoint,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "CloudStream-TpdbDiscovery/1.0"
                ),
                timeout = 10L
            )
        }.getOrNull()

        if (resp == null || resp.code != 200) {
            println("[TpdbDiscoveryProvider] Failed contacting discovery backend for $tpdbId (code=${resp?.code})")
            return false
        }

        val backendResult = runCatching {
            jsonMapper.readValue<TpdbStreamResponse>(resp.text)
        }.getOrNull()

        if (backendResult == null || backendResult.results.isEmpty()) {
            println("[TpdbDiscoveryProvider] No discovered streams returned for $tpdbId")
            return false
        }

        for (item in backendResult.results) {
            if (item.url.isBlank()) continue

            // If direct stream URL (m3u8 or mp4)
            if (item.isM3u8 || item.url.contains(".m3u8") || item.url.contains(".mp4")) {
                val qual = parseQuality(item.quality)
                callback(
                    ExtractorLink(
                        source = item.source,
                        name = "[${item.source.uppercase()}] ${item.quality}",
                        url = item.url,
                        referer = item.headers["Referer"] ?: "",
                        quality = qual,
                        isM3u8 = item.isM3u8 || item.url.contains(".m3u8"),
                        headers = item.headers
                    )
                )
                emitted.incrementAndGet()
            } else {
                // Delegate to CloudStream's host extractors (e.g., Streamtape, DoodStream, MixDrop, VOE)
                val loaded = loadExtractor(
                    url = item.url,
                    referer = item.headers["Referer"] ?: "",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                if (loaded) {
                    emitted.incrementAndGet()
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
