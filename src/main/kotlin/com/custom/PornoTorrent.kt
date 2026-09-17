package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PornoTorrent : MainAPI() {
    override var mainUrl = "https://pornotorrent.com.br"
    override var name = "PornoTorrent"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/en/" to "Latest",
        "$mainUrl/en/category/evil-angel/" to "Evil Angel",
        "$mainUrl/en/category/blacked-raw/" to "Blacked Raw",
        "$mainUrl/en/category/bang/" to "BANG!",
        "$mainUrl/en/category/blacked/" to "Blacked",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = runCatching { app.get(url, headers = headers).document }.getOrNull()
        val items = document?.select("article, .post, div.item, .cp-card")
            ?.mapNotNull { it.toSearchResult() }
            ?.distinctBy { it.url }
            .orEmpty()
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""&#?\w+;"""), " ")
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
        val titleElement = selectFirst("h2 a, h3 a, h1 a, a[title], .entry-title a, .cp-card__title a, a.cp-card__link") ?: return null
        val title = titleElement.attr("title").ifEmpty { titleElement.attr("aria-label") }.ifEmpty { titleElement.text().trim() }
        if (title.isEmpty()) return null
        val href = TorrentSupport.absoluteUrl(titleElement.attr("href"), mainUrl)
        val posterUrl = selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: selectFirst(".cp-card__cover, [style*='background']")?.attr("style")?.let { style ->
            Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
        }
        return newMovieSearchResponse(cleanTitle(title), href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = headers
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val numMatch = Regex("""\b(\d+)\b""").find(query)?.groupValues?.get(1)
        val baseName = query.replace(Regex("""(?i)\b(?:Vol\.?|Volume|Episode|Ep\.?|Part|No\.?|#)?\s*\d+"""), "")
            .replace(Regex("""[-:_/]+"""), " ")
            .trim()

        val queries = mutableListOf<String>()
        if (numMatch != null && baseName.isNotEmpty()) {
            queries.add("$baseName #$numMatch")
            queries.add("$baseName $numMatch")
        }
        queries.add(query)
        if (baseName.isNotEmpty() && !queries.contains(baseName)) queries.add(baseName)

        for (q in queries.distinct()) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            // The English catalogue owns the result cards. Keep the root route
            // as a fallback for installations that are redirected there.
            val urls = listOf("$mainUrl/en/?s=$encoded", "$mainUrl/?s=$encoded")
            for (url in urls) {
                val document = runCatching { app.get(url, headers = headers).document }.getOrNull() ?: continue
                val items = document.select("article, .post, div.item, .cp-card")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
                if (items.isNotEmpty()) return items
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = runCatching { app.get(url, headers = headers).document }.getOrNull()
        val title = document?.selectFirst("h1.entry-title, h1")?.text()?.trim()?.ifBlank { null } ?: "PornoTorrent"
        val poster = document?.selectFirst("article img, div.entry-content img, img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: document?.selectFirst(".cp-card__cover, [style*='background']")?.attr("style")?.let { style ->
            Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
        }
        val description = document?.selectFirst("div.entry-content p, div.synopsis, p")?.text()?.trim()
        val actors = document?.select("a[href*='/tag/']")?.map {
            ActorData(Actor(it.text().trim(), null))
        }?.filter { it.actor.name.isNotEmpty() }.orEmpty()
        val torrent = document?.let { resolveTorrentFromDocument(it, url) }

        return newMovieLoadResponse(title, url, TvType.NSFW, torrent?.url ?: url) {
            this.posterUrl = poster
            this.posterHeaders = headers
            this.plot = description
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directMagnet = TorrentSupport.extractMagnet(data)
        val directTorrent = directMagnet?.let { TorrentLink(it, isMagnet = true) }
            ?: data.takeIf { it.contains(Regex("""(?i)\.torrent(?:[?#].*)?$""")) }?.let { TorrentLink(it, isMagnet = false) }
        val torrent = directTorrent ?: run {
            val document = runCatching { app.get(data, headers = headers).document }.getOrNull() ?: return false
            resolveTorrentFromDocument(document, data) ?: return false
        }

        emitTorrent(torrent.url, data, callback)
        return true
    }

    private suspend fun resolveTorrentFromDocument(document: Document, sourceUrl: String): TorrentLink? {
        TorrentSupport.extractTorrent(document)?.let { return it }
        // Some releases expose their URL-encoded magnet from a secondary
        // download page, just like the current LimeTorrent catalogue does.
        for (alternateUrl in TorrentSupport.alternateTorrentDetailUrls(document, sourceUrl)) {
            val alternateDocument = runCatching {
                app.get(alternateUrl, headers = headers + ("Referer" to sourceUrl)).document
            }.getOrNull() ?: continue
            TorrentSupport.extractTorrent(alternateDocument)?.let { return it }
        }
        return null
    }

    private fun emitTorrent(torrentUrl: String, label: String, callback: (ExtractorLink) -> Unit) {
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
                referer = "$mainUrl/",
                quality = quality,
                isM3u8 = false
            ).apply { type = ExtractorLinkType.TORRENT }
        )
    }
}
