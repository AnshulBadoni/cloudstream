package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FiveMoviesPornTest {
    private val provider = FiveMoviesPorn()

    @Test
    fun testCatalogs() = runBlocking {
        println("=== 1. TESTING 5MOVIESPORN MAIN PAGE CATALOGS ===")
        val home = provider.getMainPage(1, MainPageRequest("Latest", "${provider.mainUrl}/"))
        val items = home.items.firstOrNull()?.list.orEmpty()
        println("Latest count: ${items.size}")
        assertTrue(items.isNotEmpty(), "Catalog should not be empty")
        items.take(3).forEach {
            println("  - ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }
    }

    @Test
    fun testSearch() = runBlocking {
        println("=== 2. TESTING 5MOVIESPORN SEARCH ===")
        val results = provider.search("Meant to Fuck")
        println("Search results count: ${results.size}")
        assertTrue(results.isNotEmpty(), "Search for 'Meant to Fuck' should find results")
        results.take(3).forEach {
            println("  - Found: ${it.name} -> ${it.url}")
        }
    }

    @Test
    fun testLoad() = runBlocking {
        println("=== 3. TESTING 5MOVIESPORN LOAD ===")
        val url = "https://www.5moviesporn.io/meant-to-fuck/"
        val res = provider.load(url)
        assertNotNull(res, "Load response should not be null")
        println("  Title: ${res.name}, Plot length: ${res.plot?.length}, Tags: ${res.tags?.size}")
        assertTrue(res.name.isNotEmpty(), "Title should not be empty")
    }

    @Test
    fun testLoadLinks() = runBlocking {
        println("=== 4. TESTING 5MOVIESPORN LOAD LINKS ===")
        val url = "https://www.5moviesporn.io/meant-to-fuck/"
        val extractedLinks = mutableListOf<ExtractorLink>()
        val success = provider.loadLinks(url, isCasting = false, subtitleCallback = {}) { link ->
            println("  >>> Extracted Link: [${link.name}] ${link.url}")
            extractedLinks.add(link)
        }
        println("Load links success: $success, Total extracted: ${extractedLinks.size}")
    }
}
