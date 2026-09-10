package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CustomScraperLiveTest {
    private val scraper = CustomScraper()

    @Test
    fun testMainPageCatalogs() = runBlocking {
        println("=== 1. TESTING MULTI-SOURCE MAIN PAGE CATALOGS ===")
        
        // 1. Popular Movies (ParadiseHill)
        val popular = scraper.getMainPage(1, MainPageRequest("Popular Movies", "popular/?filter=all&sort=by_likes"))
        val popularItems = popular.items.firstOrNull()?.list.orEmpty()
        println("Popular Movies count: " + popularItems.size)
        assertTrue(popularItems.isNotEmpty(), "Popular movies should not be empty")
        popularItems.take(2).forEach {
            println("  - [Movie] " + it.name + " -> " + it.url + " (Poster: " + it.posterUrl + ")")
        }

        // 2. Trending Models (PornPics)
        val models = scraper.getMainPage(1, MainPageRequest("Trending Models", "pornpics_models"))
        val modelItems = models.items.firstOrNull()?.list.orEmpty()
        println("Trending Models count: " + modelItems.size)
        assertTrue(modelItems.isNotEmpty(), "Trending models should not be empty")
        modelItems.take(2).forEach {
            println("  - [Model] " + it.name + " -> " + it.url + " (Photo: " + it.posterUrl + ")")
        }
    }

    @Test
    fun testSearch() = runBlocking {
        println("=== 2. TESTING MODEL-FIRST SEARCH ===")
        
        // Search for a model
        val modelResults = scraper.search("riley")
        println("Search results for 'riley': " + modelResults.size)
        assertTrue(modelResults.isNotEmpty(), "Model search results should not be empty")
        modelResults.take(3).forEach {
            println("  - Found: " + it.name + " (" + it.type + ") -> " + it.url)
        }

        // Search for a movie
        val movieResults = scraper.search("roots")
        println("Search results for 'roots': " + movieResults.size)
        assertTrue(movieResults.isNotEmpty(), "Movie search results should not be empty")
        movieResults.take(2).forEach {
            println("  - Found: " + it.name + " (" + it.type + ") -> " + it.url)
        }
    }

    @Test
    fun testMultiPartMovieAndStreamExtraction() = runBlocking {
        println("=== 3. TESTING MULTI-PART MOVIE & DIRECT MP4 EXTRACTION ===")
        val movieUrl = "https://en.paradisehill.cc/cant_be_roots_xxx_parody_the_untold_story/"
        val res = scraper.load(movieUrl)

        println("Title:   " + res.name)
        println("Poster:  " + res.posterUrl)
        println("Plot:    " + res.plot)
        println("Actors:  " + res.actors?.map { it.actor.name })
        println("Tags:    " + res.tags)

        assertTrue(res.name.contains("Roots", ignoreCase = true), "Title should match")
        assertTrue(!res.actors.isNullOrEmpty(), "Actors should be extracted")

        if (res is TvSeriesLoadResponse) {
            println("Detected Multi-Part Movie with " + res.episodes.size + " Parts:")
            res.episodes.forEach {
                println("  - " + it.name + " -> Data: " + it.data)
            }
            assertTrue(res.episodes.size >= 2, "Should have extracted at least 2 parts (CD1 & CD2)")
        }

        println("=== 4. TESTING STREAM & DOWNLOAD EXTRACTION ===")
        val streamLinks = mutableListOf<ExtractorLink>()
        val success = scraper.loadLinks(
            data = movieUrl,
            isCasting = false,
            subtitleCallback = {},
            callback = { link ->
                println("  -> EXTRACTED STREAM: name=" + link.name + ", isM3u8=" + link.isM3u8 + ", url=" + link.url)
                streamLinks.add(link)
            }
        )

        assertTrue(success, "loadLinks should return true")
        assertTrue(streamLinks.isNotEmpty(), "Should have extracted stream links")
        assertTrue(streamLinks.all { it.url.startsWith("http") && it.url.contains(".mp4") }, "All links should be direct MP4 URLs")
    }

    @Test
    fun testModelSeasonsMultiSite() = runBlocking {
        println("=== 5. TESTING MODEL SEASONS MULTI-SITE ===")
        val modelUrl = "https://www.pornpics.de/pornstars/blake-blossom/"
        val res = scraper.load(modelUrl)

        println("Model Name: " + res.name)
        assertTrue(res is TvSeriesLoadResponse, "Model profile should load as TvSeries with seasons")
        val tvRes = res as TvSeriesLoadResponse
        println("Total Episodes across seasons: " + tvRes.episodes.size)
        val s1 = tvRes.episodes.filter { it.season == 1 }
        val s2 = tvRes.episodes.filter { it.season == 2 }
        println("  -> Season 1 (PornTrex):      " + s1.size + " videos")
        println("  -> Season 2 (ParadiseHill):  " + s2.size + " movies")
    }
}
