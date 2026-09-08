package com.cloudstream.scraper.cloudstream

import com.cloudstream.scraper.model.*

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
        return when (tvType) {
            TvType.TvSeries -> TvSeriesSearchResponse(
                name = result.title,
                url = result.url,
                apiName = apiName,
                type = tvType,
                posterUrl = result.posterUrl,
                year = result.releaseYear
            )
            else -> MovieSearchResponse(
                name = result.title,
                url = result.url,
                apiName = apiName,
                type = tvType,
                posterUrl = result.posterUrl,
                year = result.releaseYear
            )
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

    fun toEpisode(episode: Episode): CloudStreamEpisode {
        return CloudStreamEpisode(
            data = episode.url.ifBlank { episode.id },
            name = episode.title,
            season = episode.seasonNumber,
            episode = episode.episodeNumber,
            posterUrl = episode.posterUrl,
            description = episode.description,
            date = episode.releaseDate
        )
    }

    fun toLoadResponse(details: MediaDetails, apiName: String): LoadResponse {
        val actors = details.cast.map { toActorData(it) }.ifEmpty { null }
        val ratingInt = details.rating?.let { (it * 1000).toInt() } // CloudStream standard rating representation
        val tvType = toTvType(details.type)

        return when (details) {
            is Movie -> MovieLoadResponse(
                name = details.title,
                url = details.url,
                apiName = apiName,
                type = tvType,
                dataUrl = details.url,
                posterUrl = details.posterUrl,
                year = details.releaseYear,
                plot = details.description,
                rating = ratingInt,
                tags = (details.genres + details.tags).distinct().ifEmpty { null },
                duration = details.durationMinutes,
                actors = actors,
                trailerUrl = details.trailerUrl
            )
            is Series -> {
                val flatEpisodes = details.seasons.flatMap { it.episodes }.map { toEpisode(it) }
                TvSeriesLoadResponse(
                    name = details.title,
                    url = details.url,
                    apiName = apiName,
                    type = tvType,
                    episodes = flatEpisodes,
                    posterUrl = details.posterUrl,
                    year = details.releaseYear,
                    plot = details.description,
                    rating = ratingInt,
                    tags = (details.genres + details.tags).distinct().ifEmpty { null },
                    actors = actors,
                    trailerUrl = details.trailerUrl
                )
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
            dataUrl = person.url,
            posterUrl = person.photoUrl,
            plot = person.biography ?: "Performer profile with ${person.knownFor.size} videos.",
            recommendations = videoRecommendations,
            actors = listOf(ActorData(Actor(person.name, person.photoUrl), roleString = "Performer"))
        )
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
