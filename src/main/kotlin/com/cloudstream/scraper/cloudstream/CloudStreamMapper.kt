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
            TvType.TvSeries -> api.newTvSeriesSearchResponse(
                name = result.title,
                url = result.url,
                type = tvType
            ) {
                this.posterUrl = result.posterUrl
                this.year = result.releaseYear
                this.score = score
            }
            else -> api.newMovieSearchResponse(
                name = result.title,
                url = result.url,
                type = tvType
            ) {
                this.posterUrl = result.posterUrl
                this.year = result.releaseYear
                this.score = score
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
                api.newMovieLoadResponse(
                    name = details.title,
                    url = details.url,
                    type = tvType,
                    dataUrl = details.url
                ) {
                    this.posterUrl = details.posterUrl
                    this.year = details.releaseYear
                    this.plot = details.description
                    this.score = score
                    this.tags = (details.genres + details.tags).distinct().ifEmpty { null }
                    this.duration = details.durationMinutes
                    this.actors = actors
                }
            }
            is Series -> {
                val flatEpisodes = details.seasons.flatMap { it.episodes }.map { toEpisode(it) }
                api.newTvSeriesLoadResponse(
                    name = details.title,
                    url = details.url,
                    type = tvType,
                    episodes = flatEpisodes
                ) {
                    this.posterUrl = details.posterUrl
                    this.year = details.releaseYear
                    this.plot = details.description
                    this.score = score
                    this.tags = (details.genres + details.tags).distinct().ifEmpty { null }
                    this.actors = actors
                }
            }
        }
    }

    suspend fun toLoadResponse(person: Person, api: MainAPI): LoadResponse {
        val videoRecommendations = person.knownFor.map { toSearchResponse(it, api) }.ifEmpty { null }
        return api.newMovieLoadResponse(
            name = person.name,
            url = person.url,
            type = TvType.NSFW,
            dataUrl = person.url
        ) {
            this.posterUrl = person.photoUrl
            this.plot = person.biography ?: "Performer profile with ${person.knownFor.size} videos."
            this.recommendations = videoRecommendations
            this.actors = listOf(ActorData(Actor(person.name, person.photoUrl), roleString = "Performer"))
        }
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
