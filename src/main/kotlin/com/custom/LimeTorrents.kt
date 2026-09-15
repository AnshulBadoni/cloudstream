package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class LimeTorrents : MainAPI() {
    override var mainUrl = "https://www.limetorrents.lol"
    override var name = "LimeTorrents"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/browse-torrents/Other/" to "Other",
        "$mainUrl/top100" to "Top 100",
        "$mainUrl/latest100" to "Latest 100",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val document = app.get(url, headers = headers).document
        val items = document.select("table.table2 tr, tr").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
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
        val document = app.get(url, headers = headers).document
        return document.select("table.table2 tr, tr").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "LimeTorrents"
        val magnetUrl = document.selectFirst("a[href^='magnet:']")?.attr("href") ?: ""
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
            val document = app.get(data, headers = headers).document
            magnet = document.selectFirst("a[href^='magnet:']")?.attr("href") ?: ""
        }

        if (magnet.startsWith("magnet:")) {
            val quality = when {
                magnet.contains("2160p", true) || magnet.contains("4K", true) -> Qualities.P2160.value
                magnet.contains("1080p", true) -> Qualities.P1080.value
                magnet.contains("720p", true) -> Qualities.P720.value
                magnet.contains("480p", true) -> Qualities.P480.value
                else -> Qualities.P1080.value
            }
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} [Torrent]",
                    url = magnet,
                    referer = "$mainUrl/",
                    quality = quality,
                    isM3u8 = false
                )
            )
        }
        return true
    }
}
