@file:JvmName("MainAPIKt")
package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLink

enum class TvType {
    Movie,
    TvSeries,
    Anime,
    AnimeMovie,
    OVA,
    Cartoon,
    AsianDrama,
    Documentary,
    Live,
    NSFW,
    Others,
    Torrent
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

enum class SearchQuality {
    Cam, CamRip, HdCam, Telesync, Telecine, WorkPrint, Dvd, Tvh, Hdtv, VOD, WebRip, WebDl, BluRay, FourK, Unknown
}

enum class ShowStatus {
    Completed, Ongoing
}

enum class DubStatus(val id: Int) {
    None(-1), Dubbed(1), Subbed(0)
}

class Score(val data: Int) {
    fun toInt(maxScore: Int = 10): Int = ((data.toLong() * maxScore.toLong()) / 1000000000L).toInt()
    fun toDouble(maxScore: Int = 10): Double = (data.toDouble() / 1000000000.0) * maxScore.toDouble()
    override fun toString(): String = (data.toDouble() / 100000000.0).toString()

    companion object {
        fun from10(value: Double?): Score? {
            if (value == null) return null
            return Score((value.coerceIn(0.0, 10.0) * 100000000.0).toInt())
        }
        fun from(value: Number?, max: Number): Score? {
            if (value == null) return null
            val ratio = (value.toDouble() / max.toDouble()).coerceIn(0.0, 1.0)
            return Score((ratio * 1000000000.0).toInt())
        }
    }
}

data class TrailerData(
    val extractorUrl: String,
    val referer: String? = null,
    val raw: Boolean = false,
    val headers: Map<String, String> = mapOf(),
)

data class NextAiring(val episode: Int, val unixTime: Long, val season: Int? = null)
data class SeasonData(val season: Int, val name: String? = null, val displaySeason: Int? = null)

interface SearchResponse {
    val name: String
    val url: String
    val apiName: String
    var type: TvType?
    var posterUrl: String?
    var id: Int?
    var quality: SearchQuality?
    var posterHeaders: Map<String, String>?
    var score: Score?
}

data class MovieSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse

data class TvSeriesSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    var year: Int? = null,
    var episodes: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse

data class AnimeSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    var year: Int? = null,
    var dubStatus: MutableSet<DubStatus>? = null,
    var otherName: String? = null,
    var episodes: MutableMap<DubStatus, Int> = mutableMapOf(),
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse

interface LoadResponse {
    var name: String
    var url: String
    var apiName: String
    var type: TvType
    var posterUrl: String?
    var year: Int?
    var plot: String?
    var rating: Int?
    var tags: List<String>?
    var duration: Int?
    var recommendations: List<SearchResponse>?
    var actors: List<ActorData>?
    var posterHeaders: Map<String, String>?
}

data class MovieLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var posterHeaders: Map<String, String>? = null,
) : LoadResponse

data class TvSeriesLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    var episodes: List<Episode>,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var posterHeaders: Map<String, String>? = null,
) : LoadResponse

data class Episode(
    var data: String,
    var name: String? = null,
    var season: Int? = null,
    var episode: Int? = null,
    var posterUrl: String? = null,
    var score: Score? = null,
    var description: String? = null,
    var date: Long? = null,
    var runTime: Int? = null,
) {
    var rating: Int?
        get() = score?.toInt(100)
        set(value) {
            score = Score.from(value, 100)
        }
}

typealias CloudStreamEpisode = Episode

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
    var list: List<SearchResponse>,
    val isHorizontalImages: Boolean = false
)

data class HomePageResponse(
    val items: List<HomePageList>,
    val hasNext: Boolean = false
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
    open val hasDownloadSupport: Boolean = true
    open val mainPage: List<MainPageData> = emptyList()
    var sourcePlugin: String? = null

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

fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    dataUrl: String,
    builder: MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse {
    val res = MovieLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        dataUrl = dataUrl
    )
    res.builder()
    return res
}

fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType,
    episodes: List<Episode>,
    builder: TvSeriesLoadResponse.() -> Unit = {}
): TvSeriesLoadResponse {
    val res = TvSeriesLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        episodes = episodes
    )
    res.builder()
    return res
}


fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    builder: MovieSearchResponse.() -> Unit = {}
): MovieSearchResponse {
    val res = MovieSearchResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        posterUrl = null,
        year = null,
        id = null,
        quality = null,
        posterHeaders = null,
        score = null
    )
    res.builder()
    return res
}

fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true,
    builder: TvSeriesSearchResponse.() -> Unit = {}
): TvSeriesSearchResponse {
    val res = TvSeriesSearchResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        posterUrl = null,
        year = null,
        episodes = null,
        id = null,
        quality = null,
        posterHeaders = null,
        score = null
    )
    res.builder()
    return res
}

fun newHomePageResponse(
    items: List<HomePageList>,
    hasNext: Boolean? = false
): HomePageResponse {
    return HomePageResponse(
        items = items,
        hasNext = hasNext ?: false
    )
}

fun newHomePageResponse(
    item: HomePageList,
    hasNext: Boolean? = false
): HomePageResponse {
    return HomePageResponse(
        items = listOf(item),
        hasNext = hasNext ?: false
    )
}

fun mainPageOf(vararg pages: Pair<String, String>): List<MainPageData> {
    return pages.map { MainPageData(it.second, it.first) }
}

fun MainAPI.fixUrl(url: String): String {
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    if (url.startsWith("//")) return "https:$url"
    return mainUrl.trimEnd('/') + "/" + url.trimStart('/')
}

fun MainAPI.fixUrlNull(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return fixUrl(url)
}
