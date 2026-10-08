package com.custom

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class HimerosInspectPageTest {
    @Test
    fun inspectChanelPrestonPage() = runBlocking {
        val url = "https://speedporn.net/how-i-met-my-girlfriend-chanel-preston/"
        val resp = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36"))
        println("Status: ${resp.code}")
        val doc = resp.document
        
        println("=== ALL LINKS ON PAGE ===")
        doc.select("a[href]").forEach {
            val href = it.attr("href")
            val text = it.text().trim()
            if (!href.startsWith("#") && !href.contains("category") && !href.contains("tag") && !href.contains("wp-content")) {
                println("Link: [$text] -> $href")
            }
        }

        println("\n=== ALL IFRAMES / EMBEDS / VIDEOS ===")
        doc.select("iframe, embed, video, source, object").forEach {
            println("Tag: <${it.tagName()}> html: ${it.outerHtml()}")
        }

        println("\n=== ALL SCRIPTS ===")
        doc.select("script").forEach {
            val s = it.html().trim()
            if (s.contains("eval") || s.contains("player") || s.contains("video") || s.contains("http") || s.contains("file")) {
                println("Script snippet: ${s.take(300)}")
            }
        }
        
        println("\n=== ARTICLE CONTENT HTML ===")
        val content = doc.selectFirst("div.entry-content, article, div.post-inner, .video-player")?.html().orEmpty()
        println(content.take(1500))
    }
}
