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
        val response = httpClient.get(fullUrl, config.headers)
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

        val response = httpClient.get(fullUrl, config.headers)
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
        val response = httpClient.get(fullUrl, config.headers)
        if (!response.isSuccessful || response.body.isBlank()) return null

        val document = HtmlParser.parse(response.body, config.baseUrl)
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

        val title = RuleEvaluator.extractString(document, fieldMap["title"], config.baseUrl)
            ?: document.title().ifBlank { "Unknown Title" }
        val id = RuleEvaluator.extractString(document, fieldMap["id"], config.baseUrl)
            ?: deriveIdFromUrl(fullUrl)
        val originalTitle = RuleEvaluator.extractString(document, fieldMap["originalTitle"], config.baseUrl)
        val posterUrl = RuleEvaluator.extractString(document, fieldMap["posterUrl"], config.baseUrl)
        val backdropUrl = RuleEvaluator.extractString(document, fieldMap["backdropUrl"], config.baseUrl)
        val description = RuleEvaluator.extractString(document, fieldMap["description"], config.baseUrl)
        val releaseYear = RuleEvaluator.extractInt(document, fieldMap["releaseYear"], config.baseUrl)
        val rating = RuleEvaluator.extractDouble(document, fieldMap["rating"], config.baseUrl)
        val genres = RuleEvaluator.extractList(document, fieldMap["genres"], config.baseUrl)
        val tags = RuleEvaluator.extractList(document, fieldMap["tags"], config.baseUrl)
        val rawTrailerUrl = RuleEvaluator.extractString(document, fieldMap["trailerUrl"], config.baseUrl)
        val streamSources = extractStreamSources(response.body, config.baseUrl)
        val trailerUrl = rawTrailerUrl ?: streamSources.lastOrNull()?.url ?: streamSources.firstOrNull()?.url

        // Extract actors & directors if people rules are configured
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
                genres = genres,
                tags = tags,
                trailerUrl = trailerUrl,
                cast = castMembers,
                directors = directors,
                type = MediaType.TV_SERIES,
                seasons = seasons
            )
        } else {
            val duration = RuleEvaluator.extractInt(document, fieldMap["duration"], config.baseUrl)

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
                genres = genres,
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

        val response = httpClient.get(fullUrl, config.headers)
        if (!response.isSuccessful || response.body.isBlank()) return null

        val document = HtmlParser.parse(response.body, config.baseUrl)
        val name = RuleEvaluator.extractString(document, personConfig.name, config.baseUrl)
            ?: document.title()
        val photoUrl = RuleEvaluator.extractString(document, personConfig.photoUrl, config.baseUrl)
        val biography = RuleEvaluator.extractString(document, personConfig.biography, config.baseUrl)
        val birthDate = RuleEvaluator.extractString(document, personConfig.birthDate, config.baseUrl)

        val knownFor = if (!personConfig.knownForSelector.isNullOrBlank()) {
            val items = HtmlParser.select(document, personConfig.knownForSelector)
            items.mapNotNull { el ->
                extractSearchResult(el, personConfig.knownForFields, config.baseUrl)
            }
        } else {
            emptyList()
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
        val titleRule = fields["title"] ?: fields["name"]
        val title = RuleEvaluator.extractString(element, titleRule, baseUrl)
            ?: element.selectFirst("h1, h2, h3, h4, .title, .name, .actor-name, a")?.text()?.trim()
            ?: return null

        if (title.isBlank()) return null

        val id = RuleEvaluator.extractString(element, fields["id"], baseUrl)
            ?: deriveIdFromUrl(fullUrl)

        val posterRule = fields["posterUrl"] ?: fields["photoUrl"]
        val posterUrl = RuleEvaluator.extractString(element, posterRule, baseUrl)
            ?: element.selectFirst("img")?.attr("src")?.let { Transformer.resolveUrl(baseUrl, it) }

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
            val name = RuleEvaluator.extractString(el, peopleConfig.fields["name"], config.baseUrl)
                ?: el.text().trim()
            if (name.isBlank()) return@mapNotNull null

            val url = RuleEvaluator.extractString(el, peopleConfig.fields["url"], config.baseUrl)
                ?: el.selectFirst("a")?.attr("href")?.let { Transformer.resolveUrl(config.baseUrl, it) }
                ?: ""
            val photoUrl = RuleEvaluator.extractString(el, peopleConfig.fields["photoUrl"], config.baseUrl)
                ?: el.selectFirst("img")?.attr("src")?.let { Transformer.resolveUrl(config.baseUrl, it) }
            val character = RuleEvaluator.extractString(el, peopleConfig.fields["character"], config.baseUrl)

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

        // 1. Check video_url, video_alt_url, video_alt_url2, etc. in script blocks
        val videoUrlRegex = Regex("""(?:video_url|video_alt_url\d*)\s*:\s*['"]([^'"]+)['"]""")
        val textRegex = Regex("""(?:video_url_text|video_alt_url\d*_text)\s*:\s*['"]([^'"]+)['"]""")

        val urlMatches = videoUrlRegex.findAll(html).map { it.groupValues[1] }.toList()
        val textMatches = textRegex.findAll(html).map { it.groupValues[1] }.toList()

        urlMatches.forEachIndexed { index, url ->
            val resolvedUrl = Transformer.resolveUrl(baseUrl, url) ?: url
            val qualityText = textMatches.getOrNull(index) ?: "Default"
            val qualityInt = Regex("""(\d+)""").find(qualityText)?.groupValues?.get(1)?.toIntOrNull()
            sources.add(
                MediaSource(
                    name = qualityText,
                    url = resolvedUrl,
                    quality = qualityInt,
                    isM3u8 = resolvedUrl.contains(".m3u8")
                )
            )
        }

        // 2. Check <video><source src="..." /> tags
        if (sources.isEmpty()) {
            val doc = HtmlParser.parse(html, baseUrl)
            doc.select("video source, video[src]").forEach { el ->
                val src = el.attr("src").ifBlank { el.attr("data-src") }
                if (src.isNotBlank()) {
                    val resolved = Transformer.resolveUrl(baseUrl, src) ?: src
                    sources.add(
                        MediaSource(
                            name = "Direct MP4",
                            url = resolved,
                            isM3u8 = resolved.contains(".m3u8")
                        )
                    )
                }
            }
        }

        return sources
    }

    private fun deriveIdFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast('/')
    }
}
