package com.custom

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class HimerosFullPostTest {
    @Test
    fun printFullPost() = runBlocking {
        val url = "https://speedporn.net/how-i-met-my-girlfriend-chanel-preston/"
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36")).document
        val mainContent = doc.select("div.zn, div.vid, div.entry-content, main, #main, .post-inner").eachText()
        println("Main content text blocks:\n" + mainContent.joinToString("\n---\n"))
        
        println("\n=== ALL A HREF WITH FILE / DOWNLOAD / STREAM / EMBED ===")
        doc.select("a[href]").forEach {
            val h = it.attr("href")
            println("A link: ${it.text()} -> $h")
        }
    }
}
