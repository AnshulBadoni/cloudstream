package com.custom

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParadiseHillTest {
    private val provider = ParadiseHill()

    @Test
    fun testCatalogs() = runBlocking {
        println("=== 1. TESTING PARADISEHILL CATALOGS ===")
        val home = provider.getMainPage(1, MainPageRequest("Popular Movies", "popular/?filter=all&sort=by_likes"))
        val items = home.items.firstOrNull()?.list.orEmpty()
        println("Popular Movies count: ${items.size}")
        assertTrue(items.isNotEmpty(), "Popular movies catalog should not be empty")
        items.take(3).forEach {
            println("  - ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }
    }

    @Test
    fun testSearch() = runBlocking {
        println("=== 2. TESTING PARADISEHILL SEARCH ===")
        val results = provider.search("Angela White")
        println("Search results for 'Angela White': ${results.size}")
        assertTrue(results.isNotEmpty(), "Search for 'Angela White' should find results")
        results.take(3).forEach {
            println("  - [${it.type}] ${it.name} -> ${it.url}")
        }
    }

    @Test
    fun testMovieLoadAndLinks() = runBlocking {
        println("=== 3. TESTING PARADISEHILL MOVIE LOAD & STREAM EXTRACTION ===")
        // Fetch a popular movie to test
        val home = provider.getMainPage(1, MainPageRequest("Popular Movies", "popular/?filter=all&sort=by_likes"))
        val firstMovie = home.items.firstOrNull()?.list?.firstOrNull()
        if (firstMovie != null) {
            println("Testing Movie: ${firstMovie.name} (${firstMovie.url})")
            val loadRes = provider.load(firstMovie.url)
            println("Loaded Title: ${loadRes.name}, Type: ${loadRes.type}")

            val extractedLinks = mutableListOf<ExtractorLink>()
            val success = provider.loadLinks(firstMovie.url, isCasting = false, subtitleCallback = {}) { link ->
                println(">>> Stream: [${link.name}] ${link.url}")
                extractedLinks.add(link)
            }
            println("Extracted Links: ${extractedLinks.size}, success: $success")
            assertTrue(extractedLinks.isNotEmpty(), "Should extract direct MP4 stream")
        }
    }
}
