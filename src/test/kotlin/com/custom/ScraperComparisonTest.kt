package com.custom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.megix.Porntrex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ScraperComparisonTest {

    @Test
    fun compareScrapersSideBySide() = runBlocking {
        val pt = Porntrex()
        val ms = CustomScraper()

        println("========================================================================")
        println("               SIDE-BY-SIDE SCRAPER COMPARISON REPORT                  ")
        println("========================================================================")

        // 1. PROVIDER METADATA
        println("\n--- 1. PROVIDER PROPERTIES ---")
        println("PornTrex:")
        println("  name:               ${pt.name}")
        println("  mainUrl:            ${pt.mainUrl}")
        println("  hasDownloadSupport: ${pt.hasDownloadSupport}")
        println("  supportedTypes:     ${pt.supportedTypes}")
        println("  hasMainPage:        ${pt.hasMainPage}")

        println("\nMultiSource:")
        println("  name:               ${ms.name}")
        println("  mainUrl:            ${ms.mainUrl}")
        println("  hasDownloadSupport: ${ms.hasDownloadSupport}")
        println("  supportedTypes:     ${ms.supportedTypes}")
        println("  hasMainPage:        ${ms.hasMainPage}")

        // 2. SEARCH COMPARISON
        println("\n--- 2. SEARCH COMPARISON (Query: 'blake') ---")
        val ptSearch = runCatching { pt.search("blake") }.getOrElse { emptyList() }
        val msSearch = runCatching { ms.search("blake") }.getOrElse { emptyList() }

        println("PornTrex Search Count:    ${ptSearch.size}")
        ptSearch.take(3).forEach {
            println("  - [${it.type}] ${it.name} -> ${it.url}")
        }

        println("MultiSource Search Count: ${msSearch.size}")
        msSearch.take(3).forEach {
            println("  - [${it.type}] ${it.name} -> ${it.url}")
        }

        // 3. LOAD RESPONSE COMPARISON
        println("\n--- 3. LOAD RESPONSE COMPARISON ---")

        // 3a. PornTrex Video Load
        val samplePtUrl = ptSearch.firstOrNull { it.url.contains("/video/") }?.url
            ?: "https://www.porntrex.com/video/3322928/blake-blossom-hot-host"
        println("\n[A] Loading PornTrex Video via PornTrex.load():")
        println("    URL: $samplePtUrl")
        val ptLoad = runCatching { pt.load(samplePtUrl) }.getOrNull()
        if (ptLoad != null) {
            println("    Result Class:   ${ptLoad::class.simpleName}")
            println("    Title:          ${ptLoad.name}")
            println("    URL:            ${ptLoad.url}")
            println("    TvType:         ${ptLoad.type}")
            println("    Poster:         ${ptLoad.posterUrl}")
            if (ptLoad is MovieLoadResponse) {
                println("    dataUrl:        ${ptLoad.dataUrl}")
            }
        } else {
            println("    FAILED or TIMED OUT")
        }

        // 3b. MultiSource Movie Load (ParadiseHill)
        val sampleMsMovieUrl = "https://en.paradisehill.cc/cant_be_roots_xxx_parody_the_untold_story/"
        println("\n[B] Loading ParadiseHill Movie via MultiSource.load():")
        println("    URL: $sampleMsMovieUrl")
        val msMovieLoad = runCatching { ms.load(sampleMsMovieUrl) }.getOrNull()
        if (msMovieLoad != null) {
            println("    Result Class:   ${msMovieLoad::class.simpleName}")
            println("    Title:          ${msMovieLoad.name}")
            println("    URL:            ${msMovieLoad.url}")
            println("    TvType:         ${msMovieLoad.type}")
            println("    Poster:         ${msMovieLoad.posterUrl}")
            if (msMovieLoad is TvSeriesLoadResponse) {
                println("    Episodes Count: ${msMovieLoad.episodes.size}")
                msMovieLoad.episodes.forEach { ep ->
                    println("      * [Ep ${ep.episode}] ${ep.name} -> Data: ${ep.data}")
                }
            } else if (msMovieLoad is MovieLoadResponse) {
                println("    dataUrl:        ${msMovieLoad.dataUrl}")
            }
        } else {
            println("    FAILED or TIMED OUT")
        }

        // 3c. MultiSource Actor Load (Multi-Site Seasons)
        val sampleActorUrl = "https://en.paradisehill.cc/actor/16678/" // Riley Steele
        println("\n[C] Loading Actor Profile via MultiSource.load():")
        println("    URL: $sampleActorUrl")
        val msActorLoad = runCatching { ms.load(sampleActorUrl) }.getOrNull()
        if (msActorLoad is TvSeriesLoadResponse) {
            println("    Actor Name:     ${msActorLoad.name}")
            println("    Total Episodes: ${msActorLoad.episodes.size}")
            val s1 = msActorLoad.episodes.filter { it.season == 1 }
            val s2 = msActorLoad.episodes.filter { it.season == 2 }
            println("      - Season 1 (PornTrex):     ${s1.size} videos (Sample: ${s1.firstOrNull()?.data})")
            println("      - Season 2 (ParadiseHill): ${s2.size} movies (Sample: ${s2.firstOrNull()?.data})")
        }

        // 4. STREAM EXTRACTION (loadLinks) COMPARISON
        println("\n--- 4. STREAM EXTRACTION (loadLinks) COMPARISON ---")

        // 4a. PornTrex loadLinks
        println("\n[A] Calling PornTrex.loadLinks on PornTrex video:")
        val ptLinks = mutableListOf<ExtractorLink>()
        var ptError: Throwable? = null
        val ptSuccess = try {
            pt.loadLinks(samplePtUrl, false, {}) { ptLinks.add(it) }
        } catch (e: Throwable) {
            ptError = e
            false
        }
        println("    Success: $ptSuccess | Extracted Links: ${ptLinks.size}")
        if (ptError != null) {
            println("    Exception: ${ptError.message}")
        }
        ptLinks.forEach { link ->
            println("    -> Source:   '${link.source}'")
            println("       Name:     '${link.name}'")
            println("       Quality:  ${link.quality}")
            println("       isM3u8:   ${link.isM3u8}")
            println("       Referer:  '${link.referer}'")
            println("       Headers:  ${link.headers}")
            println("       URL:      ${link.url.take(70)}...")
        }

        // 4b. MultiSource loadLinks on ParadiseHill MP4 Stream
        val sampleMp4 = "https://v1.paradisehill.cc/video/cant_be_roots_xxx_parody-cd1.mp4"
        println("\n[B] Calling MultiSource.loadLinks on Direct MP4 (ParadiseHill):")
        val msMp4Links = mutableListOf<ExtractorLink>()
        val msMp4Success = runCatching {
            ms.loadLinks(sampleMp4, false, {}) { msMp4Links.add(it) }
        }.getOrDefault(false)
        println("    Success: $msMp4Success | Extracted Links: ${msMp4Links.size}")
        msMp4Links.forEach { link ->
            println("    -> Source:   '${link.source}'")
            println("       Name:     '${link.name}'")
            println("       Quality:  ${link.quality}")
            println("       isM3u8:   ${link.isM3u8}")
            println("       Referer:  '${link.referer}'")
            println("       Headers:  ${link.headers}")
            println("       URL:      ${link.url.take(70)}...")
        }

        // 4c. MultiSource loadLinks on PornTrex video URL
        println("\n[C] Calling MultiSource.loadLinks on PornTrex Video URL:")
        val msPtLinks = mutableListOf<ExtractorLink>()
        val msPtSuccess = runCatching {
            ms.loadLinks(samplePtUrl, false, {}) { msPtLinks.add(it) }
        }.getOrDefault(false)
        println("    Success: $msPtSuccess | Extracted Links: ${msPtLinks.size}")
        msPtLinks.forEach { link ->
            println("    -> Source:   '${link.source}'")
            println("       Name:     '${link.name}'")
            println("       Quality:  ${link.quality}")
            println("       isM3u8:   ${link.isM3u8}")
            println("       Referer:  '${link.referer}'")
            println("       Headers:  ${link.headers}")
            println("       URL:      ${link.url.take(70)}...")
        }

        println("\n========================================================================")
    }
}
