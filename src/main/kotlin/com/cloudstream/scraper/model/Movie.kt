package com.cloudstream.scraper.model

sealed interface MediaDetails {
    val id: String
    val title: String
    val originalTitle: String?
    val url: String
    val posterUrl: String?
    val backdropUrl: String?
    val description: String?
    val releaseYear: Int?
    val rating: Double?
    val genres: List<String>
    val tags: List<String>
    val trailerUrl: String?
    val cast: List<CastMember>
    val directors: List<Person>
    val type: MediaType
    val extra: Map<String, String>
}

data class Movie(
    override val id: String,
    override val title: String,
    override val originalTitle: String? = null,
    override val url: String,
    override val posterUrl: String? = null,
    override val backdropUrl: String? = null,
    override val description: String? = null,
    override val releaseYear: Int? = null,
    override val rating: Double? = null,
    override val genres: List<String> = emptyList(),
    override val tags: List<String> = emptyList(),
    override val trailerUrl: String? = null,
    override val cast: List<CastMember> = emptyList(),
    override val directors: List<Person> = emptyList(),
    override val type: MediaType = MediaType.MOVIE,
    override val extra: Map<String, String> = emptyMap(),
    val durationMinutes: Int? = null
) : MediaDetails

data class Series(
    override val id: String,
    override val title: String,
    override val originalTitle: String? = null,
    override val url: String,
    override val posterUrl: String? = null,
    override val backdropUrl: String? = null,
    override val description: String? = null,
    override val releaseYear: Int? = null,
    override val rating: Double? = null,
    override val genres: List<String> = emptyList(),
    override val tags: List<String> = emptyList(),
    override val trailerUrl: String? = null,
    override val cast: List<CastMember> = emptyList(),
    override val directors: List<Person> = emptyList(),
    override val type: MediaType = MediaType.TV_SERIES,
    override val extra: Map<String, String> = emptyMap(),
    val seasons: List<Season> = emptyList()
) : MediaDetails
