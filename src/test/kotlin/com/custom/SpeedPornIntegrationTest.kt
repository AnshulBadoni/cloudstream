package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpeedPornIntegrationTest {
    private val scraper = CustomScraper()

    @Test
    fun testSpeedPornHomeCatalogs() = runBlocking {
        println("=== 1. TESTING SPEEDPORN MAIN PAGE CATALOGS ===")
        val latest = scraper.getMainPage(1, MainPageRequest("SpeedPorn - Latest", "sp_latest"))
        val latestList = latest.items.firstOrNull()?.list.orEmpty()
        println("SpeedPorn Latest count: ${latestList.size}")
        assertTrue(latestList.isNotEmpty(), "SpeedPorn latest catalog should not be empty")
        latestList.take(3).forEach {
            println("  - [SpeedPorn Latest] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }

        val movies = scraper.getMainPage(1, MainPageRequest("SpeedPorn - Full Movies", "sp_movies"))
        val moviesList = movies.items.firstOrNull()?.list.orEmpty()
        println("SpeedPorn Movies count: ${moviesList.size}")
        assertTrue(moviesList.isNotEmpty(), "SpeedPorn movies catalog should not be empty")
        moviesList.take(3).forEach {
            println("  - [SpeedPorn Movie] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }
    }

    @Test
    fun testUnifiedSearchWithSpeedPorn() = runBlocking {
        println("=== 2. TESTING UNIFIED SEARCH WITH SPEEDPORN ===")
        val results = scraper.search("pirates")
        println("Total search results for 'pirates': ${results.size}")
        assertTrue(results.isNotEmpty(), "Search results should not be empty")

        val phResults = results.filter { it.url.contains("paradisehill.cc") }
        val spResults = results.filter { it.url.contains("speedporn.net") }
        val ptResults = results.filter { it.url.contains("porntrex.com") }

        println("  -> ParadiseHill items: ${phResults.size}")
        println("  -> SpeedPorn items:    ${spResults.size}")
        println("  -> PornTrex items:     ${ptResults.size}")

        assertTrue(spResults.isNotEmpty(), "SpeedPorn should return search results for 'pirates'")
        spResults.take(3).forEach {
            println("     [SpeedPorn Result] ${it.name} -> ${it.url}")
        }
    }

    @Test
    fun testSpeedPornLoadAndLinkExtraction() = runBlocking {
        println("=== 3. TESTING SPEEDPORN LOAD & LINKS ===")
        val sampleUrl = "https://speedporn.net/the-sex-pirates/"
        val loadResponse = scraper.load(sampleUrl)
        assertNotNull(loadResponse, "Load response should not be null")
        println("Loaded Title: ${loadResponse?.name}")
        println("Loaded Poster: ${loadResponse?.posterUrl}")
        assertTrue(loadResponse?.name?.isNotBlank() == true, "Title should not be blank")

        val extractedLinks = mutableListOf<ExtractorLink>()
        val success = scraper.loadLinks(sampleUrl, isCasting = false, subtitleCallback = {}) { link ->
            extractedLinks.add(link)
            println("  -> Emitted ExtractorLink: [${link.source}] ${link.name} -> ${link.url}")
        }

        println("SpeedPorn link extraction success: $success (Count: ${extractedLinks.size})")
        assertTrue(extractedLinks.isNotEmpty() || success, "Should extract links from SpeedPorn entry")
    }
}
