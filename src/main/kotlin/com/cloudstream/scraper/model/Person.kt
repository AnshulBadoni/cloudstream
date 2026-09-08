package com.cloudstream.scraper.model

data class Person(
    val id: String,
    val name: String,
    val url: String,
    val photoUrl: String? = null,
    val biography: String? = null,
    val birthDate: String? = null,
    val knownFor: List<SearchResult> = emptyList(),
    val extra: Map<String, String> = emptyMap()
)
