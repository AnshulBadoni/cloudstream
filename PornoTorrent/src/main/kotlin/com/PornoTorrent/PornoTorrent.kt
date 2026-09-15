package com.PornoTorrent

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder

class PornoTorrent : MainAPI() {
    override var mainUrl = "https://pornotorrent.com.br"
    override var name = "PornoTorrent"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/en/" to "Latest",
        "$mainUrl/en/category/evil-angel/" to "Evil Angel",
        "$mainUrl/en/category/blacked-raw/" to "Blacked Raw",
        "$mainUrl/en/category/bang/" to "BANG!",
        "$mainUrl/en/category/blacked/" to "Blacked",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val items = document.select("article, .post, div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("h2 a, h3 a, h1 a, a[title], .entry-title a") ?: return null
        val title = titleElement.attr("title").ifEmpty { titleElement.text().trim() }
        if (title.isEmpty()) return null
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("article, .post, div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "PornoTorrent"
        val poster = fixUrlNull(document.selectFirst("article img, div.entry-content img, img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        val description = document.selectFirst("div.entry-content p, div.synopsis, p")?.text()?.trim()
        val actors = document.select("a[href*='/tag/']").map { it.text().trim() }.filter { it.isNotEmpty() }

        var magnetUrl = ""
        for (a in document.select("a[href]")) {
            val href = a.attr("href")
            if (href.startsWith("magnet:")) {
                magnetUrl = href
                break
            } else if (href.contains("/download/?m=")) {
                val encoded = href.substringAfter("/download/?m=")
                magnetUrl = try { URLDecoder.decode(encoded, "UTF-8") } catch (e: Exception) { encoded }
                break
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, magnetUrl.ifEmpty { url }) {
            this.posterUrl = poster
            this.plot = description
            addActors(actors)
        }
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
            for (a in document.select("a[href]")) {
                val href = a.attr("href")
                if (href.startsWith("magnet:")) {
                    magnet = href
                    break
                } else if (href.contains("/download/?m=")) {
                    val encoded = href.substringAfter("/download/?m=")
                    magnet = try { URLDecoder.decode(encoded, "UTF-8") } catch (e: Exception) { encoded }
                    break
                }
            }
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
