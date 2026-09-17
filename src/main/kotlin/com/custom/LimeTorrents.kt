package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class LimeTorrents : MainAPI() {
    override var mainUrl = "https://www.limetorrents.fun"
    override var name = "LimeTorrents"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val mirrors = listOf(
        "https://www.limetorrents.fun",
        "https://www.limetorrents.lol",
        "https://www.limetorrents.li",
        "https://www.limetorrents.cc"
    )

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

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""\[.*?\]|\(.*?\)|<.*?>"""), " ")
        t = t.replace(Regex("""(?i)\b(?:Blacked|Evil\s*Angel|Brazzers|Tushy|Vixen|Bang!?|Naughty\s*America|Sweet\s*Sinner|Wicked|Digital\s*Playground|Jules\s*Jordan|Reality\s*Kings|DDF|Mofos)(?:\s*\d{2,4})?\b"""), " ")
        t = t.replace(Regex("""(?i)\b(?:\d{3,4}p|4K|2160p|1080p|720p|480p|WEB-?DL|BDRip|DVDRip|HDRip|x264|x265|HEVC|AAC|MP3|SPLITSCENES|XXX|FULL|HD|VOSTFR|FRENCH)\b"""), " ")
        t = t.replace(Regex("""\b(?:19|20)\d{2}\b"""), " ")
        t = t.replace(Regex("""#(\d+)"""), "$1")
        t = t.replace(Regex("""(?i)\.torrent|\.html"""), "")
        t = t.replace(Regex("""[-–—:_/]+"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t.ifEmpty { raw.trim() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("div.tt-name a:last-child, a[href*='-torrent-']") ?: return null
        val title = link.text().trim()
        if (title.isEmpty() || title.contains("Torrent Download", true)) return null
        val href = fixUrl(link.attr("href"))
        return newMovieSearchResponse(cleanTitle(title), href, TvType.NSFW)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(Regex("""[^a-zA-Z0-9]+"""), "-").trim('-')
        for (mirror in mirrors) {
            val url = "$mirror/search/all/$cleanQuery/"
            val document = try { app.get(url, headers = headers).document } catch (_: Exception) { continue }
            val results = document.select("table.table2 tr, tr").mapNotNull { it.toSearchResult() }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "LimeTorrents"
        var magnetUrl = document.selectFirst("a[href^='magnet:']")?.attr("href") ?: ""
        if (magnetUrl.isEmpty()) {
            val raw = document.html()
            magnetUrl = Regex("""magnet:\?[^\s"'<>]+""").find(raw)?.value ?: ""
        }
        return newMovieLoadResponse(title, url, TvType.NSFW, magnetUrl.ifEmpty { url })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var magnet = data
        var emitted = false
        if (!magnet.startsWith("magnet:")) {
            val document = app.get(data, headers = headers).document
            magnet = document.selectFirst("a[href^='magnet:']")?.attr("href")
                ?: Regex("""magnet:\?[^\s"'<>]+""").find(document.html())?.value
                ?: ""
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
                ).apply { type = ExtractorLinkType.TORRENT }
            )
            emitted = true
        }
        return emitted
    }
}
