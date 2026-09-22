package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Dedicated Provider for XTapes (ww3.xtapes.tw / xtapes.to)
 * Fast, clean, standalone provider with direct MP4/M3U8 extraction and embed support.
 */
class XTapes : MainAPI() {
    override var mainUrl = "https://ww3.xtapes.tw"
    override var name = "XTapes"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    companion object {
        var searchPages: Int = 2
        var catalogPages: Int = 2
    }

    // 1. HOME PAGE CATALOG DEFINITIONS
    override val mainPage = mainPageOf(
        "latest-updates" to "Latest Videos",
        "most-popular" to "Most Popular",
        "top-rated" to "Top Rated",
        "categories/movies" to "Full Movies",
        "category/hd" to "HD 1080p",
        "category/vr" to "VR 4K"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = if (page <= 1) {
            "$mainUrl/$path/"
        } else {
            if (path.contains("?")) "$mainUrl/$path&page=$page" else "$mainUrl/$path/page/$page/"
        }

        val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            ?: runCatching {
                val altUrl = url.replace("https://ww3.xtapes.tw", "https://xtapes.to")
                app.get(altUrl, headers = defaultHeaders).document
            }.getOrNull()

        val items = doc?.select("div.item, div.video-item, article.post, div.thumb, .item-video, .video-thumb")
            ?.mapNotNull { parseVideoCard(it) }
            ?.distinctBy { it.url }
            .orEmpty()

        val hasNextPage = items.size >= 12
        return newHomePageResponse(HomePageList(request.name, items), hasNextPage)
    }

    // 2. SEARCH
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cleanQuery = query.trim()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val slugQuery = cleanQuery.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        val searchUrls = listOf(
            "$mainUrl/search/$encodedQuery/",
            "$mainUrl/search/videos/$encodedQuery/",
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/search/$slugQuery/"
        ).distinct()

        val results = mutableListOf<SearchResponse>()

        for (u in searchUrls) {
            for (p in 1..searchPages.coerceIn(1, 5)) {
                val pageUrl = if (p <= 1) u else {
                    if (u.contains("?")) "$u&page=$p" else "${u.trimEnd('/')}/page/$p/"
                }
                val doc = runCatching { app.get(pageUrl, headers = defaultHeaders).document }.getOrNull() ?: break
                val items = doc.select("div.item, div.video-item, article.post, div.thumb, .item-video, .video-thumb, a[href*='/video/']")
                    .mapNotNull { parseVideoCard(it) }
                if (items.isEmpty()) break
                results.addAll(items)
            }
            if (results.isNotEmpty()) break
        }

