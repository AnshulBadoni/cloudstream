package com.cloudstream.scraper.model

data class Episode(
    val id: String,
    val title: String? = null,
    val episodeNumber: Int,
    val seasonNumber: Int = 1,
    val url: String,
    val posterUrl: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val rating: Double? = null,
    val extra: Map<String, String> = emptyMap()
)

data class Season(
    val seasonNumber: Int,
    val name: String? = null,
    val episodes: List<Episode> = emptyList()
)
