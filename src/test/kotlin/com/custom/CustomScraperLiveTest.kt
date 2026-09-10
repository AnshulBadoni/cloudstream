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
        try {
            // 1. Popular Movies (ParadiseHill)
            val popular = scraper.getMainPage(1, MainPageRequest("Popular Movies", "popular/?filter=all&sort=by_likes"))
            val popularItems = popular.items.firstOrNull()?.list.orEmpty()
            println("Popular Movies count: " + popularItems.size)
            popularItems.take(2).forEach {
                println("  - [Movie] " + it.name + " -> " + it.url + " (Poster: " + it.posterUrl + ")")
            }

            // 2. Trending Models (PornPics)
            val models = scraper.getMainPage(1, MainPageRequest("Trending Models", "pornpics_models"))
            val modelItems = models.items.firstOrNull()?.list.orEmpty()
            println("Trending Models count: " + modelItems.size)
            modelItems.take(2).forEach {
                println("  - [Model] " + it.name + " -> " + it.url + " (Photo: " + it.posterUrl + ")")
            }
        } catch (e: Exception) {
            println("Main page test warning: " + e.message)
        }
    }

    @Test
    fun testSearch() = runBlocking {
        println("=== 2. TESTING MODEL-FIRST SEARCH ===")
        try {
            // Search for a model
            val modelResults = scraper.search("riley")
            println("Search results for 'riley': " + modelResults.size)
            modelResults.take(3).forEach {
                println("  - Found: " + it.name + " (" + it.type + ") -> " + it.url)
            }

            // Search for a movie
            val movieResults = scraper.search("roots")
            println("Search results for 'roots': " + movieResults.size)
            movieResults.take(2).forEach {
                println("  - Found: " + it.name + " (" + it.type + ") -> " + it.url)
            }
        } catch (e: Exception) {
            println("Search test warning: " + e.message)
        }
    }

    @Test
    fun testMultiPartMovieAndStreamExtraction() = runBlocking {
        val targetUrl = System.getProperty("url")?.takeIf { it.isNotBlank() }
        val movieUrl = targetUrl ?: "https://en.paradisehill.cc/cant_be_roots_xxx_parody_the_untold_story/"
        println("=== 3. TESTING MOVIE & DIRECT MP4 EXTRACTION ===")
        println("Testing URL: $movieUrl")
        try {
            val res = scraper.load(movieUrl)

            println("Title:   " + res.name)
            println("Poster:  " + res.posterUrl)
            println("Plot:    " + res.plot)
            println("Actors:  " + res.actors?.map { it.actor.name })
            println("Tags:    " + res.tags)

            var firstEpisodeData = movieUrl
            if (res is TvSeriesLoadResponse) {
                println("Detected Multi-Part Movie with " + res.episodes.size + " Parts:")
                res.episodes.forEach {
                    println("  - " + it.name + " -> Data: " + it.data)
                }
                firstEpisodeData = res.episodes.firstOrNull()?.data ?: movieUrl
            } else if (res is MovieLoadResponse) {
                println("Single Movie dataUrl: " + res.dataUrl)
                firstEpisodeData = res.dataUrl
            }

            println("\n=== 4. TESTING STREAM & DOWNLOAD EXTRACTION (Page URL) ===")
            val pageStreamLinks = mutableListOf<ExtractorLink>()
            scraper.loadLinks(
                data = movieUrl,
                isCasting = false,
                subtitleCallback = {},
                callback = { link ->
                    println("  -> [Page Link] name=${link.name}, quality=${link.quality}, url=${link.url}")
                    pageStreamLinks.add(link)
                }
            )
            println("Extracted stream links from page: " + pageStreamLinks.size)

            println("\n=== 5. TESTING STREAM & DOWNLOAD EXTRACTION (Episode/Download Button Click) ===")
            println("Testing Episode Data: $firstEpisodeData")
            val epStreamLinks = mutableListOf<ExtractorLink>()
            scraper.loadLinks(
                data = firstEpisodeData,
                isCasting = false,
                subtitleCallback = {},
                callback = { link ->
                    println("  -> [Download Link] name=${link.name}, quality=${link.quality}, url=${link.url}")
                    epStreamLinks.add(link)
                }
            )
            println("Extracted stream links for download button: " + epStreamLinks.size)
        } catch (e: Exception) {
            println("Movie test warning: " + e.message)
        }
    }

    @Test
    fun testModelSeasonsMultiSite() = runBlocking {
        println("=== 5. TESTING MODEL SEASONS MULTI-SITE ===")
        try {
            val modelUrl = "https://www.pornpics.de/pornstars/blake-blossom/"
            val res = scraper.load(modelUrl)

            println("Model Name: " + res.name)
            if (res is TvSeriesLoadResponse) {
                val tvRes = res
                println("Total Episodes across seasons: " + tvRes.episodes.size)
                val s1 = tvRes.episodes.filter { it.season == 1 }
                val s2 = tvRes.episodes.filter { it.season == 2 }
                println("  -> Season 1 (PornTrex):      " + s1.size + " videos")
                println("  -> Season 2 (ParadiseHill):  " + s2.size + " movies")
            }
        } catch (e: Exception) {
            println("Model seasons test warning: " + e.message)
        }
    }
}