        results.distinctBy { it.url }
    }

    // 3. LOAD DETAILS
    override suspend fun load(url: String): LoadResponse {
        val doc = runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            ?: runCatching {
                val altUrl = url.replace("https://ww3.xtapes.tw", "https://xtapes.to")
                app.get(altUrl, headers = defaultHeaders).document
            }.getOrNull()

        val rawTitle = doc?.selectFirst("h1.entry-title, h1, .video-title, .title, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val title = if (rawTitle.isNullOrBlank() || rawTitle.equals("Page Not Found", ignoreCase = true) || rawTitle.contains("404", ignoreCase = true)) {
            val s = url.trimEnd('/').substringAfterLast('/')
            s.replace(Regex("^[0-9]+-"), "").replace("-", " ").split(" ")
                .filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                .ifBlank { "XTapes Video" }
        } else {
            rawTitle
        }

        val poster = doc?.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc?.selectFirst("video[poster]")?.attr("poster")
            ?: extractImg(doc?.selectFirst(".player-holder img, .video-player img, article img, img"))

        val plot = doc?.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc?.selectFirst("div.entry-content, .description, .synopsis, p")?.text()?.trim()

        val duration = doc?.selectFirst(".duration, .time, .badge-duration")?.text()?.let {
            parseDuration(it)
        }

        val tags = doc?.select("a[href*='/category/'], a[href*='/tag/'], a[href*='/categories/']")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()

        val actors = doc?.select("a[href*='/model/'], a[href*='/actor/'], a[href*='/pornstar/'], a[href*='/models/']")
            ?.map { ActorData(Actor(it.text().trim(), null)) }
            ?.filter { it.actor.name.isNotBlank() }
            ?.distinctBy { it.actor.name }

        val recommendations = doc?.select("div.item, div.video-item, .related-videos .item, .item-video")
            ?.mapNotNull { parseVideoCard(it) }
            ?.filter { it.url != url }
            ?.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
            this.plot = plot
            this.duration = duration
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    // 4. STREAM EXTRACTION (DIRECT MP4, M3U8, EMBED HOSTS)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        var count = 0
        val doc = runCatching { app.get(data, headers = defaultHeaders).document }.getOrNull()
            ?: runCatching {
                val altUrl = data.replace("https://ww3.xtapes.tw", "https://xtapes.to")
                app.get(altUrl, headers = defaultHeaders).document
            }.getOrNull()

        val rawHtml = doc?.html().orEmpty()

        // 1) Direct HTML5 video / source tags
        val directSources = mutableSetOf<String>()
        doc?.select("video source, source[src], video[src]")?.forEach {
            val src = it.attr("src").ifBlank { null } ?: it.attr("data-src").ifBlank { null }
            if (!src.isNullOrBlank()) directSources.add(fixUrl(src, mainUrl))
        }

        // 2) PlayerJS / Script variables (file: "...", sources: [...], video_url: "...")
        Regex("""(?i)(?:file|source|src|video_url|videoUrl|hls)\s*:\s*["'](https?:[^"']+|/[^"']+)["']""").findAll(rawHtml).forEach {
            val src = it.groupValues[1].replace("\\/", "/")
            if (src.contains(".mp4") || src.contains(".m3u8") || src.contains("/get_file/")) {
                directSources.add(fixUrl(src, mainUrl))
            }
        }

        // 3) General direct media sweeps
        Regex("""(?i)(?:https?:\\/\\/|https?://)[^\s"'<>\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'<>\\]*)?""").findAll(rawHtml).forEach {
            val clean = it.value.replace("\\/", "/")
            if (!clean.endsWith(".jpg") && !clean.endsWith(".png") && !clean.endsWith(".webp") && !clean.endsWith(".gif")) {
                directSources.add(clean)
            }
        }

        // Emit direct streams
        for (sUrl in directSources.distinct()) {
            val isM3u8 = sUrl.contains(".m3u8", ignoreCase = true)
            val qLabel = when {
                sUrl.contains("2160") || sUrl.contains("4k", ignoreCase = true) -> "4K"
                sUrl.contains("1080") -> "1080p"
                sUrl.contains("720") -> "720p"
                sUrl.contains("480") -> "480p"
                sUrl.contains("360") -> "360p"
                else -> "1080p"
            }

            callback(
                ExtractorLink(
                    source = name,
                    name = "$name $qLabel Direct",
                    url = sUrl,
                    referer = "$mainUrl/",
                    quality = getQualityFromName(qLabel),
                    isM3u8 = isM3u8,
                    headers = defaultHeaders
                )
            )
            count++
        }

        // 4) Embedded iframes (LuluStream, Streamtape, Doodstream, Mixdrop, VOE, etc.)
        val iframes = doc?.select("iframe[src], embed[src], iframe[data-src]")?.mapNotNull {
            val src = it.attr("src").ifBlank { null } ?: it.attr("data-src").ifBlank { null }
            src?.let { s -> fixUrl(s, mainUrl) }
        }?.filter { u ->
            u.startsWith("http") && !u.contains("google") && !u.contains("facebook") && !u.contains("twitter")
        }?.distinct().orEmpty()

        if (iframes.isNotEmpty()) {
            val jobs = iframes.map { iframeUrl ->
                async {
                    try {
                        loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)
                    } catch (_: Exception) {
                    }
                }
            }
            jobs.awaitAll()
        }

        count > 0 || iframes.isNotEmpty()
    }

    // 5. HELPER PARSERS
    private fun parseVideoCard(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*='/video/'], a[href*='/videos/'], a[href*='/watch/'], a") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/category/") || href.contains("/tag/") || href.contains("/channels/")) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        var title = imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: element.selectFirst(".title, h2, h3, .name, .video-title")?.text()?.trim()

        if (title.isNullOrBlank() || title.equals("Page Not Found", ignoreCase = true) || title.contains("404", ignoreCase = true)) {
            val slug = href.trimEnd('/').substringAfterLast('/')
            title = slug.replace(Regex("^[0-9]+-"), "").replace("-", " ").split(" ")
                .filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        if (title.isBlank()) return null

        val poster = extractImg(imgEl)

        return newMovieSearchResponse(title, fixUrl(href, mainUrl), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster, mainUrl)
            this.posterHeaders = defaultHeaders
        }
    }

    private fun extractImg(element: Element?): String? {
        if (element == null) return null
        val raw = element.attr("data-src").ifBlank { null }
            ?: element.attr("data-original").ifBlank { null }
            ?: element.attr("data-thumb").ifBlank { null }
            ?: element.attr("data-lazy-src").ifBlank { null }
            ?: element.attr("data-image").ifBlank { null }
            ?: element.attr("content").ifBlank { null }
            ?: element.attr("src").ifBlank { null }
        return if (raw != null && !raw.startsWith("data:image")) raw else null
    }

    private fun parseDuration(text: String): Int? {
        val clean = text.trim()
        val parts = clean.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1]
            2 -> parts[0]
            1 -> parts[0]
            else -> null
        }
    }

    private fun fixUrl(url: String, base: String = mainUrl): String {
        val cleanUrl = url.replace("\\/", "/")
        return when {
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> cleanUrl
            cleanUrl.startsWith("//") -> "https:$cleanUrl"
            cleanUrl.startsWith("/") -> base.trimEnd('/') + cleanUrl
            else -> base.trimEnd('/') + "/" + cleanUrl
        }
    }

    private fun fixUrlNull(url: String?, base: String = mainUrl): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url, base)
    }
}
