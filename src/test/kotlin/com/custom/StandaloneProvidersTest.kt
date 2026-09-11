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
    fun testDaftSex() = runBlocking {
        println("=== TESTING DAFTSEX STANDALONE PROVIDER ===")
        val provider = DaftSex()

        // 1. Catalogs
        val trending = provider.getMainPage(1, MainPageRequest("Trending", "trending"))
        val list = trending.items.firstOrNull()?.list.orEmpty()
        println("DaftSex Trending count: ${list.size}")
        assertTrue(list.isNotEmpty(), "DaftSex trending should not be empty")
        val samplePoster = list.first().posterUrl
        println("  Sample Trending Poster: $samplePoster")
        assertNotNull(samplePoster, "DaftSex video poster should not be null")
        assertTrue(samplePoster!!.startsWith("http"), "Poster should be a valid HTTP URL")

        // 2. Studios Catalog
        val studios = provider.getMainPage(1, MainPageRequest("Studios", "studios"))
        val studioList = studios.items.firstOrNull()?.list.orEmpty()
        println("DaftSex Studios count: ${studioList.size}")
        assertTrue(studioList.isNotEmpty(), "DaftSex studios should not be empty")
        val sampleStudioPoster = studioList.first().posterUrl
        println("  Sample Studio Poster: $sampleStudioPoster")
        assertNotNull(sampleStudioPoster, "Studio poster should not be null")

        // 3. Search
        val query = "angela white"
        val searchRes = provider.search(query)
        println("DaftSex Search results for '$query': ${searchRes.size}")
        assertTrue(searchRes.isNotEmpty(), "DaftSex search should return items")
        val firstItem = searchRes.first()
        println("  First Search Result: ${firstItem.name} (Type: ${firstItem.type}) -> ${firstItem.url}")
        assertEquals(TvType.TvSeries, firstItem.type, "First item should be a TvSeries model card")
        println("  Search Actor Poster: ${firstItem.posterUrl}")
        assertNotNull(firstItem.posterUrl, "Search actor card should have poster image")

        // 4. Model / Channel Load
        val modelRes = provider.load(firstItem.url) as? TvSeriesLoadResponse
        assertNotNull(modelRes, "Should load model profile as TvSeriesLoadResponse")
        println("  Model Name: ${modelRes?.name}, Total Videos: ${modelRes?.episodes?.size}")
        assertTrue((modelRes?.episodes?.size ?: 0) > 0, "Model should have video episodes")
        val firstMovieEp = modelRes?.episodes?.firstOrNull { !it.data.startsWith("trailer:") } ?: modelRes?.episodes?.first()
        println("  First Movie Episode: ${firstMovieEp?.name} -> ${firstMovieEp?.data}")
        println("  First Episode Poster: ${firstMovieEp?.posterUrl}")
        assertNotNull(firstMovieEp?.posterUrl, "Episode poster should not be null")

        // 5. Movie Detail Load & Recommendations
        val movieUrl = firstMovieEp?.data ?: list.first().url
        val movieRes = provider.load(movieUrl) as? MovieLoadResponse
        assertNotNull(movieRes, "Should load movie details")
        println("  Movie Title: ${movieRes?.name}, Recommendations: ${movieRes?.recommendations?.size}")
        assertTrue((movieRes?.recommendations?.size ?: 0) > 0, "Movie should have recommendations")

        // 6. Stream Link Extraction (Multiple Qualities)
        val extractedLinks = mutableListOf<ExtractorLink>()
        val success = provider.loadLinks(movieUrl, isCasting = false, subtitleCallback = {}) {
            extractedLinks.add(it)
        }
        println("  Extracted stream links count: ${extractedLinks.size}")
        for (l in extractedLinks) {
            println("    -> ${l.name} (${l.quality}p): ${l.url.take(60)}...")
        }
        assertTrue(success, "loadLinks should succeed")
        assertTrue(extractedLinks.isNotEmpty(), "Should extract at least 1 stream link")
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


