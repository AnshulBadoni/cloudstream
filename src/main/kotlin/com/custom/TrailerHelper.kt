package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

object TrailerHelper {
    const val PORNSTAR_SCENES_URL = "https://pornstar-scenes.com"
    const val PORNPICS_URL = "https://www.pornpics.de"

    val psHeaders = mapOf(
        "referer" to "$PORNSTAR_SCENES_URL/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    val pornpicsHeaders = mapOf(
        "referer" to "$PORNPICS_URL/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    val popularStudios = listOf(
        "Vixen" to "vixen",
        "Blacked" to "blacked",
        "Brazzers" to "brazzers",
        "Tushy" to "tushy",
        "Deeper" to "deeper",
        "Reality Kings" to "reality-kings",
        "Naughty America" to "naughty-america",
        "Pure Taboo" to "pure-taboo",
        "Slayed" to "slayed",
        "Mofos" to "mofos",
        "Evil Angel" to "evil-angel",
        "Wicked" to "wicked",
        "Twistys" to "twistys",
        "Babes" to "babes",
        "FakeHub" to "fakehub",
        "PropertySex" to "propertysex"
    )

    suspend fun fetchStudioTrailerM3u8(studioName: String): String? {
        val cleanName = studioName.trim().replace("-", " ")
        if (cleanName.isBlank()) return null
        return runCatching {
            val encoded = URLEncoder.encode(cleanName, "UTF-8").replace("+", "%20")
            val showcaseUrl = "$PORNSTAR_SCENES_URL/showcase/$encoded/"
            val html = app.get(showcaseUrl, headers = psHeaders).text
            val sceneMatch = Regex("""href=['"](/video/[^'"]+/i\d+/)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""href=['"](https?://[^'"]*pornstar-scenes\.com/video/[^'"]+/i\d+/)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""href=['"](/video/[^'"]+)['"]""").find(html)?.groupValues?.get(1)

            if (!sceneMatch.isNullOrBlank()) {
                val sceneUrl = if (sceneMatch.startsWith("http")) sceneMatch else "$PORNSTAR_SCENES_URL$sceneMatch"
                val sceneHtml = app.get(sceneUrl, headers = psHeaders).text
                val m3u8Match = Regex("""https?://[^\'"\s<>]+\.m3u8[^\'"\s<>]*""").find(sceneHtml)?.value
                if (!m3u8Match.isNullOrBlank()) {
                    return@runCatching m3u8Match
                }
            }
            null
        }.getOrNull()
    }

    suspend fun fetchModelTrailerM3u8(modelName: String): String? {
        val cleanName = modelName.trim().replace("-", " ")
        if (cleanName.isBlank()) return null
        return runCatching {
            val encoded = URLEncoder.encode(cleanName, "UTF-8").replace("+", "%20")
            val modelUrl = "$PORNSTAR_SCENES_URL/model/$encoded/AllScenes/"
            val html = app.get(modelUrl, headers = psHeaders).text
            val sceneMatch = Regex("""href=['"](/video/[^'"]+/i\d+/)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""href=['"](https?://[^'"]*pornstar-scenes\.com/video/[^'"]+/i\d+/)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""href=['"](/video/[^'"]+)['"]""").find(html)?.groupValues?.get(1)

            if (!sceneMatch.isNullOrBlank()) {
                val sceneUrl = if (sceneMatch.startsWith("http")) sceneMatch else "$PORNSTAR_SCENES_URL$sceneMatch"
                val sceneHtml = app.get(sceneUrl, headers = psHeaders).text
                val m3u8Match = Regex("""https?://[^\'"\s<>]+\.m3u8[^\'"\s<>]*""").find(sceneHtml)?.value
                if (!m3u8Match.isNullOrBlank()) {
                    return@runCatching m3u8Match
                }
            }
            null
        }.getOrNull()
    }

