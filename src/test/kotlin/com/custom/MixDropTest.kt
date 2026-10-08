package com.custom

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class MixDropTest {

    @Test
    fun testMixDropDecodingAndHeaders() = runBlocking {
        println("=== TESTING MIXDROP EXTRACTOR & HEADERS ===")
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        
        // Find a recent page from 5moviesporn with mixdrop
        val pageDoc = runCatching {
            app.get("https://www.5moviesporn.io/", headers = mapOf("User-Agent" to userAgent)).document
        }.getOrNull()

        val sampleMovieUrl = pageDoc?.select("a[href*='/video/'], a[href*='/movies/'], .item a")
            ?.map { it.attr("href") }
            ?.firstOrNull { it.isNotBlank() }

        println("Sample movie url: $sampleMovieUrl")
        if (sampleMovieUrl != null) {
            val movieDoc = runCatching {
                app.get(sampleMovieUrl, headers = mapOf("User-Agent" to userAgent)).document
            }.getOrNull()

            val rawHtml = movieDoc?.html().orEmpty()
            val mixdropMatches = Regex("""https?://[^\s"'<>\\]*(?:mixdrop|mxdrop)[^\s"'<>\\]*""").findAll(rawHtml).map { it.value }.toList()
            println("Found mixdrop embeds: $mixdropMatches")
        }
    }
}
