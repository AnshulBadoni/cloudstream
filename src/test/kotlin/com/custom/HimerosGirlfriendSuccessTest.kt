package com.custom

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class HimerosGirlfriendSuccessTest {
    private val himeros = Himeros()

    @Test
    fun testGirlfriendWithDataServers() = runBlocking {
        val url = "https://speedporn.net/how-i-met-my-girlfriend-chanel-preston/"
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36")).document
        
        val dataServers = doc.select("[data-servers]").map { it.attr("data-servers") }
        println("Found data-servers: $dataServers")

        val extractedLinks = mutableListOf<ExtractorLink>()
        val success = himeros.loadLinks(url, isCasting = false, subtitleCallback = {}) { link ->
            println(">>> Extracted: [${link.name}] ${link.url}")
            extractedLinks.add(link)
        }
        println("Extracted count: ${extractedLinks.size}, success: $success")
    }
}
