package com.cloudstream.scraper.engine

import com.cloudstream.scraper.config.CatalogConfig
import com.cloudstream.scraper.config.ExtractionRule
import com.cloudstream.scraper.config.SiteConfig
import com.cloudstream.scraper.model.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GenericScraperEngine(
    private val httpClient: ScraperHttpClient = OkHttpScraperClient()
) {

    /**
     * Executes a search query on the target site using SiteConfig.
     */
    suspend fun search(
        config: SiteConfig,
        query: String,
        page: Int = 1
    ): List<SearchResult> {
        val searchConfig = config.search ?: return emptyList()

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val searchUrl = searchConfig.urlTemplate
            .replace("{query}", encodedQuery)
            .replace("{page}", page.toString())

        val fullUrl = Transformer.resolveUrl(config.baseUrl, searchUrl) ?: return emptyList()
        val response = try {
            httpClient.get(fullUrl, config.headers)
        } catch (e: Throwable) {
            return emptyList()
        }
        if (!response.isSuccessful || response.body.isBlank()) return emptyList()

        val document = HtmlParser.parse(response.body, config.baseUrl)
        val itemElements = HtmlParser.select(document, searchConfig.itemSelector)

        return itemElements.mapNotNull { element ->
            extractSearchResult(element, searchConfig.fields, config.baseUrl, defaultType = if (config.isNsfw) MediaType.NSFW else MediaType.MOVIE)
        }
    }

    /**
     * Scrapes a specific catalog section (e.g. Latest, Actors/Models, Trending, Classics) using SiteConfig.
     */
    suspend fun getCatalog(
        config: SiteConfig,
        catalog: CatalogConfig,
        page: Int = 1
    ): Catalog {
        val catalogUrl = catalog.urlTemplate.replace("{page}", page.toString())
        val fullUrl = Transformer.resolveUrl(config.baseUrl, catalogUrl) ?: catalogUrl

        val response = try {
            httpClient.get(fullUrl, config.headers)
        } catch (e: Throwable) {
            return Catalog(
                id = catalog.id,
                name = catalog.name,
                type = catalog.type,
                url = fullUrl,
                items = emptyList(),
                page = page,
                hasNextPage = false
            )
        }
        if (!response.isSuccessful || response.body.isBlank()) {
            return Catalog(
                id = catalog.id,
                name = catalog.name,
                type = catalog.type,
                url = fullUrl,
                items = emptyList(),
                page = page,
                hasNextPage = false
            )
        }

        val document = HtmlParser.parse(response.body, config.baseUrl)
        val itemSelector = catalog.itemSelector
            ?: if (catalog.type == CatalogType.PEOPLE) config.people?.itemSelector
            else config.search?.itemSelector ?: ".movie-card, .film-item, .actor-card, article"
        val itemElements = HtmlParser.select(document, itemSelector)

        val fields = when {
            catalog.fields.isNotEmpty() -> catalog.fields
            catalog.type == CatalogType.PEOPLE && config.people != null -> config.people.fields
            else -> config.search?.fields ?: emptyMap()
        }

        val defaultType = when (catalog.type) {
            CatalogType.MOVIES -> if (config.isNsfw) MediaType.NSFW else MediaType.MOVIE
            CatalogType.SERIES -> MediaType.TV_SERIES
            CatalogType.PEOPLE -> MediaType.UNKNOWN
            else -> if (config.isNsfw) MediaType.NSFW else MediaType.UNKNOWN
        }

        val items = itemElements.mapNotNull { element ->
            extractSearchResult(element, fields, config.baseUrl, defaultType = defaultType)
        }

        // Check pagination
        val hasNextPage = if (catalog.pagination?.nextSelector != null) {
            HtmlParser.select(document, catalog.pagination.nextSelector).isNotEmpty()
        } else {
            items.isNotEmpty()
        }

        return Catalog(
            id = catalog.id,
            name = catalog.name,
            type = catalog.type,
            url = fullUrl,
            items = items,
            page = page,
            hasNextPage = hasNextPage
        )
    }

    /**
     * Scrapes detailed metadata for a Movie or TV Series.
     */
    suspend fun load(
        config: SiteConfig,
        url: String
    ): MediaDetails? {
        val fullUrl = Transformer.resolveUrl(config.baseUrl, url) ?: url
        val response = try {
            httpClient.get(fullUrl, config.headers)
        } catch (e: Throwable) {
            return null
        }
        if (!response.isSuccessful || response.body.isBlank()) return null

        val html = response.body
        val document = HtmlParser.parse(html, config.baseUrl)
        val detailsConfig = config.details ?: return null

        // Autodetect Media Type
        val mediaType = if (detailsConfig.typeDetector != null && detailsConfig.typeDetector.selector.isNotBlank()) {
            val matched = HtmlParser.select(document, detailsConfig.typeDetector.selector)
            if (matched.isNotEmpty()) detailsConfig.typeDetector.existsAs else detailsConfig.typeDetector.default
        } else if (detailsConfig.series != null && HtmlParser.select(document, detailsConfig.series.episodesSelector).isNotEmpty()) {
            MediaType.TV_SERIES
        } else {
            if (config.isNsfw) MediaType.NSFW else MediaType.MOVIE
        }

        // Combine common and specific fields
        val fieldMap = mutableMapOf<String, ExtractionRule>()
        fieldMap.putAll(detailsConfig.common)
        if (mediaType == MediaType.MOVIE || mediaType == MediaType.NSFW) {
            fieldMap.putAll(detailsConfig.movie)
        }

        // 1. Extract Title with multi-layered fallbacks
        var title = RuleEvaluator.extractString(document, fieldMap["title"], config.baseUrl)
        if (title.isNullOrBlank() || title == "Unknown Title") {
            val flashTitle = Regex("""(?i)(?:video_title|title)\s*[:=]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            title = flashTitle
                ?: document.selectFirst("h1.title, h1, .headline h1, .video-details h1, .title")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title'], meta[name='twitter:title']")?.attr("content")?.trim()
                ?: document.title().substringBefore("|").substringBefore("-").trim()
        }
        if (title.isBlank()) title = "Unknown Title"
        title = title.replace(Regex("""\s*\|\s*PornTrex.*$""", RegexOption.IGNORE_CASE), "").trim()

        // 2. Extract ID
        val id = RuleEvaluator.extractString(document, fieldMap["id"], config.baseUrl)
            ?: deriveIdFromUrl(fullUrl)
        val originalTitle = RuleEvaluator.extractString(document, fieldMap["originalTitle"], config.baseUrl)

        // 3. Extract Poster
        var posterUrl = RuleEvaluator.extractString(document, fieldMap["posterUrl"], config.baseUrl)
        if (posterUrl.isNullOrBlank()) {
            val previewUrl = Regex("""(?i)(?:preview_url|poster_url|poster)\s*[:=]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            posterUrl = previewUrl
                ?: document.selectFirst("meta[property='og:image'], meta[name='twitter:image']")?.attr("content")
                ?: document.selectFirst("#player-holder video[poster], .player-holder video[poster], video[poster]")?.attr("poster")
                ?: document.selectFirst("link[rel='image_src']")?.attr("href")
        }
        posterUrl = posterUrl?.let { Transformer.resolveUrl(config.baseUrl, it) }

        val backdropUrl = RuleEvaluator.extractString(document, fieldMap["backdropUrl"], config.baseUrl)

        // 4. Extract Description / Plot
        var description = RuleEvaluator.extractString(document, fieldMap["description"], config.baseUrl)
        if (description.isNullOrBlank()) {
            description = document.selectFirst(".videodesc .items-holder, .videodesc, .description-block, .video-details .description, .description")
                ?.text()?.replace(Regex("""^Description:\s*""", RegexOption.IGNORE_CASE), "")?.trim()
                ?: document.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")?.trim()
        }

        // 5. Extract Release Year
        val releaseYear = RuleEvaluator.extractInt(document, fieldMap["releaseYear"], config.baseUrl)

        // 6. Extract Rating
        var rating = RuleEvaluator.extractDouble(document, fieldMap["rating"], config.baseUrl)
        if (rating == null) {
            val ratingText = document.selectFirst(".vote-percentage, .rating, .rate")?.text()
            if (ratingText != null) {
                rating = Regex("""(\d+)""").find(ratingText)?.groupValues?.get(1)?.toDoubleOrNull()
            }
        }

        // 7. Extract Genres & Tags
        val genres = mutableListOf<String>()
        genres.addAll(RuleEvaluator.extractList(document, fieldMap["genres"], config.baseUrl))
        if (genres.isEmpty()) {
            document.select(".block-details a[href*='/categories/'], .block-details a[href*='/tags/'], .item-categories a, .item-tags a, .tags a").forEach {
                val tagText = it.text().trim()
                if (tagText.isNotBlank() && !tagText.equals("Suggest", ignoreCase = true) && !tagText.startsWith("+")) {
                    genres.add(tagText)
                }
            }
        }
        val flashTags = Regex("""(?i)(?:video_tags|tags)\s*[:=]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
        if (!flashTags.isNullOrBlank()) {
            flashTags.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { genres.add(it) }
        }

        val tags = RuleEvaluator.extractList(document, fieldMap["tags"], config.baseUrl)
        val rawTrailerUrl = RuleEvaluator.extractString(document, fieldMap["trailerUrl"], config.baseUrl)
        val streamSources = extractStreamSources(html, config.baseUrl)
        val trailerUrl = rawTrailerUrl ?: streamSources.firstOrNull()?.url

        // 8. Extract actors & directors
        val castMembers = extractCastMembers(document, config)
        val directors = extractDirectors(document, fieldMap["directors"], config.baseUrl)

        return if (mediaType == MediaType.TV_SERIES) {
            val seriesConfig = detailsConfig.series
            val seasons = if (seriesConfig != null) {
                extractSeriesSeasons(document, seriesConfig, config.baseUrl)
            } else {
                emptyList()
            }

            Series(
                id = id,
                title = title,
                originalTitle = originalTitle,
                url = fullUrl,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                description = description,
                releaseYear = releaseYear,
                rating = rating,
                genres = genres.distinct(),
                tags = tags,
                trailerUrl = trailerUrl,
                cast = castMembers,
                directors = directors,
                type = MediaType.TV_SERIES,
                seasons = seasons
            )
        } else {
            var duration = RuleEvaluator.extractInt(document, fieldMap["duration"], config.baseUrl)
            if (duration == null) {
                val durText = document.selectFirst(".info-block .item:has(i.fa-clock-o) em, .info-block i.fa-clock-o + em, .durations, .duration, .time")?.text()
                if (durText != null) {
                    duration = Regex("""(\d+)""").find(durText)?.groupValues?.get(1)?.toIntOrNull()
                }
            }

            Movie(
                id = id,
                title = title,
                originalTitle = originalTitle,
                url = fullUrl,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                description = description,
                releaseYear = releaseYear,
                rating = rating,
                genres = genres.distinct(),
                tags = tags,
                trailerUrl = trailerUrl,
                cast = castMembers,
                directors = directors,
                type = mediaType,
                durationMinutes = duration
            )
        }
    }

    /**
     * Scrapes actor/person details page.
     */
    suspend fun getPerson(
        config: SiteConfig,
        url: String
    ): Person? {
        val fullUrl = Transformer.resolveUrl(config.baseUrl, url) ?: url
        val personConfig = config.people?.detail ?: return null

        val response = try {
            httpClient.get(fullUrl, config.headers)
        } catch (e: Throwable) {
            return null
        }
        if (!response.isSuccessful || response.body.isBlank()) return null

        val html = response.body
        val document = HtmlParser.parse(html, config.baseUrl)

        // Extract Name with fallback chain
        var name = RuleEvaluator.extractString(document, personConfig.name, config.baseUrl)
        if (name.isNullOrBlank()) {
            name = document.selectFirst(".profile-model-info h1, .profile-model-info .name h1, h1.title, h1, .headline h1")?.text()?.trim()
        }
        if (name.isNullOrBlank()) {
            name = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
        }
        if (name.isNullOrBlank()) {
            name = document.title().substringBefore("|").substringBefore("-").trim()
        }
        if (name.isBlank()) {
            name = deriveIdFromUrl(fullUrl).replace("-", " ").split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        }

        // Extract Photo
        var photoUrl = RuleEvaluator.extractString(document, personConfig.photoUrl, config.baseUrl)
        if (photoUrl.isNullOrBlank()) {
            photoUrl = document.selectFirst(".profile-model-info img, .img-holder img, .avatar img, img.thumb")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.let { Transformer.resolveUrl(config.baseUrl, it) }
        }
        if (photoUrl.isNullOrBlank()) {
            photoUrl = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.let { Transformer.resolveUrl(config.baseUrl, it) }
        }

        // Extract Biography
        var biography = RuleEvaluator.extractString(document, personConfig.biography, config.baseUrl)
        if (biography.isNullOrBlank()) {
            biography = document.selectFirst(".profile-model-info .description, .model-description, .description-block, .description, meta[property='og:description']")
                ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }?.trim()
        }

        val birthDate = RuleEvaluator.extractString(document, personConfig.birthDate, config.baseUrl)

        // Extract Known For Videos
        val knownForSelector = personConfig.knownForSelector
            ?: ".list-videos .item:has(a[href*='/videos/']), .list-videos .item:has(a.thumb), .video-preview-screen, .video-item, .item:has(a.thumb)"
        val items = HtmlParser.select(document, knownForSelector)
        val fields = if (personConfig.knownForFields.isNotEmpty()) personConfig.knownForFields else config.search?.fields ?: emptyMap()
        val knownFor = items.mapNotNull { el ->
            extractSearchResult(el, fields, config.baseUrl, defaultType = if (config.isNsfw) MediaType.NSFW else MediaType.MOVIE)
        }

        return Person(
            id = deriveIdFromUrl(fullUrl),
            name = name,
            url = fullUrl,
            photoUrl = photoUrl,
            biography = biography,
            birthDate = birthDate,
            knownFor = knownFor
        )
    }

    private fun extractSearchResult(
        element: Element,
        fields: Map<String, ExtractionRule>,
        baseUrl: String,
        defaultType: MediaType = MediaType.UNKNOWN
    ): SearchResult? {
        val rawUrl = RuleEvaluator.extractString(element, fields["url"], baseUrl)
            ?: element.selectFirst("a")?.attr("href")?.let { Transformer.resolveUrl(baseUrl, it) }
            ?: return null

        val fullUrl = Transformer.resolveUrl(baseUrl, rawUrl) ?: rawUrl
        if (defaultType == MediaType.MOVIE || defaultType == MediaType.NSFW) {
            if (fullUrl.contains("/categories/") || fullUrl.contains("/tags/") || fullUrl.contains("/channels/")) {
                return null
            }
        }
        val titleRule = fields["title"] ?: fields["name"]
        val title = RuleEvaluator.extractString(element, titleRule, baseUrl)
            ?: element.selectFirst("h1, h2, h3, h4, .title, .name, .actor-name, strong.title, a.title, a")?.text()?.trim()
            ?: return null

        if (title.isBlank()) return null

        val id = RuleEvaluator.extractString(element, fields["id"], baseUrl)
            ?: deriveIdFromUrl(fullUrl)

        val posterRule = fields["posterUrl"] ?: fields["photoUrl"]
        val posterUrl = RuleEvaluator.extractString(element, posterRule, baseUrl)
            ?: element.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.let { Transformer.resolveUrl(baseUrl, it) }

        val typeStr = RuleEvaluator.extractString(element, fields["type"], baseUrl)
        val type = when (typeStr?.uppercase()) {
            "MOVIE", "FILM" -> MediaType.MOVIE
            "NSFW", "ADULT" -> MediaType.NSFW
            "TV", "SERIES", "TV_SERIES", "SHOW" -> MediaType.TV_SERIES
            "ANIME" -> MediaType.ANIME
            else -> defaultType
        }

        val releaseYear = RuleEvaluator.extractInt(element, fields["releaseYear"], baseUrl)
        val rating = RuleEvaluator.extractDouble(element, fields["rating"], baseUrl)

        return SearchResult(
            id = id,
            title = title,
            url = fullUrl,
            posterUrl = posterUrl,
            type = type,
            releaseYear = releaseYear,
            rating = rating
        )
    }

    private fun extractCastMembers(
        document: Element,
        config: SiteConfig
    ): List<CastMember> {
        val peopleConfig = config.people ?: return emptyList()
        val itemSelector = peopleConfig.itemSelector ?: return emptyList()

        val actorElements = HtmlParser.select(document, itemSelector)
        return actorElements.mapNotNull { el ->
            val isLink = el.tagName().equals("a", ignoreCase = true)
            val name = if (isLink && el.text().isNotBlank()) {
                el.text().trim()
            } else {
                RuleEvaluator.extractString(el, peopleConfig.fields["name"], config.baseUrl)
                    ?: el.selectFirst("a, .name, .title, strong")?.text()?.trim()
                    ?: el.text().trim()
            }
            if (name.isBlank() || name.equals("Suggest", ignoreCase = true) || name.startsWith("+")) return@mapNotNull null

            val url = if (isLink && el.hasAttr("href")) {
                Transformer.resolveUrl(config.baseUrl, el.attr("href")) ?: el.attr("href")
            } else {
                RuleEvaluator.extractString(el, peopleConfig.fields["url"], config.baseUrl)
                    ?: el.selectFirst("a")?.attr("href")?.let { Transformer.resolveUrl(config.baseUrl, it) }
                    ?: ""
            }
            val photoUrl = RuleEvaluator.extractString(el, peopleConfig.fields["photoUrl"], config.baseUrl)
                ?: el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.let { Transformer.resolveUrl(config.baseUrl, it) }
            val character = RuleEvaluator.extractString(el, peopleConfig.fields["character"], config.baseUrl) ?: "Performer"

            CastMember(
                person = Person(
                    id = deriveIdFromUrl(url.ifBlank { name }),
                    name = name,
                    url = url,
                    photoUrl = photoUrl
                ),
                character = character
            )
        }
    }

    private fun extractDirectors(
        document: Element,
        rule: ExtractionRule?,
        baseUrl: String
    ): List<Person> {
        if (rule == null) return emptyList()
        val names = RuleEvaluator.extractList(document, rule, baseUrl)
        return names.map { name ->
            Person(
                id = deriveIdFromUrl(name),
                name = name,
                url = ""
            )
        }
    }

    private fun extractSeriesSeasons(
        document: Element,
        seriesConfig: com.cloudstream.scraper.config.SeriesConfig,
        baseUrl: String
    ): List<Season> {
        val seasonsMap = mutableMapOf<Int, MutableList<Episode>>()

        if (!seriesConfig.seasonsSelector.isNullOrBlank()) {
            val seasonWrappers = HtmlParser.select(document, seriesConfig.seasonsSelector)
            seasonWrappers.forEachIndexed { idx, sEl ->
                val seasonNum = seriesConfig.seasonNumber?.let { RuleEvaluator.extractInt(sEl, it, baseUrl) }
                    ?: (idx + 1)
                val epElements = HtmlParser.select(sEl, seriesConfig.episodesSelector)
                epElements.forEachIndexed { epIdx, epEl ->
                    extractEpisode(epEl, seriesConfig.episode, seasonNum, epIdx + 1, baseUrl)?.let { ep ->
                        seasonsMap.getOrPut(seasonNum) { mutableListOf() }.add(ep)
                    }
                }
            }
        } else {
            val epElements = HtmlParser.select(document, seriesConfig.episodesSelector)
            epElements.forEachIndexed { epIdx, epEl ->
                val seasonNum = seriesConfig.seasonNumber?.let { RuleEvaluator.extractInt(epEl, it, baseUrl) } ?: 1
                extractEpisode(epEl, seriesConfig.episode, seasonNum, epIdx + 1, baseUrl)?.let { ep ->
                    seasonsMap.getOrPut(seasonNum) { mutableListOf() }.add(ep)
                }
            }
        }

        return seasonsMap.map { (sNum, episodes) ->
            Season(seasonNumber = sNum, episodes = episodes)
        }.sortedBy { it.seasonNumber }
    }

    private fun extractEpisode(
        element: Element,
        fields: Map<String, ExtractionRule>,
        seasonNum: Int,
        defaultEpNum: Int,
        baseUrl: String
    ): Episode? {
        val rawUrl = RuleEvaluator.extractString(element, fields["url"], baseUrl)
            ?: element.selectFirst("a")?.attr("href")
            ?: ""
        val fullUrl = Transformer.resolveUrl(baseUrl, rawUrl) ?: rawUrl

        val title = RuleEvaluator.extractString(element, fields["title"], baseUrl)
        val epNum = RuleEvaluator.extractInt(element, fields["episodeNumber"], baseUrl) ?: defaultEpNum
        val id = RuleEvaluator.extractString(element, fields["id"], baseUrl)
            ?: deriveIdFromUrl(fullUrl.ifBlank { "ep-$seasonNum-$epNum" })
        val posterUrl = RuleEvaluator.extractString(element, fields["posterUrl"], baseUrl)
        val description = RuleEvaluator.extractString(element, fields["description"], baseUrl)
        val releaseDate = RuleEvaluator.extractString(element, fields["releaseDate"], baseUrl)

        return Episode(
            id = id,
            title = title,
            episodeNumber = epNum,
            seasonNumber = seasonNum,
            url = fullUrl,
            posterUrl = posterUrl,
            description = description,
            releaseDate = releaseDate
        )
    }

    fun extractStreamSources(html: String, baseUrl: String): List<MediaSource> {
        val sources = mutableListOf<MediaSource>()
        val seenUrls = mutableSetOf<String>()

        fun addSource(name: String, rawUrl: String, qualityStr: String? = null) {
            if (rawUrl.isBlank()) return
            val unescaped = rawUrl.replace("\\/", "/").trim()
            val resolvedUrl = Transformer.resolveUrl(baseUrl, unescaped) ?: unescaped
            if (seenUrls.contains(resolvedUrl)) return
            seenUrls.add(resolvedUrl)

            val qualityInt = qualityStr?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: Regex("""(\d{3,4})p?""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d{3,4})p?""").find(resolvedUrl)?.groupValues?.get(1)?.toIntOrNull()

            val label = if (qualityInt != null) "${qualityInt}p" else name

            sources.add(
                MediaSource(
                    name = label,
                    url = resolvedUrl,
                    quality = qualityInt,
                    isM3u8 = resolvedUrl.contains(".m3u8", ignoreCase = true)
                )
            )
        }

        // 1. KVS Flashvars patterns (covers video_url, video_alt_url, video_alt_url2..4, hls_url with : or = or [])
        val kvsPattern = Regex("""(?i)(?:['"]?(?:video_url|video_alt_url\d*|hls_url)['"]?|flashvars\[['"](?:video_url|video_alt_url\d*|hls_url)['"]\]|flashvars\.(?:video_url|video_alt_url\d*|hls_url))\s*[:=]\s*['"]([^'"]+)['"]""")
        val kvsTextPattern = Regex("""(?i)(?:['"]?(?:video_url_text|video_alt_url\d*_text)['"]?|flashvars\[['"](?:video_url_text|video_alt_url\d*_text)['"]\]|flashvars\.(?:video_url_text|video_alt_url\d*_text))\s*[:=]\s*['"]([^'"]+)['"]""")

        val urlMatches = kvsPattern.findAll(html).map { it.groupValues[1] }.toList()
        val textMatches = kvsTextPattern.findAll(html).map { it.groupValues[1] }.toList()

        urlMatches.forEachIndexed { index, url ->
            val qualityText = textMatches.getOrNull(index) ?: "Stream ${index + 1}"
            addSource(qualityText, url, qualityText)
        }

        // 2. Direct HLS / M3U8 URLs in scripts
        val m3u8Regex = Regex("""(?i)['"](https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?)['"]""")
        m3u8Regex.findAll(html).forEach { match ->
            addSource("HLS Stream", match.groupValues[1])
        }

        // 3. Direct MP4 URLs in scripts (including /get_file/ links)
        val mp4Regex = Regex("""(?i)['"]((?:https?://|/)[^\s"'<>]+\.mp4(?:\?[^\s"'<>]*)?)['"]""")
        mp4Regex.findAll(html).forEach { match ->
            addSource("Direct MP4", match.groupValues[1])
        }

        // 4. Check HTML5 <video> and <source> tags
        val doc = HtmlParser.parse(html, baseUrl)
        doc.select("video source, video[src], a[href*='.mp4'], a[href*='.m3u8']").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }.ifBlank { el.attr("href") }
            if (src.isNotBlank()) {
                val label = el.attr("title").ifBlank { el.attr("label") }.ifBlank { "Direct Video" }
                addSource(label, src)
            }
        }

        return sources
    }

    private fun deriveIdFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast('/')
    }
}
