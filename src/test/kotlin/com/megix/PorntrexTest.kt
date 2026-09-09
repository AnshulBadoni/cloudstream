package com.megix

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PorntrexTest {

    private val provider = Porntrex()

    @Test
    fun testPorntrexMetadata() {
        assertEquals("PornTrex", provider.name)
        assertEquals("https://www.porntrex.com", provider.mainUrl)
        assertTrue(provider.hasMainPage)
        assertTrue(provider.mainPage.isNotEmpty())
        assertTrue(provider.mainPage.any { it.name == "Models & Stars" || it.data == "models" })
    }

    @Test
    fun testHomePageCatalogs() = runBlocking {
        println("\n=== 1. TESTING MAIN PAGE CATALOGS ===")
        for (page in provider.mainPage.take(4)) {
            println("Fetching: ${page.name} (${page.data})...")
            try {
                val response = provider.getMainPage(1, MainPageRequest(page.name, page.data, false))
                val items = response.items.firstOrNull()?.list.orEmpty()
                println("  ✓ ${page.name}: Found ${items.size} items")
                items.take(2).forEach {
                    println("    - [${it.type}] ${it.name} -> ${it.url}")
                }
            } catch (e: Exception) {
                println("  ✗ Failed ${page.name}: ${e.message}")
            }
        }
    }

    @Test
    fun testSearch() = runBlocking {
        println("\n=== 2. TESTING SEARCH ===")
        val query = "blake"
        try {
            val results = provider.search(query)
            println("Search results for '$query': ${results.size} found")
            results.take(2).forEach {
                println("  - ${it.name} -> ${it.url}")
            }
        } catch (e: Exception) {
            println("Search error: ${e.message}")
        }
    }

    @Test
    fun testVideoLoadAndStreamExtraction() = runBlocking {
        println("\n=== 3. TESTING VIDEO LOAD & STREAM LINKS ===")
        try {
            val mainPage = provider.getMainPage(1, MainPageRequest("Latest", "latest-updates", false))
            val firstVideo = mainPage.items.firstOrNull()?.list?.firstOrNull()
            if (firstVideo != null) {
                println("Loading video: ${firstVideo.name} (${firstVideo.url})")
                val details = provider.load(firstVideo.url) as? MovieLoadResponse
                if (details != null) {
                    println("  Title: ${details.name}")
                    println("  Poster: ${details.posterUrl}")
                    println("  Tags: ${details.tags?.take(5)}")
                    println("  Recommendations: ${details.recommendations?.size} items")

                    println("\nExtracting stream links...")
                    provider.loadLinks(details.dataUrl ?: details.url, isCasting = false, subtitleCallback = {}) { link ->
                        println("  ✓ Found Stream: [Quality: ${link.quality}] ${link.name} -> ${link.url}")
                    }
                }
            }
        } catch (e: Exception) {
            println("Video load error: ${e.message}")
        }
    }

    @Test
    fun testModelProfile() = runBlocking {
        println("\n=== 4. TESTING MODEL PROFILE ===")
        try {
            val modelsPage = provider.getMainPage(1, MainPageRequest("Models & Stars", "models", false))
            val firstModel = modelsPage.items.firstOrNull()?.list?.firstOrNull()
            if (firstModel != null) {
                println("Loading model: ${firstModel.name} (${firstModel.url})")
                val modelDetails = provider.load(firstModel.url) as? MovieLoadResponse
                if (modelDetails != null) {
                    println("  Name: ${modelDetails.name}")
                    println("  Bio: ${modelDetails.plot}")
                    println("  Filmography: ${modelDetails.recommendations?.size} videos found")
                }
            }
        } catch (e: Exception) {
            println("Model load error: ${e.message}")
        }
    }
}

