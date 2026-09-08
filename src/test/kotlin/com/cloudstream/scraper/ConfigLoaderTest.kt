package com.cloudstream.scraper

import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.config.ConfigParseException
import com.cloudstream.scraper.config.ConfigValidationException
import com.cloudstream.scraper.config.ExtractionType
import com.cloudstream.scraper.model.CatalogType
import com.cloudstream.scraper.model.MediaType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigLoaderTest {

    @Test
    fun `should load valid site config from resource`() {
        val config = ConfigLoader.loadFromResource("/test_sites/valid_site.yaml")

        assertEquals("test-site", config.id)
        assertEquals("Test Site", config.name)
        assertEquals("https://test-site.example.com", config.baseUrl)
        assertEquals(1, config.version)
        assertEquals("TestBrowser/1.0", config.headers["User-Agent"])

        // Search config
        val search = config.search
        assertNotNull(search)
        assertEquals("/search?q={query}&page={page}", search?.urlTemplate)
        assertEquals(".search-card", search?.itemSelector)
        assertEquals("text", search?.fields?.get("title")?.extraction?.name?.lowercase())

        // Catalogs
        assertEquals(2, config.catalogs.size)
        assertEquals("popular", config.catalogs[0].id)
        assertEquals(CatalogType.MOVIES, config.catalogs[0].type)
        assertEquals("trending-tv", config.catalogs[1].id)
        assertEquals(CatalogType.SERIES, config.catalogs[1].type)

        // Details
        val details = config.details
        assertNotNull(details)
        assertEquals(".episodes-grid", details?.typeDetector?.selector)
        assertEquals(MediaType.TV_SERIES, details?.typeDetector?.existsAs)

        // Series details
        val series = details?.series
        assertNotNull(series)
        assertEquals(".episode-row", series?.episodesSelector)
        assertEquals(1, series?.seasonNumber?.transforms?.size)
    }

    @Test
    fun `should throw ConfigParseException on invalid YAML syntax`() {
        val malformedYaml = """
            id: bad-yaml
            name: Bad
            baseUrl: [unclosed bracket
        """.trimIndent()

        assertThrows<ConfigParseException> {
            ConfigLoader.loadFromString(malformedYaml)
        }
    }

    @Test
    fun `should throw ConfigValidationException on invalid semantics`() {
        val invalidConfigYaml = """
            id: INVALID_UPPERCASE_ID
            name: Bad Site
            baseUrl: not-a-valid-url
            version: 99
        """.trimIndent()

        val ex = assertThrows<ConfigValidationException> {
            ConfigLoader.loadFromString(invalidConfigYaml)
        }
        assertTrue(ex.errors.any { it.contains("baseUrl") })
        assertTrue(ex.errors.any { it.contains("version") })
        assertTrue(ex.errors.any { it.contains("id") })
    }
}
