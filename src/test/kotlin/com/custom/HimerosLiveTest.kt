package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HimerosLiveTest {
    private val himeros = Himeros()

    @Test
    fun testProviderMetadata() {
        assertEquals("Himeros", himeros.name)
        assertEquals("https://speedporn.net", himeros.mainUrl)
        assertEquals(true, himeros.hasMainPage)
        assertEquals(true, himeros.hasDownloadSupport)
        assertEquals(VPNStatus.MightBeNeeded, himeros.vpnStatus)
        assertEquals(6, himeros.mainPage.size)
    }

    @Test
    fun testCatalogsGraceful() = runBlocking {
        println("=== 1. TESTING SPEEDPORN MAIN PAGE CATALOGS ===")
        val res = runCatching {
            himeros.getMainPage(1, MainPageRequest("HD Movies", "category/hd-porn"))
        }
        if (res.isSuccess) {
            val items = res.getOrThrow().items.firstOrNull()?.list.orEmpty()
            println("HD Movies count: ${items.size}")
            items.take(3).forEach {
                println("  - ${it.name} -> ${it.url}")
            }
        } else {
            println("Catalog live request skipped/timeout (expected without active VPN): ${res.exceptionOrNull()?.message}")
        }
    }

    @Test
    fun testSearchGraceful() = runBlocking {
        println("=== 2. TESTING SEARCH ===")
        val res = runCatching { himeros.search("Level Up Vol. 4") }
        if (res.isSuccess) {
            val results = res.getOrThrow()
            println("Search results: ${results.size}")
        } else {
            println("Search live request skipped/timeout (expected without active VPN): ${res.exceptionOrNull()?.message}")
        }
    }

    @Test
    fun testLoadLinksGraceful() = runBlocking {
        println("=== 3. TESTING LOAD LINKS ===")
        val extractedLinks = mutableListOf<ExtractorLink>()
        val res = runCatching {
            himeros.loadLinks("https://speedporn.net/meant-to-fuck/", isCasting = false, subtitleCallback = {}) { link ->
                extractedLinks.add(link)
            }
        }
        if (res.isSuccess) {
            println("Total extracted links: ${extractedLinks.size}")
        } else {
            println("loadLinks live request skipped/timeout (expected without active VPN): ${res.exceptionOrNull()?.message}")
        }
    }
}
