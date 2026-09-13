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

        // 2. Models (Data18)
        val models = himeros.getMainPage(1, MainPageRequest("Models", "d18_models"))
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

        // 4. Studios (Data18)
        val studios = himeros.getMainPage(1, MainPageRequest("Studios", "d18_studios"))
        val studiosList = studios.items.firstOrNull()?.list.orEmpty()
        println("Studios count: ${studiosList.size}")
        assertTrue(studiosList.isNotEmpty(), "Studios catalog should not be empty")
        studiosList.take(2).forEach {
            println("  - [Studio] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
        }

        // 5. Showcase (Data18)
        val releases = himeros.getMainPage(1, MainPageRequest("Showcase", "d18_showcases"))
        val releasesList = releases.items.firstOrNull()?.list.orEmpty()
        println("Showcase count: ${releasesList.size}")
        assertTrue(releasesList.isNotEmpty(), "Showcase catalog should not be empty")
        releasesList.take(2).forEach {
            println("  - [Showcase] ${it.name} -> ${it.url} (Poster: ${it.posterUrl})")
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
    fun testStudioLoad() = runBlocking {
        println("=== 3b. TESTING STUDIO LOAD (BLACKED) ===")
        val studioUrl = "https://www.data18.com/studios/blacked"
        val res = himeros.load(studioUrl) as? TvSeriesLoadResponse
        assertNotNull(res, "Load response should be TvSeriesLoadResponse")
        println("Studio Name: ${res?.name}")
        println("Logo: ${res?.posterUrl}")
        println("Episodes / Movies count: ${res?.episodes?.size}")
        res?.episodes?.take(5)?.forEach { ep ->
            println("  - Ep ${ep.episode}: ${ep.name} -> Poster: ${ep.posterUrl}")
        }
        assertTrue(res?.episodes?.isNotEmpty() == true, "Studio episodes should not be empty")
    }

    @Test
    fun testPerformerLoad() = runBlocking {
        println("=== 3c. TESTING PERFORMER LOAD (KAYDEN KROSS) ===")
        val performerUrl = "https://www.data18.com/name/kayden-kross"
        val res = himeros.load(performerUrl) as? TvSeriesLoadResponse
        assertNotNull(res, "Load response should be TvSeriesLoadResponse")
        println("Performer Name: ${res?.name}")
        println("Avatar: ${res?.posterUrl}")
        println("Episodes / Movies count: ${res?.episodes?.size}")
        res?.episodes?.take(5)?.forEach { ep ->
            println("  - Ep ${ep.episode}: ${ep.name} -> Poster: ${ep.posterUrl}")
        }
        assertTrue(res?.episodes?.isNotEmpty() == true, "Performer episodes should not be empty")
    }

    @Test
    fun testSeriesLoad() = runBlocking {
        println("=== 3d. TESTING SERIES LOAD (LESBIAN LOVE STORIES) ===")
        val seriesUrl = "https://www.data18.com/studios/girlfriends-films/movie-series-lesbian-love-stories"
        val res = himeros.load(seriesUrl) as? TvSeriesLoadResponse
        assertNotNull(res, "Load response should be TvSeriesLoadResponse")
        println("Series Name: ${res?.name}")
        println("Poster: ${res?.posterUrl}")
        println("Episodes / Volumes count: ${res?.episodes?.size}")
        res?.episodes?.take(5)?.forEach { ep ->
            println("  - Ep ${ep.episode}: ${ep.name} -> Poster: ${ep.posterUrl}")
        }
        assertTrue(res?.episodes?.isNotEmpty() == true, "Series episodes should not be empty")
    }

    @Test
    fun testSearch() = runBlocking {
        println("=== 4. TESTING SEARCH ===")
        val results = himeros.search("facial fantasy 4")
        println("Search results for 'facial fantasy 4': ${results.size}")
        assertTrue(results.isNotEmpty(), "Search results should not be empty")
        results.take(5).forEach {
            println("  - [Result] ${it.name} -> ${it.url}")
            assertFalse(it.name.contains("hrs", ignoreCase = true) || it.name.contains("min", ignoreCase = true), "Search result title should not be duration string: ${it.name}")
        }
        val match = results.find { it.name.equals("Facial Fantasy 4", ignoreCase = true) }
        assertNotNull(match, "Should find 'Facial Fantasy 4' search result")
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

        val c7 = himeros.cleanMovieTitle("Watch Facial Fantasy 4 2026 by Evil Angel Porn Movie Online Free - SpeedPorn")
        assertEquals("Facial Fantasy 4", c7)

        val c8 = himeros.cleanMovieTitle("Watch facial fantasy 4 Porn Free - Page 1 of 1 - SpeedPorn")
        assertEquals("facial fantasy 4", c8)
    }

    @Test
    fun testSpeedPornMovieLoad() = runBlocking {
        println("=== 6. TESTING SPEEDPORN MOVIE LOAD ===")
        val spUrl = "https://speedporn.net/facial-fantasy-4/"
        val res = himeros.load(spUrl) as? TvSeriesLoadResponse
        assertNotNull(res, "SpeedPorn load response should not be null")
        println("SpeedPorn Movie Title: ${res?.name}")
        assertEquals("Facial Fantasy 4", res?.name)
        println("SpeedPorn Poster: ${res?.posterUrl}")
        println("Actors: ${res?.actors?.map { it.actor.name }}")
        println("Episodes count: ${res?.episodes?.size}")
        res?.episodes?.forEach { ep ->
            println("  - Ep ${ep.episode}: ${ep.name} (Data: ${ep.data})")
        }
        assertTrue(res?.episodes?.isNotEmpty() == true, "Episodes should not be empty")
        assertEquals("Full Movie", res?.episodes?.first()?.name)
    }

    @Test
    fun testNoBuyThisSceneEpisodes() = runBlocking {
        println("=== 7. TESTING NO PROMOTIONAL 'BUY THIS SCENE' EPISODES ===")
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
        println("=== 8. TESTING STUDIO DETAIL LOAD (CHANNEL LOGO) ===")
        val onlyfansUrl = "https://www.pornpics.de/channels/onlyfans"
        val res = himeros.load(onlyfansUrl) as? TvSeriesLoadResponse
        assertNotNull(res, "Studio load response should not be null")
        println("Studio Name: ${res?.name}")
        println("Studio Logo: ${res?.posterUrl}")
        assertTrue(res?.posterUrl?.contains("hfma.pornpics.de") == true || res?.posterUrl?.contains("16014") == true, "Studio logo should be authentic high-res channel logo")
    }

    @Test
    fun testLoadLinks() = runBlocking {
        println("=== 9. TESTING LOAD LINKS (STREAM & DOWNLOAD RESOLVER) ===")
        val links = mutableListOf<ExtractorLink>()
        val success = himeros.loadLinks(
            data = "full_movie|Full Movie|Facial Fantasy 4|https://speedporn.net/facial-fantasy-4/|Adriana Chechik",
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
