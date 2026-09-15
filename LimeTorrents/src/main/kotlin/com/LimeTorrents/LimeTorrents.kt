package com.LimeTorrents

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class LimeTorrents : MainAPI() {
    override var mainUrl = "https://www.limetorrents.lol"
    override var name = "LimeTorrents"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/browse-torrents/Other/" to "Other",
        "$mainUrl/top100" to "Top 100",
        "$mainUrl/latest100" to "Latest 100",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val document = app.get(url).document
        val items = document.select("table.table2 tr, tr").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("div.tt-name a:last-child, a[href*='-torrent-']") ?: return null
        val title = link.text().trim()
        if (title.isEmpty() || title.contains("Torrent Download", true)) return null
        val href = fixUrl(link.attr("href"))
        return newMovieSearchResponse(title, href, TvType.NSFW)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(Regex("""[^a-zA-Z0-9]+"""), "-")
        val url = "$mainUrl/search/all/$cleanQuery/"
        val document = app.get(url).document
        return document.select("table.table2 tr, tr").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "LimeTorrents"
        var magnetUrl = document.selectFirst("a[href^='magnet:']")?.attr("href") ?: ""
        return newMovieLoadResponse(title, url, TvType.NSFW, magnetUrl.ifEmpty { url })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var magnet = data
        if (!magnet.startsWith("magnet:")) {
            val document = app.get(data).document
            magnet = document.selectFirst("a[href^='magnet:']")?.attr("href") ?: ""
        }

        if (magnet.startsWith("magnet:")) {
            val quality = when {
                magnet.contains("2160p", true) || magnet.contains("4K", true) -> Qualities.P2160.value
                magnet.contains("1080p", true) -> Qualities.P1080.value
                magnet.contains("720p", true) -> Qualities.P720.value
                magnet.contains("480p", true) -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} [Torrent]",
                    url = magnet,
                    type = ExtractorLinkType.VIDEO
                ).apply {
                    this.quality = quality
                }
            )
        }
        return true
    }
}
