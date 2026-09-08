package com.cloudstream.scraper

import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.engine.GenericScraperEngine
import com.cloudstream.scraper.engine.MockScraperHttpClient
import com.cloudstream.scraper.model.MediaType
import com.cloudstream.scraper.model.Movie
import com.cloudstream.scraper.model.Series
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ScraperEngineTest {

    private lateinit var searchHtml: String
    private lateinit var movieHtml: String
    private lateinit var seriesHtml: String
    private lateinit var malformedHtml: String
    private val config = ConfigLoader.loadFromResource("/test_sites/trexton.yaml")

    @BeforeEach
    fun setUp() {
        searchHtml = javaClass.getResourceAsStream("/fixtures/first-site/search.html")!!.bufferedReader().readText()
        movieHtml = javaClass.getResourceAsStream("/fixtures/first-site/movie.html")!!.bufferedReader().readText()
        seriesHtml = javaClass.getResourceAsStream("/fixtures/first-site/series.html")!!.bufferedReader().readText()
        malformedHtml = javaClass.getResourceAsStream("/fixtures/first-site/malformed.html")!!.bufferedReader().readText()
    }

    @Test
    fun `search should extract movies and series results accurately`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://trexton.com/search?q=dark&page=1" to searchHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val results = engine.search(config, "dark", page = 1)
        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("The Dark Knight", first.title)
        assertEquals("https://trexton.com/movie/the-dark-knight", first.url)
        assertEquals("https://trexton.com/img/dark_knight.jpg", first.posterUrl)
        assertEquals(MediaType.MOVIE, first.type)
        assertEquals(2008, first.releaseYear)
        assertEquals(9.0, first.rating)

        val second = results[1]
        assertEquals("Breaking Bad", second.title)
        assertEquals("https://trexton.com/tv/breaking-bad", second.url)
        assertEquals(MediaType.TV_SERIES, second.type)
        assertEquals(2008, second.releaseYear)
        assertEquals(9.5, second.rating)
    }

    @Test
    fun `load should extract full movie details with cast, trailer, and tags`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://trexton.com/movie/the-dark-knight" to movieHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val details = engine.load(config, "https://trexton.com/movie/the-dark-knight")
        assertNotNull(details)
        assertTrue(details is Movie)

        val movie = details as Movie
        assertEquals("The Dark Knight", movie.title)
        assertEquals("The Dark Knight Original", movie.originalTitle)
        assertEquals("https://trexton.com/img/dark_knight_large.jpg", movie.posterUrl)
        assertEquals("https://trexton.com/img/dark_knight_backdrop.jpg", movie.backdropUrl)
        assertEquals(2008, movie.releaseYear)
        assertEquals(9.0, movie.rating)
        assertEquals(152, movie.durationMinutes)
        assertEquals(listOf("Action", "Crime", "Drama"), movie.genres)
        assertEquals(listOf("DC", "Superhero"), movie.tags)
        assertEquals("https://www.youtube.com/embed/EXeTwQWrcwY", movie.trailerUrl)
        assertTrue(movie.description!!.contains("Joker wreaks havoc"))

        // Cast
        assertEquals(2, movie.cast.size)
        assertEquals("Christian Bale", movie.cast[0].person.name)
        assertEquals("Bruce Wayne / Batman", movie.cast[0].character)
        assertEquals("Heath Ledger", movie.cast[1].person.name)
        assertEquals("Joker", movie.cast[1].character)
    }

    @Test
    fun `load should extract series with season and episode hierarchy`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://trexton.com/tv/breaking-bad" to seriesHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val details = engine.load(config, "https://trexton.com/tv/breaking-bad")
        assertNotNull(details)
        assertTrue(details is Series)

        val series = details as Series
        assertEquals("Breaking Bad", series.title)
        assertEquals(MediaType.TV_SERIES, series.type)
        assertEquals(2, series.seasons.size)

        // Season 1
        val season1 = series.seasons[0]
        assertEquals(1, season1.seasonNumber)
        assertEquals(2, season1.episodes.size)
        assertEquals("Pilot", season1.episodes[0].title)
        assertEquals(1, season1.episodes[0].episodeNumber)
        assertEquals("bb-s1e1", season1.episodes[0].id)
        assertEquals("https://trexton.com/tv/breaking-bad/s1e1", season1.episodes[0].url)
        assertEquals("2008-01-20", season1.episodes[0].releaseDate)

        // Season 2
        val season2 = series.seasons[1]
        assertEquals(2, season2.seasonNumber)
        assertEquals(1, season2.episodes.size)
        assertEquals("Seven Thirty-Seven", season2.episodes[0].title)
        assertEquals(1, season2.episodes[0].episodeNumber)
    }

    @Test
    fun `load should handle malformed HTML resiliently without throwing exceptions`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://trexton.com/broken" to malformedHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val details = engine.load(config, "https://trexton.com/broken")
        assertNotNull(details)
        assertEquals("Broken Movie Entry", details?.title)
        assertEquals(null, details?.releaseYear) // NotAYear should be safely discarded as null
        assertEquals(null, details?.rating)
    }
}
