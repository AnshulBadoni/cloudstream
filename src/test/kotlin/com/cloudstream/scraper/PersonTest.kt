package com.cloudstream.scraper

import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.engine.GenericScraperEngine
import com.cloudstream.scraper.engine.MockScraperHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PersonTest {

    private lateinit var actorHtml: String
    private val config = ConfigLoader.loadFromResource("/test_sites/trexton.yaml")

    @BeforeEach
    fun setUp() {
        actorHtml = javaClass.getResourceAsStream("/fixtures/first-site/actor.html")!!.bufferedReader().readText()
    }

    @Test
    fun `getPerson should extract person profile and known-for list`() = runBlocking {
        val mockClient = MockScraperHttpClient(
            fixtureMap = mapOf("https://trexton.com/actor/christian-bale" to actorHtml)
        )
        val engine = GenericScraperEngine(mockClient)

        val person = engine.getPerson(config, "https://trexton.com/actor/christian-bale")
        assertNotNull(person)

        assertEquals("Christian Bale", person?.name)
        assertEquals("https://trexton.com/actor/christian-bale", person?.url)
        assertEquals("https://trexton.com/actors/bale_profile.jpg", person?.photoUrl)
        assertTrue(person?.biography!!.contains("versatility and physical transformations"))
        assertEquals("1974-01-30", person?.birthDate)

        // Known For items
        assertEquals(2, person?.knownFor?.size)
        val firstCredit = person?.knownFor?.get(0)
        assertEquals("The Dark Knight", firstCredit?.title)
        assertEquals("https://trexton.com/movie/the-dark-knight", firstCredit?.url)
        assertEquals("https://trexton.com/img/dark_knight.jpg", firstCredit?.posterUrl)
        assertEquals(2008, firstCredit?.releaseYear)

        val secondCredit = person?.knownFor?.get(1)
        assertEquals("American Psycho", secondCredit?.title)
        assertEquals(2000, secondCredit?.releaseYear)
    }
}
