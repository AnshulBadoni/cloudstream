package com.cloudstream.scraper.adapters

import com.cloudstream.scraper.config.CatalogConfig
import com.cloudstream.scraper.config.SiteConfig
import com.cloudstream.scraper.engine.ScraperHttpClient
import com.cloudstream.scraper.model.*

/**
 * Escape-hatch interface for websites with non-standard flows (e.g. anti-bot token unpacking,
 * dynamic cryptographic signatures, bespoke API encodings) that cannot be expressed via YAML rules.
 *
 * All methods return null by default, which instructs the engine to use the generic scraper implementation.
 */
interface SiteAdapter {
    fun matches(config: SiteConfig): Boolean = false

    suspend fun onCustomSearch(
        config: SiteConfig,
        query: String,
        page: Int,
        client: ScraperHttpClient
    ): List<SearchResult>? = null

    suspend fun onCustomGetCatalog(
        config: SiteConfig,
        catalog: CatalogConfig,
        page: Int,
        client: ScraperHttpClient
    ): Catalog? = null

    suspend fun onCustomLoad(
        config: SiteConfig,
        url: String,
        client: ScraperHttpClient
    ): MediaDetails? = null

    suspend fun onCustomGetPerson(
        config: SiteConfig,
        url: String,
        client: ScraperHttpClient
    ): Person? = null

    suspend fun onCustomLoadLinks(
        config: SiteConfig,
        url: String,
        client: ScraperHttpClient
    ): List<MediaSource>? = null
}

object DefaultSiteAdapter : SiteAdapter
