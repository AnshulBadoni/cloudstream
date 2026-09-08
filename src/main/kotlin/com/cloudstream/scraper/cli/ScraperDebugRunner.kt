package com.cloudstream.scraper.cli

import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.config.SiteConfig
import com.cloudstream.scraper.engine.GenericScraperEngine
import com.cloudstream.scraper.engine.MockScraperHttpClient
import com.cloudstream.scraper.engine.OkHttpScraperClient
import com.cloudstream.scraper.model.Movie
import com.cloudstream.scraper.model.Series
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val sitesDir = File("src/main/resources/sites")
    val loadedConfigs = mutableMapOf<String, SiteConfig>()

    if (sitesDir.exists()) {
        ConfigLoader.loadAllFromDirectory(sitesDir).forEach { loadedConfigs[it.id] = it }
    } else {
        try {
            val site = ConfigLoader.loadFromResource("/sites/first-site.yaml")
            loadedConfigs[site.id] = site
        } catch (e: Exception) {
            // Ignore if in standalone binary
        }
    }

    if (args.isEmpty()) {
        printUsage(loadedConfigs.keys)
        return@runBlocking
    }

    val command = args[0].lowercase()

    when (command) {
        "sites" -> {
            println("========================================")
            println(" Loaded Site Configurations (${loadedConfigs.size})")
            println("========================================")
            loadedConfigs.values.forEach { site ->
                println(" • ID: ${site.id}")
                println("   Name: ${site.name}")
                println("   Base URL: ${site.baseUrl}")
                println("   Catalogs: ${site.catalogs.joinToString { it.name }}")
                println()
            }
        }

        "fetch", "html" -> {
            if (args.size < 2) {
                println("Usage: scraper fetch <url> [outputFile]")
                return@runBlocking
            }
            val url = args[1]
            val outputFile = if (args.size > 2) File(args[2]) else null

            println("Fetching HTML from '$url'...")
            try {
                val client = OkHttpScraperClient()
                val response = client.get(url)
                if (!response.isSuccessful) {
                    println("Error: HTTP ${response.code} received.")
                    return@runBlocking
                }

                if (outputFile != null) {
                    outputFile.writeText(response.body, Charsets.UTF_8)
                    println("✓ Successfully saved ${response.body.length} characters to '${outputFile.absolutePath}'")
                } else {
                    println("\n" + response.body)
                }
            } catch (e: Exception) {
                println("\n[Fetch Error]: ${e.message}")
            }
        }

        "search" -> {
            if (args.size < 3) {
                println("Usage: scraper search <siteId> <query> [page]")
                return@runBlocking
            }
            val siteId = args[1]
            val query = args[2]
            val page = if (args.size > 3) args[3].toIntOrNull() ?: 1 else 1

            val config = loadedConfigs[siteId] ?: run {
                println("Error: Unknown site '$siteId'")
                return@runBlocking
            }

            println("Searching '${config.name}' for '$query' (page $page)...")
            try {
                val engine = GenericScraperEngine(OkHttpScraperClient())
                val results = engine.search(config, query, page)
                println("\nSearch Results (${results.size}):")
                results.forEachIndexed { idx, res ->
                    println("\n[${idx + 1}]")
                    println("  Title:  ${res.title.padEnd(30)} ✓")
                    println("  URL:    ${res.url.padEnd(30)} ✓")
                    println("  Type:   ${res.type}")
                    println("  Year:   ${res.releaseYear ?: "N/A"}")
                    println("  Rating: ${res.rating ?: "N/A"}")
                    println("  Poster: ${res.posterUrl ?: "None"}")
                }
            } catch (e: Exception) {
                println("\n[Network Error]: ${e.message}")
                println("Tip: ${config.baseUrl} could not be reached. Make sure baseUrl in '${config.id}.yaml' is a live website, or test offline using: .\\gradlew.bat run --args=\"file ${config.id} search src/test/resources/fixtures/first-site/search.html\"")
            }
        }

        "catalog" -> {
            if (args.size < 3) {
                println("Usage: scraper catalog <siteId> <catalogId> [page]")
                return@runBlocking
            }
            val siteId = args[1]
            val catalogId = args[2]
            val page = if (args.size > 3) args[3].toIntOrNull() ?: 1 else 1

            val config = loadedConfigs[siteId] ?: run {
                println("Error: Unknown site '$siteId'")
                return@runBlocking
            }

            val catalogConfig = config.catalogs.find { it.id == catalogId } ?: run {
                println("Error: Catalog '$catalogId' not found in site '$siteId'. Available: ${config.catalogs.map { it.id }}")
                return@runBlocking
            }

            println("Fetching catalog '${catalogConfig.name}' (page $page)...")
            try {
                val engine = GenericScraperEngine(OkHttpScraperClient())
                val catalog = engine.getCatalog(config, catalogConfig, page)
                println("\nCatalog: ${catalog.name} (Items: ${catalog.items.size}, HasNextPage: ${catalog.hasNextPage})")
                catalog.items.forEachIndexed { idx, res ->
                    println("  [${idx + 1}] ${res.title} (${res.releaseYear ?: "N/A"}) -> ${res.url}")
                }
            } catch (e: Exception) {
                println("\n[Network Error]: ${e.message}")
                println("Tip: ${config.baseUrl} could not be reached. Make sure baseUrl in '${config.id}.yaml' is a live website, or test offline using: .\\gradlew.bat run --args=\"file ${config.id} catalog src/test/resources/fixtures/first-site/actors_catalog.html\"")
            }
        }

        "load" -> {
            if (args.size < 3) {
                println("Usage: scraper load <siteId> <url>")
                return@runBlocking
            }
            val siteId = args[1]
            val url = args[2]

            val config = loadedConfigs[siteId] ?: run {
                println("Error: Unknown site '$siteId'")
                return@runBlocking
            }

            println("Loading details for '$url' using '${config.name}'...")
            try {
                val engine = GenericScraperEngine(OkHttpScraperClient())
                val details = engine.load(config, url) ?: run {
                    println("Error: Failed to extract details.")
                    return@runBlocking
                }

                printDetails(details)
            } catch (e: Exception) {
                println("\n[Network Error]: ${e.message}")
                println("Tip: Could not connect to $url. To test offline, run: .\\gradlew.bat run --args=\"file ${config.id} load src/test/resources/fixtures/first-site/movie.html\"")
            }
        }

        "person" -> {
            if (args.size < 3) {
                println("Usage: scraper person <siteId> <url>")
                return@runBlocking
            }
            val siteId = args[1]
            val url = args[2]

            val config = loadedConfigs[siteId] ?: run {
                println("Error: Unknown site '$siteId'")
                return@runBlocking
            }

            println("Fetching actor profile for '$url'...")
            try {
                val engine = GenericScraperEngine(OkHttpScraperClient())
                val person = engine.getPerson(config, url) ?: run {
                    println("Error: Failed to extract person.")
                    return@runBlocking
                }

                println("\n========================================")
                println(" Performer Profile")
                println("========================================")
                println(" Performer:   ${person.name} ✓")
                println(" Photo:       ${person.photoUrl ?: "N/A"}")
                println(" Birth Date:  ${person.birthDate ?: "N/A"}")
                println(" Biography:   ${person.biography ?: "N/A"}")
                println("\n Performer Videos (${person.knownFor.size}):")
                person.knownFor.forEachIndexed { idx, item ->
                    println("  [${idx + 1}] ${item.title} -> ${item.url}")
                }
                println("\nPASS")
            } catch (e: Exception) {
                println("\n[Network Error]: ${e.message}")
                println("Tip: Could not connect to $url. To test offline, run: .\\gradlew.bat run --args=\"file ${config.id} person src/test/resources/fixtures/first-site/actor.html\"")
            }
        }

        "links" -> {
            if (args.size < 3) {
                println("Usage: scraper links <siteId> <webpageUrlOrHtmlFile>")
                return@runBlocking
            }
            val siteId = args[1]
            val input = args[2]

            val config = loadedConfigs[siteId] ?: run {
                println("Error: Unknown site '$siteId'")
                return@runBlocking
            }

            println("Extracting stream qualities for '$input' using '${config.name}'...")
            try {
                val localFile = File(input)
                val htmlContent = if (localFile.exists()) {
                    localFile.readText(Charsets.UTF_8)
                } else {
                    val client = OkHttpScraperClient()
                    val response = client.get(input, config.headers)
                    response.body
                }

                val engine = GenericScraperEngine(MockScraperHttpClient())
                val sources = engine.extractStreamSources(htmlContent, config.baseUrl)
                println("\nFound ${sources.size} Available Video Resolutions:")
                sources.forEachIndexed { idx, src ->
                    val q = if (src.quality != null) "[${src.quality}p]" else "[${src.name}]"
                    println("  [${idx + 1}] $q ${src.name.padEnd(12)} -> ${src.url}")
                }
            } catch (e: Exception) {
                println("\n[Error]: ${e.message}")
            }
        }

        "file" -> {
            if (args.size < 4) {
                println("Usage: scraper file <siteId> <search|catalog|load|person> <htmlFilePath>")
                return@runBlocking
            }
            val siteId = args[1]
            val type = args[2].lowercase()
            val filePath = args[3]
            val file = File(filePath)

            if (!file.exists()) {
                println("Error: File not found: ${file.absolutePath}")
                return@runBlocking
            }

            val htmlContent = file.readText(Charsets.UTF_8)
            val config = loadedConfigs[siteId] ?: run {
                println("Error: Unknown site '$siteId'")
                return@runBlocking
            }

            val mockClient = MockScraperHttpClient(defaultResponse = htmlContent)
            val engine = GenericScraperEngine(mockClient)

            println("Processing offline file '${file.name}' using site '${config.name}' ($type)...")

            when (type) {
                "search" -> {
                    val results = engine.search(config, "test")
                    println("\nResults (${results.size}):")
                    results.forEachIndexed { idx, res ->
                        println("  [${idx + 1}] ${res.title} (${res.type}) -> ${res.url}")
                    }
                }
                "catalog" -> {
                    val catalog = engine.getCatalog(config, config.catalogs.firstOrNull { it.type == com.cloudstream.scraper.model.CatalogType.PEOPLE } ?: config.catalogs.first())
                    println("\nCatalog: ${catalog.name} (Items: ${catalog.items.size})")
                    catalog.items.forEachIndexed { idx, res ->
                        println("  [${idx + 1}] ${res.title} -> ${res.url}")
                    }
                }
                "load" -> {
                    val details = engine.load(config, config.baseUrl + "/sample")
                    val sources = engine.extractStreamSources(htmlContent, config.baseUrl)
                    if (details != null) printDetails(details, sources) else println("Failed to extract details.")
                }
                "person" -> {
                    val person = engine.getPerson(config, config.baseUrl + "/actor/sample")
                    if (person != null) {
                        println("\n Performer: ${person.name} ✓")
                        println(" Photo:     ${person.photoUrl ?: "N/A"}")
                        println(" Biography: ${person.biography?.take(150)}...")
                        println(" Known For (${person.knownFor.size}):")
                        person.knownFor.forEach { println("   - ${it.title} -> ${it.url}") }
                    } else {
                        println("Failed to extract person.")
                    }
                }
            }
        }

        else -> printUsage(loadedConfigs.keys)
    }
}

