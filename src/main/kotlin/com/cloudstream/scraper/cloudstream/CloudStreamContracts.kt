package com.cloudstream.scraper.cloudstream

/**
 * Standard CloudStream API contracts matching com.lagradost.cloudstream3.*
 * This guarantees exact API compatibility without tying the standalone engine compile phase to Android libraries.
 */

enum class TvType {
    Movie,
    TvSeries,
    Anime,
    Live,
    NSFW,
    Others
}

enum class Qualities(val value: Int) {
    Unknown(400),
    P144(144),
    P240(240),
    P360(360),
    P480(480),
    P720(720),
    P1080(1080),
    P1440(1440),
    P2160(2160)
}

data class Actor(val name: String, val image: String? = null)
data class ActorData(val actor: Actor, val roleString: String? = null, val voiceActor: Actor? = null)

interface SearchResponse {
    val name: String
    val url: String
    val apiName: String
    val type: TvType
    var posterUrl: String?
    var id: Int?
    var quality: SearchQuality?
    var posterHeaders: Map<String, String>?
}

enum class SearchQuality {
    SD, HD, UHD, Cam, TeleSync, BlueRay, FourK
}

data class MovieSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Movie,
    override var posterUrl: String? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    var year: Int? = null
) : SearchResponse

data class TvSeriesSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.TvSeries,
    override var posterUrl: String? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    var year: Int? = null
) : SearchResponse

interface LoadResponse {
    val name: String
    val url: String
    val apiName: String
    val type: TvType
    var posterUrl: String?
    var year: Int?
    var plot: String?
    var rating: Int?
    var tags: List<String>?
    var duration: Int?
    var actors: List<ActorData>?
    var recommendations: List<SearchResponse>?
    var trailerUrl: String?
}

data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Movie,
    val dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var actors: List<ActorData>? = null,
    override var recommendations: List<SearchResponse>? = null,
    override var trailerUrl: String? = null
) : LoadResponse

data class TvSeriesLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.TvSeries,
    val episodes: List<CloudStreamEpisode>,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var actors: List<ActorData>? = null,
    override var recommendations: List<SearchResponse>? = null,
    override var trailerUrl: String? = null
) : LoadResponse

data class CloudStreamEpisode(
    val data: String,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val rating: Int? = null,
    val description: String? = null,
    val date: String? = null
)

data class MainPageData(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)

data class MainPageRequest(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)

data class HomePageList(
    val name: String,
    val list: List<SearchResponse>,
    val isHorizontalImages: Boolean = false
)

data class HomePageResponse(
    val items: List<HomePageList>,
    val hasNext: Boolean = false
)

data class ExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String = "",
    val quality: Int = Qualities.Unknown.value,
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)

data class SubtitleFile(
    val lang: String,
    val url: String
)

abstract class MainAPI {
    open var mainUrl: String = ""
    open var name: String = ""
    open val supportedTypes: Set<TvType> = setOf(TvType.NSFW)
    open var lang: String = "en"
    open val isNsfw: Boolean = true
    open val hasMainPage: Boolean = false
    open val mainPage: List<MainPageData> = emptyList()

    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null
    open suspend fun search(query: String): List<SearchResponse> = emptyList()
    open suspend fun load(url: String): LoadResponse? = null
    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean = false,
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit = {}
    ): Boolean = false
}

annotation class CloudstreamPlugin

abstract class Plugin {
    open var resources: Any? = null
    open var filename: String? = null
    val registeredAPIs = mutableListOf<MainAPI>()

    open fun load(context: Any? = null) {}

    fun registerMainAPI(api: MainAPI) {
        registeredAPIs.add(api)
    }
}
