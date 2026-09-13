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
}
