package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PLibraryLiveTest {
    private val plibrary = PLibrary()

    @Test
    fun testCatalogs() = runBlocking {
        println("=== 1. TESTING PLIBRARY MAIN PAGE CATALOGS ===")
        
        // 1. Trending
        val trending = plibrary.getMainPage(1, MainPageRequest("🔥 Trending", "trending"))
        val trendingList = trending.items.firstOrNull()?.list.orEmpty()
        println("🔥 Trending count: ${trendingList.size}")
        assertTrue(trendingList.isNotEmpty(), "Trending catalog should not be empty")
        trendingList.take(2).forEach {
            println("  - [Trending] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }

        // 2. Studios
        val studios = plibrary.getMainPage(1, MainPageRequest("🏢 Studios", "studios"))
        val studiosList = studios.items.firstOrNull()?.list.orEmpty()
        println("🏢 Studios count: ${studiosList.size}")
        assertTrue(studiosList.isNotEmpty(), "Studios catalog should not be empty")
        studiosList.take(2).forEach {
            println("  - [Studio] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }

        // 3. Vixen Studio
        val vixen = plibrary.getMainPage(1, MainPageRequest("⭐ Vixen", "channel/vixen/"))
        val vixenList = vixen.items.firstOrNull()?.list.orEmpty()
        println("⭐ Vixen count: ${vixenList.size}")
        assertTrue(vixenList.isNotEmpty(), "Vixen catalog should not be empty")
        vixenList.take(2).forEach {
            println("  - [Vixen] ${it.name} -> ${it.url}")
        }
    }

    @Test
    fun testUnifiedSearch() = runBlocking {
        println("=== 2. TESTING PLIBRARY UNIFIED SEARCH ===")
        val query = "angela white"
        val results = plibrary.search(query)
        println("Total search results for '$query': ${results.size}")
        assertTrue(results.isNotEmpty(), "Search results should not be empty")

        val firstItem = results.first()
        println("First search result: ${firstItem.name} (Type: ${firstItem.type}) -> ${firstItem.url}")
        assertEquals(TvType.TvSeries, firstItem.type, "First item for performer search should be a TvSeries model profile")

        val actors = results.filter { it.type == TvType.TvSeries }
        val videos = results.filter { it.type == TvType.Movie }

        println("  -> Actors found: ${actors.size}")
        actors.take(2).forEach { println("     [Actor] ${it.name} -> ${it.url}") }

        println("  -> Videos found: ${videos.size}")
        videos.take(3).forEach { println("     [Video] ${it.name} -> ${it.url}") }
    }

    @Test
    fun testPerformerMultiSeasonLoad() = runBlocking {
        println("=== 3. TESTING PERFORMER 4-SEASON LOAD ===")
        val modelUrl = "https://www.yamyhub.com/pornstar/angela-white/"
        val loadResponse = plibrary.load(modelUrl) as? TvSeriesLoadResponse
        assertNotNull(loadResponse, "Load response should be TvSeriesLoadResponse")
        println("Performer Name: ${loadResponse?.name}")
        println("Total Episodes across seasons: ${loadResponse?.episodes?.size}")

        val s1 = loadResponse?.episodes?.filter { it.season == 1 }.orEmpty()
        val s2 = loadResponse?.episodes?.filter { it.season == 2 }.orEmpty()
        val s3 = loadResponse?.episodes?.filter { it.season == 3 }.orEmpty()
        val s4 = loadResponse?.episodes?.filter { it.season == 4 }.orEmpty()

        println("  -> Season 1 (YamyHub): ${s1.size} videos")
        println("  -> Season 2 (DaftSex): ${s2.size} videos")
        println("  -> Season 3 (TnaFlix): ${s3.size} videos")
        println("  -> Season 4 (FPO): ${s4.size} videos")

        assertTrue(s1.isNotEmpty() || s2.isNotEmpty(), "Should load videos for performer across seasons")
    }

    @Test
    fun testStreamLinkExtraction() = runBlocking {
        println("=== 4. TESTING MULTI-QUALITY STREAM EXTRACTION ===")
        
        // 1. YamyHub Video
        val yamySample = "https://www.yamyhub.com/video/brazzers-mature-stepmother-cherrie-deville-fuck-during-kitchen-work-892/"
        val yamyLinks = mutableListOf<ExtractorLink>()
        val ySuccess = plibrary.loadLinks(yamySample, isCasting = false, subtitleCallback = {}) { link ->
            yamyLinks.add(link)
            println("  [YamyHub Link] ${link.name} (Quality: ${link.quality}) -> ${link.url}")
        }
        println("YamyHub link extraction: $ySuccess (Count: ${yamyLinks.size})")
        assertTrue(yamyLinks.isNotEmpty(), "YamyHub should extract direct MP4 links")

        // 2. DaftSex Video
        val daftSample = "https://daftsex.biz/movie/xJwEaVj1klwOZ3Pa4Oa5oKv"
        val daftLinks = mutableListOf<ExtractorLink>()
        val dSuccess = plibrary.loadLinks(daftSample, isCasting = false, subtitleCallback = {}) { link ->
            daftLinks.add(link)
            println("  [DaftSex Link] ${link.name} (Quality: ${link.quality}) -> ${link.url}")
        }
        println("DaftSex link extraction: $dSuccess (Count: ${daftLinks.size})")
        assertTrue(daftLinks.isNotEmpty(), "DaftSex should extract direct MP4 links")
    }
}

