package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class CustomScraperLiveTest {
    @Test
    fun testLiveLoadLinks() = runBlocking {
        val scraper = CustomScraper()
        val testUrls = listOf(
            "https://xmovix.net/en/movies/hd-1080p/10985-devil-in-her.html",
            "https://xmovix.net/en/movies/10978-dr-glenn-and-his-nurses.html",
            "https://xmovix.net/en/movies/parodies/10970-squid-game-xxx-an-axel-braun-parody.html"
        )
        for (movieUrl in testUrls) {
            println("\n========================================================")
            println("Testing loadLinks on: $movieUrl")
            println("========================================================")
            val links = mutableListOf<ExtractorLink>()
            val success = scraper.loadLinks(
                data = movieUrl,
                isCasting = false,
                subtitleCallback = {},
                callback = { link ->
                    println("[STREAM EXTRACTED]")
                    println("  -> Name:     " + link.name)
                    println("  -> URL:      " + link.url)
                    println("  -> Referer:  " + link.referer)
                    println("  -> isM3u8:   " + link.isM3u8)
                    println("  -> Headers:  " + link.headers)
                    links.add(link)
                }
            )
            println("Extraction Result: success=$success, total streams found=${links.size}")
        }
    }
}
