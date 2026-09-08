package com.cloudstream.scraper

import com.cloudstream.scraper.cloudstream.*
import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.engine.MockScraperHttpClient
import com.cloudstream.scraper.model.*
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CloudStreamMapperTest {

    @Test
    fun `toSearchResponse should map Movie and TV search results to CloudStream classes`() {
        val movieResult = SearchResult(
            id = "movie-1",
            title = "Test Movie",
            url = "https://example.com/movie/1",
            posterUrl = "https://example.com/poster.jpg",
            type = MediaType.MOVIE,
            releaseYear = 2023,
            rating = 8.5
        )
        val response = CloudStreamMapper.toSearchResponse(movieResult, "TestProvider")
        assertTrue(response is MovieSearchResponse)
        assertEquals("Test Movie", response.name)
        assertEquals("https://example.com/movie/1", response.url)
        assertEquals("TestProvider", response.apiName)
        assertEquals(TvType.Movie, response.type)

        val seriesResult = SearchResult(
            id = "series-1",
            title = "Test Series",
            url = "https://example.com/tv/1",
            type = MediaType.TV_SERIES
        )
        val seriesResponse = CloudStreamMapper.toSearchResponse(seriesResult, "TestProvider")
        assertTrue(seriesResponse is TvSeriesSearchResponse)
        assertEquals(TvType.TvSeries, seriesResponse.type)
    }

    @Test
    fun `toLoadResponse should map Movie and Series details into LoadResponse`() {
        val movie = Movie(
            id = "m1",
            title = "Inception",
            url = "https://example.com/movie/inception",
            posterUrl = "https://example.com/poster.jpg",
            releaseYear = 2010,
            rating = 8.8,
            genres = listOf("Sci-Fi", "Action"),
            durationMinutes = 148,
            cast = listOf(
                CastMember(
                    person = Person(id = "p1", name = "Leonardo DiCaprio", url = ""),
                    character = "Cobb"
                )
            )
        )
        val loadResponse = CloudStreamMapper.toLoadResponse(movie, "TestProvider")
        assertTrue(loadResponse is TvSeriesLoadResponse)
        assertEquals("Inception", loadResponse.name)
        assertEquals(148, (loadResponse as TvSeriesLoadResponse).duration)
        assertEquals(88, loadResponse.rating)
        assertEquals(1, loadResponse.actors?.size)
        assertEquals("Leonardo DiCaprio", loadResponse.actors?.get(0)?.actor?.name)
        assertEquals("Cobb", loadResponse.actors?.get(0)?.roleString)
        assertEquals(1, loadResponse.episodes.size)
    }

    @Test
    fun `GenericCloudStreamProvider end-to-end flow with MockScraperHttpClient`() = runBlocking {
        val searchHtml = javaClass.getResourceAsStream("/fixtures/first-site/search.html")!!.bufferedReader().readText()
        val movieHtml = javaClass.getResourceAsStream("/fixtures/first-site/movie.html")!!.bufferedReader().readText()
        val catalogHtml = javaClass.getResourceAsStream("/fixtures/first-site/catalog.html")!!.bufferedReader().readText()

        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf(
                "https://trexton.com/search?q=dark&page=1" to searchHtml,
                "https://trexton.com/movie/the-dark-knight" to movieHtml,
                "https://trexton.com/latest?page=1" to catalogHtml
            )
        )

        val config = ConfigLoader.loadFromResource("/test_sites/trexton.yaml")
        val provider = GenericCloudStreamProvider(config, httpClient = mockClient)

        // 1. Check provider metadata
        assertEquals("Trexton", provider.name)
        assertEquals("https://trexton.com", provider.mainUrl)
        assertTrue(provider.hasMainPage)
        assertEquals(4, provider.mainPage.size)

        // 2. Search
        val searchResults = provider.search("dark")
        assertEquals(2, searchResults.size)
        assertEquals("The Dark Knight", searchResults[0].name)

        // 3. Load
        val loadResult = provider.load("https://trexton.com/movie/the-dark-knight")
        assertNotNull(loadResult)
        assertEquals("The Dark Knight", loadResult?.name)
        assertTrue(loadResult is TvSeriesLoadResponse)

        // 4. Main Page (Catalog)
        val homePage = provider.getMainPage(1, MainPageRequest("Latest Movies", "latest"))
        assertNotNull(homePage)
        assertEquals(1, homePage?.items?.size)
        assertEquals("Latest Movies", homePage?.items?.get(0)?.name)
        assertEquals(2, homePage?.items?.get(0)?.list?.size)
    }

    @Test
    fun `toLoadResponse should map Person profile and filmography into LoadResponse`() {
        val person = Person(
            id = "p1",
            name = "Angela White",
            url = "https://example.com/models/angela-white",
            photoUrl = "https://example.com/photo.jpg",
            biography = "Star performer",
            knownFor = listOf(
                SearchResult(
                    id = "v1",
                    title = "Scene 1",
                    url = "https://example.com/video/1",
                    posterUrl = "https://example.com/poster1.jpg",
                    type = MediaType.NSFW
                ),
                SearchResult(
                    id = "v2",
                    title = "Scene 2",
                    url = "https://example.com/video/2",
                    posterUrl = "https://example.com/poster2.jpg",
                    type = MediaType.NSFW
                )
            )
        )
        val loadResponse = CloudStreamMapper.toLoadResponse(person, "TestProvider")
        assertTrue(loadResponse is TvSeriesLoadResponse)
        assertEquals("Angela White", loadResponse.name)
        assertEquals("https://example.com/models/angela-white", loadResponse.url)
        assertEquals("https://example.com/photo.jpg", loadResponse.posterUrl)
        assertEquals("Star performer", loadResponse.plot)
        assertEquals(2, (loadResponse as TvSeriesLoadResponse).episodes.size)
        assertEquals(2, loadResponse.recommendations?.size)
        assertEquals("Scene 1", loadResponse.recommendations?.get(0)?.name)
        assertEquals("Scene 2", loadResponse.recommendations?.get(1)?.name)
    }

    @Test
    fun `GenericCloudStreamProvider should load actor filmography on actor URL`() = runBlocking {
        val actorHtml = javaClass.getResourceAsStream("/fixtures/first-site/actor.html")!!.bufferedReader().readText()
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://trexton.com/actor/christian-bale" to actorHtml)
        )
        val config = ConfigLoader.loadFromResource("/test_sites/trexton.yaml")
        val provider = GenericCloudStreamProvider(config, httpClient = mockClient)

        val loadResult = provider.load("https://trexton.com/actor/christian-bale")
        assertNotNull(loadResult)
        assertEquals("Christian Bale", loadResult?.name)
        assertEquals("https://trexton.com/actor/christian-bale", loadResult?.url)
        assertEquals(2, loadResult?.recommendations?.size)
        assertEquals("The Dark Knight", loadResult?.recommendations?.get(0)?.name)
        assertEquals("American Psycho", loadResult?.recommendations?.get(1)?.name)
    }
}
