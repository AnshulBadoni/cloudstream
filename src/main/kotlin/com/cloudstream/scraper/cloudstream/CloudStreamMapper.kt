package com.cloudstream.scraper.cloudstream

import com.cloudstream.scraper.model.*
import com.cloudstream.scraper.model.Episode as ScraperEpisode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

object CloudStreamMapper {

    fun toTvType(mediaType: MediaType): TvType {
        return when (mediaType) {
            MediaType.NSFW -> TvType.NSFW
            MediaType.MOVIE -> TvType.Movie
            MediaType.TV_SERIES -> TvType.TvSeries
            MediaType.ANIME -> TvType.Anime
            MediaType.LIVE_STREAM -> TvType.Live
            MediaType.UNKNOWN -> TvType.NSFW
        }
    }

    fun toSearchResponse(result: SearchResult, api: MainAPI): SearchResponse {
        val tvType = toTvType(result.type)
        val score = result.rating?.let { Score.from10(it) }
        return when (tvType) {
            TvType.TvSeries -> {
                val res = CloudStreamBridge.createTvSeriesSearchResponse(api, result.title, result.url, tvType)
                CloudStreamBridge.setField(res, "posterUrl", result.posterUrl)
                CloudStreamBridge.setField(res, "year", result.releaseYear)
                CloudStreamBridge.setField(res, "score", score)
                res
            }
            else -> {
                val res = CloudStreamBridge.createMovieSearchResponse(api, result.title, result.url, tvType)
                CloudStreamBridge.setField(res, "posterUrl", result.posterUrl)
                CloudStreamBridge.setField(res, "year", result.releaseYear)
                CloudStreamBridge.setField(res, "score", score)
                res
            }
        }
    }

    fun toActorData(castMember: CastMember): ActorData {
        return ActorData(
            actor = Actor(
                name = castMember.person.name,
                image = castMember.person.photoUrl
            ),
            roleString = castMember.character
        )
    }

    fun toEpisode(episode: ScraperEpisode): Episode {
        return Episode(
            data = episode.url.ifBlank { episode.id }
        ).apply {
            name = episode.title
            season = episode.seasonNumber
            this.episode = episode.episodeNumber
            posterUrl = episode.posterUrl
            this.score = episode.rating?.let { Score.from10(it) }
            description = episode.description
        }
    }

    suspend fun toLoadResponse(details: MediaDetails, api: MainAPI): LoadResponse {
        val actors = details.cast.map { toActorData(it) }.ifEmpty { null }
        val score = details.rating?.let { Score.from10(it) }
        val tvType = toTvType(details.type)

        return when (details) {
            is Movie -> {
                val res = CloudStreamBridge.createMovieLoadResponse(
                    api = api,
                    name = details.title,
                    url = details.url,
                    type = tvType,
                    dataUrl = details.url
                )
                CloudStreamBridge.setField(res, "posterUrl", details.posterUrl)
                CloudStreamBridge.setField(res, "year", details.releaseYear)
                CloudStreamBridge.setField(res, "plot", details.description)
                CloudStreamBridge.setField(res, "score", score)
                CloudStreamBridge.setField(res, "tags", (details.genres + details.tags).distinct().ifEmpty { null })
                CloudStreamBridge.setField(res, "duration", details.durationMinutes)
                CloudStreamBridge.setField(res, "actors", actors)
                res
            }
            is Series -> {
                val flatEpisodes = details.seasons.flatMap { it.episodes }.map { toEpisode(it) }
                val res = CloudStreamBridge.createTvSeriesLoadResponse(
                    api = api,
                    name = details.title,
                    url = details.url,
                    type = tvType,
                    episodes = flatEpisodes
                )
                CloudStreamBridge.setField(res, "posterUrl", details.posterUrl)
                CloudStreamBridge.setField(res, "year", details.releaseYear)
                CloudStreamBridge.setField(res, "plot", details.description)
                CloudStreamBridge.setField(res, "score", score)
                CloudStreamBridge.setField(res, "tags", (details.genres + details.tags).distinct().ifEmpty { null })
                CloudStreamBridge.setField(res, "actors", actors)
                res
            }
        }
    }

    suspend fun toLoadResponse(person: Person, api: MainAPI): LoadResponse {
        val videoRecommendations = person.knownFor.map { toSearchResponse(it, api) }.ifEmpty { null }
        val res = CloudStreamBridge.createMovieLoadResponse(
            api = api,
            name = person.name,
            url = person.url,
            type = TvType.NSFW,
            dataUrl = person.url
        )
        CloudStreamBridge.setField(res, "posterUrl", person.photoUrl)
        CloudStreamBridge.setField(res, "plot", person.biography ?: "Performer profile with ${person.knownFor.size} videos.")
        CloudStreamBridge.setField(res, "recommendations", videoRecommendations)
        CloudStreamBridge.setField(res, "actors", listOf(ActorData(Actor(person.name, person.photoUrl), roleString = "Performer")))
        return res
    }

    fun toExtractorLink(source: MediaSource, apiName: String): ExtractorLink {
        return ExtractorLink(
            source = apiName,
            name = source.name,
            url = source.url,
            isM3u8 = source.isM3u8,
            quality = source.quality ?: Qualities.Unknown.value,
            headers = source.headers
        )
    }

    fun toSubtitleFile(subtitle: Subtitle): SubtitleFile {
        return SubtitleFile(
            lang = subtitle.language,
            url = subtitle.url
        )
    }
}