private fun printDetails(
    details: com.cloudstream.scraper.model.MediaDetails,
    sources: List<com.cloudstream.scraper.model.MediaSource> = emptyList()
) {
    println("\n========================================")
    println(" Extracted Media Details")
    println("========================================")
    println(" Title:          ${details.title} ✓")
    println(" Page Link:      ${details.url} ✓")
    println(" Type:           ${details.type}")
    println(" Year:           ${details.releaseYear ?: "N/A"} ✓")
    val ratingStr = details.rating?.let { "${it}%" } ?: "N/A"
    println(" Rating:         $ratingStr ✓")
    if (details is Movie) {
        println(" Duration:       ${details.durationMinutes ?: "N/A"} min")
    }
    println(" Description:    ${details.description ?: "N/A"} ✓")
    println(" Genres / Tags:  ${(details.genres + details.tags).distinct().joinToString().ifBlank { "N/A" }}")
    println(" Poster:         ${details.posterUrl ?: "N/A"}")
    println(" Performers (${details.cast.size}): ${details.cast.take(5).joinToString { it.person.name }}")

    if (sources.isNotEmpty()) {
        println("\n Available Video Resolutions (${sources.size}):")
        sources.forEachIndexed { idx, src ->
            val qTag = if (src.quality != null) "[${src.quality}p]" else "[${src.name}]"
            println("   - $qTag ${src.name.padEnd(10)} -> ${src.url}")
        }
    } else {
        println(" Stream / Link:  ${details.trailerUrl ?: details.url} ✓")
    }

    if (details is Series) {
        println("\n Seasons (${details.seasons.size}):")
        details.seasons.forEach { season ->
            println("   Season ${season.seasonNumber}: ${season.episodes.size} episodes")
            season.episodes.take(3).forEach { ep ->
                println("     - Ep ${ep.episodeNumber}: ${ep.title} (${ep.url})")
            }
        }
    }
    println("\nPASS")
}

private fun printUsage(availableSites: Collection<String>) {
    println("""
        =========================================================
         CloudStream Generic Scraper CLI & Debug Inspector
        =========================================================
        Usage:
          scraper fetch <url> [outputFile]               - Download HTML from a link to inspect
          scraper sites                                  - List available site configs
          scraper search <siteId> <query> [page]         - Test live search extraction
          scraper catalog <siteId> <catalogId> [page]    - Test live catalog extraction
          scraper load <siteId> <url>                    - Test live movie/scene extraction
          scraper links <siteId> <url>                   - Test all video stream qualities/resolutions
          scraper person <siteId> <url>                  - Test live actor extraction
          scraper file <siteId> <search|catalog|load|person> <htmlFile> - Test local HTML fixture

        Available Sites: ${availableSites.joinToString()}
    """.trimIndent())
}
