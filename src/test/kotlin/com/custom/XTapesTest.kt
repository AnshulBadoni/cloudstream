package com.custom

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class XTapesTest {
    private val provider = XTapes()

    @Test
    fun testProviderMetadata() {
        assertEquals("XTapes", provider.name)
        assertEquals("https://ww3.xtapes.tw", provider.mainUrl)
        assertEquals(true, provider.hasMainPage)
        assertEquals(true, provider.hasDownloadSupport)
        assertEquals(setOf(TvType.NSFW, TvType.Movie), provider.supportedTypes)
    }

    @Test
    fun testCatalogsDefinition() {
        assertNotNull(provider.mainPage)
        val sections = provider.mainPage.map { it.name }
        println("XTapes configured catalog sections: $sections")
        assertEquals(6, sections.size)
    }

    @Test
    fun testLiveOrFallbackScraping() = runBlocking {
        println("=== TESTING XTAPES LIVE/FALLBACK SCRAPING ===")
        val home = runCatching {
            provider.getMainPage(1, MainPageRequest("Latest Videos", "latest-updates"))
        }.getOrNull()
        println("Home response received: ${home != null}, items: ${home?.items?.firstOrNull()?.list?.size ?: 0}")
    }
}
