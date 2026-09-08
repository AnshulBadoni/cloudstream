package com.cloudstream.scraper.cloudstream

import com.cloudstream.scraper.adapters.AdapterRegistry
import com.cloudstream.scraper.config.CatalogConfig
import com.cloudstream.scraper.config.SiteConfig
import com.cloudstream.scraper.engine.GenericScraperEngine
import com.cloudstream.scraper.engine.OkHttpScraperClient
import com.cloudstream.scraper.engine.ScraperHttpClient
import com.cloudstream.scraper.engine.Transformer
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Production Generic CloudStream Provider powered by declarative SiteConfig.
 *
 * Adding a new website requires ONLY a new YAML configuration and registers dynamically.
 */
open class GenericCloudStreamProvider(
    private val config: SiteConfig,
    private val httpClient: ScraperHttpClient = OkHttpScraperClient(config.headers),
    private val engine: GenericScraperEngine = GenericScraperEngine(httpClient)
) : MainAPI() {

    override var mainUrl: String = config.baseUrl
    override var name: String = config.name
    override val isNsfw: Boolean = config.isNsfw
    override val supportedTypes: Set<TvType> = if (config.isNsfw) setOf(TvType.NSFW) else setOf(TvType.Movie, TvType.TvSeries)
    override var lang: String = "en"
    override val hasMainPage: Boolean = config.catalogs.isNotEmpty()

    override val mainPage: List<MainPageData> = config.catalogs.map { catalog ->
        MainPageData(name = catalog.name, data = catalog.id)
    }

    private val adapter = AdapterRegistry.getAdapter(config)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogConfig = config.catalogs.find { it.id == request.data }
            ?: return null

        val customCatalog = adapter.onCustomGetCatalog(config, catalogConfig, page, httpClient)
        val catalog = customCatalog ?: engine.getCatalog(config, catalogConfig, page)

        val searchResponses = catalog.items.map { CloudStreamMapper.toSearchResponse(it, name) }
        val homePageList = HomePageList(
            name = catalog.name,
            list = searchResponses,
            isHorizontalImages = request.horizontalImages
        )

        return HomePageResponse(
            items = listOf(homePageList),
            hasNext = catalog.hasNextPage
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val customResults = adapter.onCustomSearch(config, query, page = 1, httpClient)
        val results = customResults ?: engine.search(config, query, page = 1)
        return results.map { CloudStreamMapper.toSearchResponse(it, name) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = Transformer.resolveUrl(config.baseUrl, url) ?: url
        val customDetails = adapter.onCustomLoad(config, fullUrl, httpClient)
        if (customDetails != null) {
            return CloudStreamMapper.toLoadResponse(customDetails, name)
        }

        // 1. If URL matches actor/model pattern, fetch person filmography
        val isPersonUrl = fullUrl.contains("/models/") || fullUrl.contains("/pornstars/") || fullUrl.contains("/actor/") || fullUrl.contains("/person/") || fullUrl.contains("/model/")
        if (isPersonUrl && config.people?.detail != null) {
            val person = engine.getPerson(config, fullUrl)
            if (person != null && (person.name.isNotBlank() || person.knownFor.isNotEmpty())) {
                return CloudStreamMapper.toLoadResponse(person, name)
            }
        }

        // 2. Otherwise load video / movie / series details
        val details = engine.load(config, fullUrl)
        if (details != null) {
            return CloudStreamMapper.toLoadResponse(details, name)
        }

        // 3. Fallback: try getPerson if load returned null
        if (config.people?.detail != null) {
            val person = engine.getPerson(config, fullUrl)
            if (person != null) {
                return CloudStreamMapper.toLoadResponse(person, name)
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullDataUrl = Transformer.resolveUrl(config.baseUrl, data) ?: data
        val customSources = adapter.onCustomLoadLinks(config, fullDataUrl, httpClient)
        if (customSources != null) {
            customSources.forEach { source ->
                callback(CloudStreamMapper.toExtractorLink(source, name))
                source.subtitles.forEach { sub ->
                    subtitleCallback(CloudStreamMapper.toSubtitleFile(sub))
                }
            }
            return true
        }

        val response = httpClient.get(fullDataUrl, config.headers)
        if (response.isSuccessful && response.body.isNotBlank()) {
            val sources = engine.extractStreamSources(response.body, config.baseUrl)
            sources.forEach { source ->
                callback(CloudStreamMapper.toExtractorLink(source, name))
            }
            return sources.isNotEmpty()
        }

        return false
    }
}
