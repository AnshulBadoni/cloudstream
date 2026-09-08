package com.cloudstream.scraper

import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.engine.GenericScraperEngine
import com.cloudstream.scraper.engine.MockScraperHttpClient
import com.cloudstream.scraper.model.CatalogType
import com.cloudstream.scraper.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CatalogTest {

    private lateinit var catalogHtml: String
    private lateinit var actorsCatalogHtml: String
    private val config = ConfigLoader.loadFromResource("/test_sites/trexton.yaml")

    @BeforeEach
    fun setUp() {
        catalogHtml = javaClass.getResourceAsStream("/fixtures/first-site/catalog.html")!!.bufferedReader().readText()
        actorsCatalogHtml = javaClass.getResourceAsStream("/fixtures/first-site/actors_catalog.html")!!.bufferedReader().readText()
    }

    @Test
    fun `actors panel should be explicitly configured as the second catalog`() {
        assertTrue(config.catalogs.size >= 2)
        val secondCatalog = config.catalogs[1]
        assertEquals("actors", secondCatalog.id)
        assertEquals("Actors & Performers", secondCatalog.name)
        assertEquals(CatalogType.PEOPLE, secondCatalog.type)
        assertEquals("/actors?page={page}", secondCatalog.urlTemplate)
    }

    @Test
    fun `getCatalog should extract actor items from the second catalog panel`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://trexton.com/actors?page=1" to actorsCatalogHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val actorsCatalogConfig = config.catalogs[1] // 2nd catalog
        val catalog = engine.getCatalog(config, actorsCatalogConfig, page = 1)

        assertEquals("actors", catalog.id)
        assertEquals("Actors & Performers", catalog.name)
        assertEquals(CatalogType.PEOPLE, catalog.type)
        assertEquals(2, catalog.items.size)

        val firstActor = catalog.items[0]
        assertEquals("Christian Bale", firstActor.title)
        assertEquals("https://trexton.com/actor/christian-bale", firstActor.url)
        assertEquals("https://trexton.com/actors/bale.jpg", firstActor.posterUrl)

        val secondActor = catalog.items[1]
        assertEquals("Heath Ledger", secondActor.title)
        assertEquals("https://trexton.com/actor/heath-ledger", secondActor.url)
    }

    @Test
    fun `getCatalog should extract movies items and detect next page`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://trexton.com/latest?page=1" to catalogHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val latestCatalogConfig = config.catalogs.first { it.id == "latest" }
        val catalog = engine.getCatalog(config, latestCatalogConfig, page = 1)

        assertEquals("latest", catalog.id)
        assertEquals("Latest Movies", catalog.name)
        assertEquals(1, catalog.page)
        assertTrue(catalog.hasNextPage)
        assertEquals(2, catalog.items.size)

        val firstItem = catalog.items[0]
        assertEquals("Oppenheimer", firstItem.title)
        assertEquals("https://trexton.com/movie/oppenheimer", firstItem.url)
        assertEquals("https://trexton.com/img/oppenheimer.jpg", firstItem.posterUrl)
        assertEquals(2023, firstItem.releaseYear)
        assertEquals(8.9, firstItem.rating)

        val secondItem = catalog.items[1]
        assertEquals("Barbie", secondItem.title)
        assertEquals(2023, secondItem.releaseYear)
        assertEquals(7.0, secondItem.rating)
    }
}
