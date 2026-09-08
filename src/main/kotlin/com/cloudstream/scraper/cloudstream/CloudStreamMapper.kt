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

    fun toSearchResponse(result: SearchResult, apiName: String): SearchResponse {
        val tvType = toTvType(result.type)
        val score = result.rating?.let { Score.from10(it) }
        return when (tvType) {
            TvType.TvSeries -> TvSeriesSearchResponse(
                name = result.title,
                url = result.url,
                apiName = apiName,
                type = tvType
            ).apply {
                posterUrl = result.posterUrl
                year = result.releaseYear
                this.score = score
            }
            else -> MovieSearchResponse(
                name = result.title,
                url = result.url,
                apiName = apiName,
                type = tvType
            ).apply {
                posterUrl = result.posterUrl
                year = result.releaseYear
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

    fun toLoadResponse(details: MediaDetails, apiName: String): LoadResponse {
        val actors = details.cast.map { toActorData(it) }.ifEmpty { null }
        val score = details.rating?.let { Score.from10(it) }
        val tvType = toTvType(details.type)
        val trailers = details.trailerUrl?.let { mutableListOf(TrailerData(it)) } ?: mutableListOf()

        return when (details) {
            is Movie -> MovieLoadResponse(
                name = details.title,
                url = details.url,
                apiName = apiName,
                type = tvType,
                dataUrl = details.url
            ).apply {
                posterUrl = details.posterUrl
                year = details.releaseYear
                plot = details.description
                this.score = score
                tags = (details.genres + details.tags).distinct().ifEmpty { null }
                duration = details.durationMinutes
                this.trailers = trailers
                this.actors = actors
            }
            is Series -> {
                val flatEpisodes = details.seasons.flatMap { it.episodes }.map { toEpisode(it) }
                TvSeriesLoadResponse(
                    name = details.title,
                    url = details.url,
                    apiName = apiName,
                    type = tvType,
                    episodes = flatEpisodes
                ).apply {
                    posterUrl = details.posterUrl
                    year = details.releaseYear
                    plot = details.description
                    this.score = score
                    tags = (details.genres + details.tags).distinct().ifEmpty { null }
                    this.trailers = trailers
                    this.actors = actors
                }
            }
        }
    }

    fun toLoadResponse(person: Person, apiName: String): LoadResponse {
        val videoRecommendations = person.knownFor.map { toSearchResponse(it, apiName) }.ifEmpty { null }
        return MovieLoadResponse(
            name = person.name,
            url = person.url,
            apiName = apiName,
            type = TvType.NSFW,
            dataUrl = person.url
        ).apply {
            posterUrl = person.photoUrl
            plot = person.biography ?: "Performer profile with ${person.knownFor.size} videos."
            recommendations = videoRecommendations
            actors = listOf(ActorData(Actor(person.name, person.photoUrl), roleString = "Performer"))
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
