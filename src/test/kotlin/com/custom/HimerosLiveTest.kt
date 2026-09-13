package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HimerosLiveTest {
    private val himeros = Himeros()

    @Test
    fun testCatalogs() = runBlocking {
        println("=== 1. TESTING HIMEROS MAIN PAGE CATALOGS ===")

        // 1. Recent (Data18)
        val recent = himeros.getMainPage(1, MainPageRequest("Recent", "d18_recent"))
        val recentList = recent.items.firstOrNull()?.list.orEmpty()
        println("Recent count: ${recentList.size}")
        assertTrue(recentList.isNotEmpty(), "Recent catalog should not be empty")
        recentList.take(2).forEach {
            println("  - [Recent] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }

        // 2. Models (PornPics)
        val models = himeros.getMainPage(1, MainPageRequest("Models", "pp_models"))
        val modelsList = models.items.firstOrNull()?.list.orEmpty()
        println("Models count: ${modelsList.size}")
        assertTrue(modelsList.isNotEmpty(), "Models catalog should not be empty")
        modelsList.take(2).forEach {
            println("  - [Model] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }

        // 3. Recent Series (Data18)
        val series = himeros.getMainPage(1, MainPageRequest("Recent Series", "d18_series"))
        val seriesList = series.items.firstOrNull()?.list.orEmpty()
        println("Recent Series count: ${seriesList.size}")
        assertTrue(seriesList.isNotEmpty(), "Recent Series catalog should not be empty")
        seriesList.take(2).forEach {
            println("  - [Series] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }

        // 4. Studios (PornPics)
        val studios = himeros.getMainPage(1, MainPageRequest("Studios", "pp_studios"))
        val studiosList = studios.items.firstOrNull()?.list.orEmpty()
        println("Studios count: ${studiosList.size}")
        assertTrue(studiosList.isNotEmpty(), "Studios catalog should not be empty")
        studiosList.take(2).forEach {
            println("  - [Studio] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }

        // 5. Showcase (Data18)
        val showcase = himeros.getMainPage(1, MainPageRequest("Showcase", "d18_showcases"))
        val showcaseList = showcase.items.firstOrNull()?.list.orEmpty()
        println("Showcase count: ${showcaseList.size}")
        assertTrue(showcaseList.isNotEmpty(), "Showcase catalog should not be empty")
        showcaseList.take(2).forEach {
            println("  - [Showcase] ${it.name} -> ${it.url}")
        }
    }

    @Test
    fun testPosterEnhancement() = runBlocking {
        println("=== 2. TESTING IMDB / TMDB POSTER ENHANCEMENT ===")
        val poster1 = himeros.fetchEnhancedPoster("Equilibrium", fallback = "https://fallback.com/eq.jpg")
        println("Equilibrium Poster: $poster1")
        assertNotNull(poster1)
        assertTrue(poster1!!.contains("media-amazon.com") || poster1.contains("tmdb.org"))

        val poster2 = himeros.fetchEnhancedPoster("Pirates", fallback = "https://fallback.com/p.jpg")
        println("Pirates Poster: $poster2")
        assertNotNull(poster2)
    }

    @Test
    fun testMovieDetailLoad() = runBlocking {
        println("=== 3. TESTING MOVIE EPISODIC LOAD ===")
        val movieUrl = "https://www.data18.com/movies/1214858-equilibrium"
        val res = himeros.load(movieUrl) as? TvSeriesLoadResponse
        assertNotNull(res, "Load response should be TvSeriesLoadResponse")
        println("Movie Title: ${res?.name}")
        println("Enhanced Poster: ${res?.posterUrl}")
        println("Actors: ${res?.actors?.map { it.actor.name }}")
        println("Total Episodes / Parts: ${res?.episodes?.size}")
        res?.episodes?.forEach { ep ->
            println("  - Ep ${ep.episode}: ${ep.name} (Data length: ${ep.data.length})")
        }
        assertTrue(res?.episodes?.isNotEmpty() == true, "Episodes list should not be empty")
    }

    @Test
    fun testSearch() = runBlocking {
        println("=== 4. TESTING SEARCH ===")
        val results = himeros.search("pirates")
        println("Search results for 'pirates': ${results.size}")
        assertTrue(results.isNotEmpty(), "Search results should not be empty")
        results.take(3).forEach {
            println("  - [Result] ${it.name} -> ${it.url}")
        }
    }

    @Test
    fun testTitleNormalizationAndFuzzyMatching() {
        println("=== 5. TESTING TITLE NORMALIZATION & FUZZY MATCHING ===")
        val t1 = himeros.normalizeTitle("Ignite Vol. 10 (2023) [4K]")
        assertEquals("Ignite 10", t1)

        val t2 = himeros.normalizeTitle("Anal Icons Vol. #5")
        assertEquals("Anal Icons 5", t2)

        val t3 = himeros.normalizeTitle("Equilibrium #1 (2026)")
        assertEquals("Equilibrium 1", t3)

        val score1 = himeros.fuzzyMatchScore("Ignite Vol. 10", "Ignite 10 - Full Movie HD")
        println("Score for 'Ignite Vol. 10' vs 'Ignite 10': $score1")
        assertTrue(score1 >= 0.8, "Should match same volume")

        val score2 = himeros.fuzzyMatchScore("Ignite Vol. 10", "Ignite 9")
        println("Score for 'Ignite Vol. 10' vs 'Ignite 9': $score2")
        assertEquals(0.0, score2, "Should reject different volume numbers")

        // Test Data18 clean title
        val c1 = himeros.cleanData18Title("Summer Secrets (2026) Showcase Porn Movies | DATA18")
        assertEquals("Summer Secrets", c1)

        val c2 = himeros.cleanData18Title("Movie Series: Lesbian Love Stories | DATA18")
        assertEquals("Lesbian Love Stories", c2)

        val c3 = himeros.cleanData18Title("Facial Fantasy 4 (2023) Porn Movie | DATA18")
        assertEquals("Facial Fantasy 4", c3)

        val c4 = himeros.cleanData18Title("Lesbian Love Stories #11")
        assertEquals("Lesbian Love Stories 11", c4)

        val c5 = himeros.cleanData18Title("Hot Horny Cheerleaders #4 (2024) Porn Movie | DATA18")
        assertEquals("Hot Horny Cheerleaders 4", c5)

        val c6 = himeros.cleanData18Title("Facial Fantasy, by Evil Angel")
        assertEquals("Facial Fantasy", c6)
    }

    @Test
    fun testNoBuyThisSceneEpisodes() = runBlocking {
        println("=== 6. TESTING NO PROMOTIONAL 'BUY THIS SCENE' EPISODES ===")
        val movieUrl = "https://www.data18.com/movies/1128696-lesbian-love-stories-11"
        val res = himeros.load(movieUrl) as? TvSeriesLoadResponse
        assertNotNull(res, "Load response should not be null")
        println("Movie Title: ${res?.name}")
        assertEquals("Lesbian Love Stories 11", res?.name)
        res?.episodes?.forEach { ep ->
            println("  - Ep ${ep.episode}: ${ep.name}")
            assertFalse(ep.name?.contains("Buy this scene", ignoreCase = true) == true, "Episode name should not contain 'Buy this scene'")
        }
        val epNames = res?.episodes?.map { it.name }.orEmpty()
        assertEquals(epNames.distinct().size, epNames.size, "Episodes should have no duplicates")
    }

    @Test
    fun testStudioDetailLoad() = runBlocking {
        println("=== 7. TESTING STUDIO DETAIL LOAD (CHANNEL LOGO) ===")
        val onlyfansUrl = "https://www.pornpics.de/channels/onlyfans"
        val res = himeros.load(onlyfansUrl) as? TvSeriesLoadResponse
        assertNotNull(res, "Studio load response should not be null")
        println("Studio Name: ${res?.name}")
        println("Studio Logo: ${res?.posterUrl}")
        assertTrue(res?.posterUrl?.contains("hfma.pornpics.de") == true || res?.posterUrl?.contains("16014") == true, "Studio logo should be authentic high-res channel logo")
    }

    @Test
    fun testLoadLinks() = runBlocking {
        println("=== 8. TESTING LOAD LINKS (STREAM & DOWNLOAD RESOLVER) ===")
        val links = mutableListOf<ExtractorLink>()
        val success = himeros.loadLinks(
            data = "full_movie|Full Movie|Facial Fantasy 4|",
            isCasting = false,
            subtitleCallback = {},
            callback = { link ->
                links.add(link)
                println("  -> Emitted Link: ${link.name} | URL: ${link.url}")
            }
        )
        assertTrue(success, "loadLinks should return true")
        println("Total links emitted for 'Facial Fantasy 4': ${links.size}")
        assertTrue(links.isNotEmpty(), "Should emit at least one stream/download link for Facial Fantasy 4")
    }
}
