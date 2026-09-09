@file:JvmName("ExtractorApiKt")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.Qualities

enum class ExtractorLinkType {
    VIDEO,
    TORRENT,
    M3U8,
    DASH,
    SUBTITLE
}

const val INFER_TYPE = "infer"

open class ExtractorLink(
    open var source: String,
    open var name: String,
    open var url: String,
    open var referer: String = "",
    open var quality: Int = Qualities.Unknown.value,
    open var isM3u8: Boolean = false,
    open var headers: Map<String, String> = mapOf(),
    open var extractorData: String? = null,
    open var type: ExtractorLinkType = ExtractorLinkType.VIDEO
)

suspend fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    builder: suspend ExtractorLink.() -> Unit = {}
): ExtractorLink {
    val res = ExtractorLink(
        source = source,
        name = name,
        url = url,
        type = type
    )
    res.builder()
    return res
}

fun getQualityFromName(qualityName: String?): Int {
    if (qualityName.isNullOrBlank()) return Qualities.Unknown.value
    return when {
        qualityName.contains("2160") || qualityName.contains("4k", ignoreCase = true) -> Qualities.P2160.value
        qualityName.contains("1440") || qualityName.contains("2k", ignoreCase = true) -> Qualities.P1440.value
        qualityName.contains("1080") -> Qualities.P1080.value
        qualityName.contains("720") -> Qualities.P720.value
        qualityName.contains("480") -> Qualities.P480.value
        qualityName.contains("360") -> Qualities.P360.value
        qualityName.contains("240") -> Qualities.P240.value
        qualityName.contains("144") -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }
}
