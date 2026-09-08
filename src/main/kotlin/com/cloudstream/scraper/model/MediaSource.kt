package com.cloudstream.scraper.model

data class MediaSource(
    val url: String,
    val name: String,
    val isM3u8: Boolean = false,
    val isDash: Boolean = false,
    val quality: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList()
)

data class Subtitle(
    val url: String,
    val language: String,
    val isDefault: Boolean = false
)
