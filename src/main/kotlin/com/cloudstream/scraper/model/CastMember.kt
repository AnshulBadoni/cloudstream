package com.cloudstream.scraper.model

data class CastMember(
    val person: Person,
    val character: String? = null,
    val order: Int? = null
)
