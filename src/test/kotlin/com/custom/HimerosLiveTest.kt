package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HimerosLiveTest {
    private val himeros = Himeros()

    @Test
    fun testCatalogs() = runBlocking {
        println("=== 1. TESTING SPEEDPORN MAIN PAGE CATALOGS ===")
        val home = himeros.getMainPage(1, MainPageRequest("HD Movies", "https://speedporn.net/hdmovies/"))
        val items = home.items.firstOrNull()?.list.orEmpty()
        println("HD Movies count: ${items.size}")
        assertTrue(items.isNotEmpty(), "Catalog should not be empty")
        items.take(3).forEach {
            println("  - ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }
    }

    @Test
    fun testSearchNormalization() = runBlocking {
        println("=== 2. TESTING SEARCH NORMALIZATION ===")
        val results = himeros.search("Level Up Vol. 4")
        println("Search results for 'Level Up Vol. 4': ${results.size}")
        assertTrue(results.isNotEmpty(), "Search for 'Level Up Vol. 4' should find matches")
        results.take(3).forEach {
            println("  - Found: ${it.name} -> ${it.url}")
        }
    }

    @Test
    fun testLoadLinksMeantToFuck() = runBlocking {
        println("=== 3. TESTING LOAD LINKS FOR MEANT TO FUCK ===")
        val extractedLinks = mutableListOf<ExtractorLink>()
        val success = himeros.loadLinks("https://speedporn.net/meant-to-fuck/", isCasting = false, subtitleCallback = {}) { link ->
            println(">>> Extracted: [${link.name}] ${link.url}")
            extractedLinks.add(link)
        }
        println("Total extracted links: ${extractedLinks.size}")
        assertTrue(extractedLinks.isNotEmpty(), "Should extract at least one stream for Meant to Fuck")
    }

    @Test
    fun testLoadLinksStars8Diagnostics() = runBlocking {
        println("=== 4. TESTING LOAD LINKS DIAGNOSTICS FOR STARS 8 ===")
        val extractedLinks = mutableListOf<ExtractorLink>()
        val success = himeros.loadLinks("https://speedporn.net/stars-8-3/", isCasting = false, subtitleCallback = {}) { link ->
            extractedLinks.add(link)
        }

        val sources = extractedLinks.groupingBy { it.source }.eachCount()
        val names = extractedLinks.groupingBy { it.name.substringBefore(" [").substringBefore(" Direct") }.eachCount()
        println("loadLinks returned: $success")
        println("Total extracted links: ${extractedLinks.size}")
        println("Sources: $sources")
        println("Names: $names")

        assertTrue(extractedLinks.isNotEmpty(), "Should extract at least one stream for Stars 8")
    }
}
