package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LimeTorrents : MainAPI() {
    override var mainUrl = TorrentSupport.limeMirrors.first()
    override var name = "LimeTorrents"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/browse-torrents/Movies/" to "Movies",
        "$mainUrl/top100" to "Top 100",
        "$mainUrl/latest100" to "Latest 100",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        for (mirror in TorrentSupport.limeMirrors) {
            val baseUrl = request.data.replace(mainUrl, mirror)
            val url = if (page <= 1) baseUrl else "${baseUrl.trimEnd('/')}/$page/"
            val document = runCatching { app.get(url, headers = headers).document }.getOrNull() ?: continue
            val items = parseSearchEntries(document, url)
            if (items.isNotEmpty()) {
                return newHomePageResponse(HomePageList(request.name, items), hasNext = true)
            }
        }
        return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""&#?\w+;"""), " ")
        t = t.replace(Regex("""\[.*?]|\(.*?\)|<.*?>"""), " ")
        t = t.replace(
            Regex("""(?i)\b(?:Blacked|Evil\s*Angel|Brazzers|Tushy|Vixen|Bang!?|Naughty\s*America|Sweet\s*Sinner|Wicked|Digital\s*Playground|Jules\s*Jordan|Reality\s*Kings|DDF|Mofos)(?:\s*\d{2,4})?\b"""),
            " "
        )
        t = t.replace(
            Regex("""(?i)\b(?:\d{3,4}p|4K|2160p|1080p|720p|480p|WEB-?DL|BDRip|DVDRip|HDRip|x264|x265|HEVC|AAC|MP3|SPLITSCENES|XXX|FULL|HD|VOSTFR|FRENCH)\b"""),
            " "
        )
        t = t.replace(Regex("""\b(?:19|20)\d{2}\b"""), " ")
        t = t.replace(Regex("""#(\d+)"""), "$1")
        t = t.replace(Regex("""(?i)\.torrent|\.html"""), "")
        t = t.replace(Regex("""[-–—:_/]+"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t.ifEmpty { raw.trim() }
    }

    /** Kept local so the provider can attach its own CloudStream metadata. */
    private fun parseSearchEntries(document: Document, baseUrl: String): List<SearchResponse> {
        return TorrentSupport.findLimeSearchEntries(document, baseUrl).map { entry ->
            newMovieSearchResponse(cleanTitle(entry.title), entry.url, TvType.NSFW)
        }
    }

    // Supports the older card layout as well as the current -<id>.html list layout.
    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val link = selectFirst("div.tt-name a:last-child, a[href*='-torrent-'], a[href]") ?: return null
        val title = link.text().trim()
        val href = link.attr("href").trim()
        if (title.isEmpty() || title.contains(
                "Torrent Download",
                true
            ) || !href.contains(Regex("""(?i)(-\d+\.html|\-torrent\-)"""))
        ) {
            return null
        }
        return newMovieSearchResponse(cleanTitle(title), TorrentSupport.absoluteUrl(href, baseUrl), TvType.NSFW)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        for (mirror in TorrentSupport.limeMirrors) {
            for (url in TorrentSupport.limeSearchUrls(mirror, query)) {
                val document = runCatching { app.get(url, headers = headers).document }.getOrNull() ?: continue
                val results = parseSearchEntries(document, url)
                if (results.isNotEmpty()) return results

                // Backward-compatible parsing for mirrors that still use the old tt-name layout.
                val legacyResults = document.select("table.table2 tr, tr, .tt-name")
                    .mapNotNull { it.toSearchResult(url) }
                    .distinctBy { it.url }
                if (legacyResults.isNotEmpty()) return legacyResults
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = runCatching { app.get(url, headers = headers).document }.getOrNull()
        val title = document?.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: "LimeTorrents"
        val torrent = document?.let { resolveTorrentFromDocument(it, url) }

        return newMovieLoadResponse(title, url, TvType.NSFW, torrent?.url ?: url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val direct = TorrentSupport.extractMagnet(data)
        if (direct != null) {
            emitTorrent(direct, data, TorrentSupport.originOf(mainUrl), callback)
            return true
        }
        if (data.contains(Regex("""(?i)\.torrent(?:[?#].*)?$"""))) {
            emitTorrent(data, data, TorrentSupport.originOf(data), callback)
            return true
        }

        val document = runCatching { app.get(data, headers = headers).document }.getOrNull() ?: return false
        val torrent = resolveTorrentFromDocument(document, data) ?: return false
        emitTorrent(torrent.url, document.selectFirst("h1")?.text().orEmpty(), TorrentSupport.originOf(data), callback)
        return true
    }

    /**
     * On current LimeTorrent catalogue mirrors the detail page points the
     * Magnet button at limetorrent.store. The old implementation looked at the
     * catalogue page only, so it always returned no source even though a
     * working magnet was one request away.
     */
    private suspend fun resolveTorrentFromDocument(document: Document, sourceUrl: String): TorrentLink? {
        TorrentSupport.extractTorrent(document)?.let { return it }

        for (alternateUrl in TorrentSupport.alternateTorrentDetailUrls(document, sourceUrl)) {
            val alternateDocument = runCatching {
                app.get(alternateUrl, headers = headers + ("Referer" to "$sourceUrl/")).document
            }.getOrNull() ?: continue
            TorrentSupport.extractTorrent(alternateDocument)?.let { return it }
        }
        return null
    }

    private fun emitTorrent(
        torrentUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val quality = when (TorrentSupport.qualityFromText(label.ifBlank { torrentUrl })) {
            2160 -> Qualities.P2160.value
            1440 -> Qualities.P1440.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        callback(
            ExtractorLink(
                source = name,
                name = "$name [Torrent]",
                url = torrentUrl,
                referer = referer,
                quality = quality,
                isM3u8 = false
            ).apply { type = ExtractorLinkType.TORRENT }
        )
    }
}
