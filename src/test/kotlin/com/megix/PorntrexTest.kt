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
        val query = System.getProperty("query")?.takeIf { it.isNotBlank() } ?: "blake"
        println("\n=== 2. TESTING SEARCH (Query: '$query') ===")
        try {
            val results = provider.search(query)
            println("Search results for '$query': ${results.size} found")
            results.take(5).forEach {
                println("  - [${it.type}] ${it.name} -> ${it.url}")
            }
        } catch (e: Exception) {
            println("Search error: ${e.message}")
        }
    }

    @Test
    fun testVideoLoadAndStreamExtraction() = runBlocking {
        println("\n=== 3. TESTING VIDEO LOAD & STREAM LINKS ===")
        try {
            val targetUrl = System.getProperty("url")?.takeIf { it.isNotBlank() }
            val videoUrl = if (targetUrl != null) {
                targetUrl
            } else {
                val mainPage = provider.getMainPage(1, MainPageRequest("Latest", "latest-updates", false))
                mainPage.items.firstOrNull()?.list?.firstOrNull()?.url
            }

            if (videoUrl != null) {
                println("Loading video URL: $videoUrl")
                val details = provider.load(videoUrl) as? MovieLoadResponse
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
            } else {
                println("No video found to test.")
            }
        } catch (e: Exception) {
            println("Video load error: ${e.message}")
        }
    }

    @Test
    fun testModelProfile() = runBlocking {
        println("\n=== 4. TESTING MODEL PROFILE ===")
        try {
            val targetUrl = System.getProperty("url")?.takeIf { it.isNotBlank() }
            val modelUrl = if (targetUrl != null) {
                targetUrl
            } else {
                val modelsPage = provider.getMainPage(1, MainPageRequest("Models & Stars", "models", false))
                modelsPage.items.firstOrNull()?.list?.firstOrNull()?.url
            }

            if (modelUrl != null) {
                println("Loading model URL: $modelUrl")
                val response = provider.load(modelUrl)
                if (response is TvSeriesLoadResponse) {
                    println("  ✓ TV Series Model Name: ${response.name}")
                    println("  ✓ Bio: ${response.plot}")
                    println("  ✓ Episodes count: ${response.episodes.size} episodes")
                    response.episodes.take(3).forEachIndexed { i, ep ->
                        println("    - Episode ${i + 1}: ${ep.name} [Duration: ${ep.description}] -> ${ep.data}")
                    }
                } else if (response != null) {
                    println("  Loaded as: ${response::class.simpleName} (${response.name})")
                }
            } else {
                println("No model found to test.")
            }
        } catch (e: Exception) {
            println("Model load error: ${e.message}")
        }
    }

    @Test
    fun testModelProfileOffline() {
        println("\n=== 5. TESTING MODEL PROFILE OFFLINE HTML ===")
        val html = java.io.File("model_sample.html").readText()
        val document = org.jsoup.Jsoup.parse(html, "https://www.porntrex.com/models/blake-blossom/")
        
        val name = document.selectFirst("h1, .profile-model-info h1, .profile-model-info .name h1, h1.title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
            ?: "Blake Blossom"
        println("Extracted Model Name: $name")

        val videoElements = document.select(
            "#list_videos_model_videos_items .item, #list_videos_common_videos_list_items .item, .list-videos .item, div.video-list div.video-item, div.video-preview-screen, .item:has(a[href*='/video/']), .item:has(a[href*='/videos/'])"
        )
        println("Matched video elements: ${videoElements.size}")
        val episodes = videoElements.mapIndexedNotNull { index, element ->
            val linkEl = element.selectFirst("p.inf a, a[href*='/video/'], a[href*='/videos/'], a.thumb, a") ?: return@mapIndexedNotNull null
            val href = linkEl.attr("href")
            val title = element.selectFirst("strong.title a, .title a, p.inf a, strong.title, .title")?.text()?.trim()
                ?: linkEl.attr("title").ifBlank { null }
                ?: "Video ${index + 1}"
            val duration = element.selectFirst(".durations, .duration, .time, .video-duration, span.min")?.text()?.trim()
            Episode(
                data = href,
                name = title,
                season = 1,
                episode = index + 1,
                description = duration
            )
        }.distinctBy { it.data }
        println("Extracted episodes: ${episodes.size}")
        episodes.take(5).forEach {
            println("  - Ep ${it.episode}: ${it.name} [${it.description}] -> ${it.data}")
        }
        assertTrue(episodes.isNotEmpty(), "Episodes should not be empty")
    }
}

