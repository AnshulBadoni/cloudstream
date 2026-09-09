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
) {
    var type: ExtractorLinkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
}

