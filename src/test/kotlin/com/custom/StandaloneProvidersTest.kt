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

        // 2. Search
        val query = "angela white"
        val searchRes = provider.search(query)
        println("DaftSex Search results for '$query': ${searchRes.size}")
        assertTrue(searchRes.isNotEmpty(), "DaftSex search should return items")
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
}

