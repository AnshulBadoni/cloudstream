package com.cloudstream.scraper.model

data class SearchResult(
    val id: String,
    val title: String,
    val url: String,
    val posterUrl: String? = null,
    val type: MediaType = MediaType.UNKNOWN,
    val releaseYear: Int? = null,
    val rating: Double? = null,
    val extra: Map<String, String> = emptyMap()
)
