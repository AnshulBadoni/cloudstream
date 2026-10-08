package com.custom

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class HimerosGirlfriendTest {
    private val himeros = Himeros()

    @Test
    fun testGirlfriendSearchAndLinks() = runBlocking {
        println("=== 1. SEARCHING FOR HOW I MET MY GIRLFRIEND / CHANEL PRESTON ===")
        val queries = listOf("How I Met My Girlfriend", "Chanel Preston", "how i met my girlfriend chanel preston")
        for (q in queries) {
            val results = himeros.search(q)
            println("Query '$q' -> results: ${results.size}")
            results.take(5).forEach {
                println("  - ${it.name} -> ${it.url}")
            }
        }

        // Test loading links from direct search matches
        val match = himeros.search("How I Met My Girlfriend").firstOrNull()
            ?: himeros.search("Chanel Preston").firstOrNull { it.name.contains("Girlfriend", ignoreCase = true) }

        if (match != null) {
            println("\n=== 2. TESTING LOAD LINKS FOR: ${match.name} (${match.url}) ===")
            val pageDoc = app.get(match.url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36")).document
            println("Page HTML length: ${pageDoc.html().length}")
            println("Iframes found: ${pageDoc.select("iframe").map { it.attr("src").ifBlank { it.attr("data-src") } }}")
            println("Links found: ${pageDoc.select("a[href]").map { it.attr("href") }.filter { u -> listOf("lulu", "dood", "mixdrop", "streamtape", "voe", "file", "play").any { u.contains(it, true) } }}")
            
            val links = mutableListOf<ExtractorLink>()
            val success = himeros.loadLinks(match.url, isCasting = false, subtitleCallback = {}) { l ->
                println(">>> Extracted link: [${l.name}] ${l.url}")
                links.add(l)
            }
            println("loadLinks success: $success, Total extracted: ${links.size}")
        }
    }
}