    suspend fun fetchPornPicsStudioLogo(studioSlug: String): String? {
        val cleanSlug = studioSlug.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (cleanSlug.isBlank()) return null
        return runCatching {
            val doc = app.get("$PORNPICS_URL/channels/$cleanSlug/", headers = pornpicsHeaders).document
            val avatarEl = doc.selectFirst("div.entity-card-avatar img, .entity-card-avatar img, img[src*='hfma.pornpics.de'], img[data-src*='hfma.pornpics.de'], .channel-avatar img, .channel-logo img")
            val avatarRaw = avatarEl?.attr("src")?.ifBlank { null } ?: avatarEl?.attr("data-src")?.ifBlank { null }
            if (!avatarRaw.isNullOrBlank() && !avatarRaw.endsWith(".svg")) {
                return@runCatching if (avatarRaw.startsWith("http")) avatarRaw else "$PORNPICS_URL$avatarRaw"
            }

            val imgEl = doc.select("li.thumb img, div.thumb-holder img, img.thumb_image, img[data-src*='cdni.pornpics.de'], img[src*='cdni.pornpics.de']")
                .firstOrNull { el ->
                    val s = el.attr("data-src").ifBlank { el.attr("src") }
                    s.isNotBlank() && !s.endsWith(".svg") && !s.contains("logo")
                }
            val raw = imgEl?.attr("data-src")?.ifBlank { null } ?: imgEl?.attr("src")?.ifBlank { null }
            if (raw != null && !raw.endsWith(".svg") && !raw.contains("logo")) {
                if (raw.startsWith("http")) raw else "$PORNPICS_URL$raw"
            } else null
        }.getOrNull()
    }

    suspend fun fetchPornPicsTrendingActors(page: Int = 1, targetBaseUrl: String, pathPrefix: String): List<SearchResponse> {
        val ppUrl = if (page <= 1) {
            "$PORNPICS_URL/pornstars/?gender=female&orientation=straight&s=trending"
        } else {
            "$PORNPICS_URL/pornstars/?gender=female&orientation=straight&s=trending&page=$page"
        }
        return runCatching {
            val doc = app.get(ppUrl, headers = pornpicsHeaders).document
            doc.select("li.thumb-block, li:has(a[href*='/pornstars/']), div.thumb-holder").mapNotNull {
                parsePornPicsActorCard(it, targetBaseUrl, pathPrefix)
            }
        }.getOrDefault(emptyList())
    }

    fun parsePornPicsActorCard(element: Element, targetBaseUrl: String, pathPrefix: String): SearchResponse? {
        val linkEl = element.selectFirst("a[href*='/pornstars/']") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/list/")) return null

        val slug = href.trimEnd('/').substringAfterLast('/')
        val name = element.selectFirst(".name, span.title, .title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        val imgEl = element.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("data-original")?.ifBlank { null }
            ?: imgEl?.attr("src")?.ifBlank { null }

        val cleanPrefix = pathPrefix.trim('/')
        val fullUrl = "${targetBaseUrl.trimEnd('/')}/$cleanPrefix/$slug"

        return TvSeriesSearchResponse(
            name = name,
            url = fullUrl,
            apiName = "PornPics",
            type = TvType.TvSeries,
            posterUrl = if (poster != null && poster.startsWith("http")) poster else if (poster != null) "$PORNPICS_URL$poster" else null,
            posterHeaders = pornpicsHeaders
        )
    }

    fun createTrailerEpisode(m3u8Url: String, title: String = "🎬 Trailer / Preview", posterUrl: String? = null): Episode {
        return Episode(
            data = "trailer:$m3u8Url",
            name = title,
            season = 1,
            episode = 0,
            posterUrl = posterUrl
        )
    }

    fun handleTrailerStream(data: String, providerName: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.startsWith("trailer:") || data.contains("pornstar-scenes.com") || data.endsWith(".m3u8")) {
            val m3u8Url = data.removePrefix("trailer:").trim()
            if (m3u8Url.isNotBlank() && m3u8Url.startsWith("http")) {
                callback(
                    ExtractorLink(
                        source = providerName,
                        name = "$providerName Trailer (720p HLS)",
                        url = m3u8Url,
                        referer = "$PORNSTAR_SCENES_URL/",
                        quality = Qualities.P720.value,
                        isM3u8 = true,
                        headers = mapOf(
                            "Referer" to "$PORNSTAR_SCENES_URL/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    )
                )
                return true
            }
        }
        return false
    }
}
