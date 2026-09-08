package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.Qualities

const val INFER_TYPE = "infer"

data class ExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String = "",
    val quality: Int = Qualities.Unknown.value,
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)
