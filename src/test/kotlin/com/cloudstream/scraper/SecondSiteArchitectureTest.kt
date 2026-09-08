package com.cloudstream.scraper

import com.cloudstream.scraper.cloudstream.GenericCloudStreamProvider
import com.cloudstream.scraper.cloudstream.MainPageRequest
import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.engine.GenericScraperEngine
import com.cloudstream.scraper.engine.MockScraperHttpClient
import com.cloudstream.scraper.model.CatalogType
import com.cloudstream.scraper.model.MediaType
import com.cloudstream.scraper.model.Movie
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SecondSiteArchitectureTest {

    private lateinit var searchHtml: String
    private lateinit var movieHtml: String
    private lateinit var catalogHtml: String
    private val config = ConfigLoader.loadFromResource("/sites/second-site.yaml")

    @BeforeEach
    fun setUp() {
        searchHtml = javaClass.getResourceAsStream("/fixtures/second-site/search.html")!!.bufferedReader().readText()
        movieHtml = javaClass.getResourceAsStream("/fixtures/second-site/movie.html")!!.bufferedReader().readText()
        catalogHtml = javaClass.getResourceAsStream("/fixtures/second-site/catalog.html")!!.bufferedReader().readText()
    }

    @Test
    fun `second site configuration should load and validate with zero errors`() {
        assertEquals("movieland", config.id)
        assertEquals("MovieLand", config.name)
        assertEquals("https://movieland.example.org", config.baseUrl)
        assertEquals(3, config.catalogs.size)
        assertEquals("korean", config.catalogs[2].id)
    }

    @Test
    fun `second site search should work with different selectors and URL structure`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://movieland.example.org/find?keyword=parasite&p=1" to searchHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val results = engine.search(config, "parasite", page = 1)
        assertEquals(2, results.size)

        val movie = results[0]
        assertEquals("ml-9876", movie.id)
        assertEquals("Parasite", movie.title)
        assertEquals("https://movieland.example.org/film/parasite-2019", movie.url)
        assertEquals("https://movieland.example.org/covers/parasite.jpg", movie.posterUrl)
        assertEquals(MediaType.MOVIE, movie.type)
        assertEquals(2019, movie.releaseYear)
        assertEquals(8.5, movie.rating)

        val show = results[1]
        assertEquals("ml-5432", show.id)
        assertEquals("Squid Game", show.title)
        assertEquals(MediaType.TV_SERIES, show.type)
        assertEquals(2021, show.releaseYear)
        assertEquals(8.0, show.rating)
    }

    @Test
    fun `second site movie details should extract correctly`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://movieland.example.org/film/parasite-2019" to movieHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val details = engine.load(config, "https://movieland.example.org/film/parasite-2019")
        assertNotNull(details)
        assertTrue(details is Movie)

        val movie = details as Movie
        assertEquals("Parasite", movie.title)
        assertEquals("https://movieland.example.org/covers/parasite_hd.jpg", movie.posterUrl)
        assertEquals("https://movieland.example.org/backdrops/parasite_bg.jpg", movie.backdropUrl)
        assertEquals(2019, movie.releaseYear)
        assertEquals(8.5, movie.rating)
        assertEquals(132, movie.durationMinutes)
        assertEquals(listOf("Drama", "Thriller"), movie.genres)
        assertTrue(movie.description!!.contains("Greed and class discrimination"))
    }

    @Test
    fun `second site arbitrary catalogs should work via GenericCloudStreamProvider`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://movieland.example.org/korean-collection?p=1" to catalogHtml)
        )
        val provider = GenericCloudStreamProvider(config, httpClient = mockClient)

        val homePage = provider.getMainPage(1, MainPageRequest("Korean Cinema", "korean"))
        assertNotNull(homePage)
        assertEquals(1, homePage?.items?.size)

        val list = homePage?.items?.get(0)?.list ?: emptyList()
        assertEquals(2, list.size)
        assertEquals("Oldboy", list[0].name)
        assertEquals("https://movieland.example.org/film/oldboy", list[0].url)
        assertEquals("Memories of Murder", list[1].name)
    }
}
