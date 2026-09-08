package com.cloudstream.scraper.model

enum class CatalogType {
    MOVIES,
    SERIES,
    PEOPLE,
    MIXED,
    UNKNOWN
}

data class Catalog(
    val id: String,
    val name: String,
    val type: CatalogType,
    val url: String,
    val items: List<SearchResult> = emptyList(),
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val extra: Map<String, String> = emptyMap()
)
