@file:JvmName("ExtractorApiKt")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.Qualities

fun getQualityFromName(qualityName: String?): Int {
    if (qualityName == null) return Qualities.Unknown.value
    val digits = qualityName.filter { it.isDigit() }.toIntOrNull() ?: return Qualities.Unknown.value
    return when {
        digits >= 2160 -> Qualities.P2160.value
        digits >= 1440 -> Qualities.P1440.value
        digits >= 1080 -> Qualities.P1080.value
        digits >= 720 -> Qualities.P720.value
        digits >= 480 -> Qualities.P480.value
        digits >= 360 -> Qualities.P360.value
        digits >= 240 -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }
}

suspend fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    builder: suspend ExtractorLink.() -> Unit = {}
): ExtractorLink {
    val link = ExtractorLink(
        source = source,
        name = name,
        url = url,
    )
    link.type = type
    link.builder()
    return link
}

suspend fun loadExtractor(
    url: String,
    referer: String? = null,
    subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit = {},
    callback: (ExtractorLink) -> Unit = {}
): Boolean {
    return false
}

suspend fun loadExtractor(
    url: String,
    subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit = {},
    callback: (ExtractorLink) -> Unit = {}
): Boolean {
    return loadExtractor(url, null, subtitleCallback, callback)
}

