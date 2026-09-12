package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StandaloneProvidersTest {

    @Test
    fun testYamyHub() = runBlocking {
        println("=== TESTING YAMYHUB STANDALONE PROVIDER ===")
        val provider = YamyHub()

        // 1. Catalogs
        val trending = provider.getMainPage(1, MainPageRequest("Trending", "trending"))
        val list = trending.items.firstOrNull()?.list.orEmpty()
        println("YamyHub Trending count: ${list.size}")
        assertTrue(list.isNotEmpty(), "YamyHub trending should not be empty")

        // 2. Search
        val query = "angela white"
        val searchRes = provider.search(query)
        println("YamyHub Search results for '$query': ${searchRes.size}")
        assertTrue(searchRes.isNotEmpty(), "YamyHub search should return items")
        val firstItem = searchRes.first()
        println("  First Search Result: ${firstItem.name} (Type: ${firstItem.type}) -> ${firstItem.url}")
        assertEquals(TvType.TvSeries, firstItem.type, "First item should be a TvSeries model card")

        // 3. Model Load
        val modelRes = provider.load(firstItem.url) as? TvSeriesLoadResponse
        assertNotNull(modelRes, "Should load model profile as TvSeriesLoadResponse")
        println("  Model Name: ${modelRes?.name}, Total Videos: ${modelRes?.episodes?.size}")
        assertTrue((modelRes?.episodes?.size ?: 0) > 0, "Model should have video episodes")
    }

    @Test
    fun testEporner() = runBlocking {
        println("=== TESTING EPORNER STANDALONE PROVIDER ===")
        val provider = Eporner()

        // 1. Catalogs
        val weeklyTop = provider.getMainPage(1, MainPageRequest("Weekly Top 4K Pro", "${provider.mainUrl}/cat/all/PROD-pro/SORT-top-weekly/?quality=2160&duration_min=1800"))
        val list = weeklyTop.items.firstOrNull()?.list.orEmpty()
        println("Eporner Weekly Top count: ${list.size}")
        assertTrue(list.isNotEmpty(), "Eporner weekly top should not be empty")
        val samplePoster = list.first().posterUrl
        println("  Sample Weekly Top Poster: $samplePoster")
        assertNotNull(samplePoster, "Eporner video poster should not be null")

        // 2. Studios & Channels Catalog
        val studios = provider.getMainPage(1, MainPageRequest("Studios & Channels", "${provider.mainUrl}/channels/"))
        val studioList = studios.items.firstOrNull()?.list.orEmpty()
        println("Eporner Studios count: ${studioList.size}")
        assertTrue(studioList.isNotEmpty(), "Eporner studios should not be empty")
        val sampleStudioPoster = studioList.first().posterUrl
        println("  Sample Studio Poster: $sampleStudioPoster")

        // 3. Search
        val query = "angela white"
        val searchRes = provider.search(query)
        println("Eporner Search results for '$query': ${searchRes.size}")
        assertTrue(searchRes.isNotEmpty(), "Eporner search should return items")
        val firstItem = searchRes.first()
        println("  First Search Result: ${firstItem.name} (Type: ${firstItem.type}) -> ${firstItem.url}")

        // 4. Model / Channel Load
        val channelRes = provider.load("${provider.mainUrl}/channel/vixen/") as? TvSeriesLoadResponse
        assertNotNull(channelRes, "Should load Vixen channel as TvSeriesLoadResponse")
        println("  Channel Name: ${channelRes?.name}, Total Videos: ${channelRes?.episodes?.size}")
        assertTrue((channelRes?.episodes?.size ?: 0) > 0, "Channel should have video episodes")

        // 5. Movie Detail Load & Recommendations
        val movieUrl = channelRes?.episodes?.first()?.data ?: list.first().url
        val movieRes = provider.load(movieUrl) as? MovieLoadResponse
        assertNotNull(movieRes, "Should load movie details")
        println("  Movie Title: ${movieRes?.name}, Recommendations: ${movieRes?.recommendations?.size}")

        // 6. Stream Link Extraction (Multiple Qualities across multiple videos)
        val testUrls = listOf(
            movieUrl,
            list.first().url,
            list.getOrNull(1)?.url ?: movieUrl
        ).distinct()

        for (u in testUrls) {
            println("\nTesting stream extraction for: $u")
            val links = mutableListOf<ExtractorLink>()
            val s = provider.loadLinks(u, isCasting = false, subtitleCallback = {}) {
                links.add(it)
            }
            println("  Extracted stream links count: ${links.size}")
            for (l in links) {
                println("    -> [${l.quality}p] ${l.name}: ${l.url}")
            }
            assertTrue(s, "loadLinks should succeed for $u")
            assertTrue(links.isNotEmpty(), "Should extract links for $u")
        }
    }

    @Test
    fun testTnaFlix() = runBlocking {
        println("=== TESTING TNAFLIX STANDALONE PROVIDER ===")
        val provider = TnaFlix()

        // 1. Search
        val query = "angela white"
        val searchRes = provider.search(query)
        println("TnaFlix Search results for '$query': ${searchRes.size}")
        assertTrue(searchRes.isNotEmpty(), "TnaFlix search should return items")
        val firstItem = searchRes.first()
        println("  First Search Result: ${firstItem.name} (Type: ${firstItem.type}) -> ${firstItem.url}")
        assertEquals(TvType.TvSeries, firstItem.type, "First item should be a TvSeries model card")
    }

    @Test
    fun testFPO() = runBlocking {
        println("=== TESTING FPO STANDALONE PROVIDER ===")
        val provider = FPO()

        // 1. Catalogs
        val trending = runCatching { provider.getMainPage(1, MainPageRequest("Trending", "trending")) }.getOrNull()
        val list = trending?.items?.firstOrNull()?.list.orEmpty()
        println("FPO Trending count: ${list.size}")

        // 2. Search
        val query = "aletta ocean"
        val searchRes = provider.search(query)
        println("FPO Search results for '$query': ${searchRes.size}")
        assertTrue(searchRes.isNotEmpty(), "FPO search should return at least synthetic performer card")
        val firstItem = searchRes.first()
        println("  First Search Result: ${firstItem.name} (Type: ${firstItem.type}) -> ${firstItem.url}")
        assertEquals(TvType.TvSeries, firstItem.type, "First item should be a TvSeries model card")
    }

    @Test
    fun testTrailerHelper() = runBlocking {
        println("=== TESTING TRAILER HELPER ===")
        val vixenTrailer = TrailerHelper.fetchStudioTrailerM3u8("Vixen")
        println("Vixen Studio Trailer: $vixenTrailer")
        assertNotNull(vixenTrailer, "Should fetch Vixen studio trailer")
        assertTrue(vixenTrailer!!.endsWith(".m3u8"), "Trailer should be an M3U8 stream")

        val alettaTrailer = TrailerHelper.fetchModelTrailerM3u8("Aletta Ocean")
        println("Aletta Ocean Model Trailer: $alettaTrailer")
        assertNotNull(alettaTrailer, "Should fetch Aletta Ocean model trailer")
        assertTrue(alettaTrailer!!.endsWith(".m3u8"), "Trailer should be an M3U8 stream")

        val vixenLogo = TrailerHelper.fetchPornPicsStudioLogo("vixen")
        println("Vixen Studio Logo: $vixenLogo")
        assertNotNull(vixenLogo, "Should fetch Vixen studio logo from PornPics")
        assertTrue(vixenLogo!!.contains("hfma.pornpics.de") || vixenLogo.startsWith("http"), "Should be valid logo URL")

        val extractedLinks = mutableListOf<ExtractorLink>()
        val handled = TrailerHelper.handleTrailerStream("trailer:$vixenTrailer", "DaftSex") {
            extractedLinks.add(it)
        }
        assertTrue(handled, "Trailer stream handler should handle trailer: prefix")
        assertTrue(extractedLinks.isNotEmpty(), "Trailer stream should produce ExtractorLink")
        println("  Extracted Trailer Link: ${extractedLinks.first().name} -> ${extractedLinks.first().url}")
    }
}


