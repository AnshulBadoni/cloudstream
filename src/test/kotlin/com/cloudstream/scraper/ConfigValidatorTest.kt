package com.cloudstream.scraper

import com.cloudstream.scraper.config.*
import com.cloudstream.scraper.model.CatalogType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ConfigValidatorTest {

    @Test
    fun `valid minimal config should pass validation`() {
        val config = SiteConfig(
            id = "valid-site",
            name = "Valid Site",
            baseUrl = "https://valid.example.com",
            version = 1
        )
        assertDoesNotThrow {
            ConfigValidator.validate(config)
        }
    }

    @Test
    fun `blank id should fail validation`() {
        val config = SiteConfig(
            id = "",
            name = "Valid Site",
            baseUrl = "https://valid.example.com"
        )
        assertThrows<ConfigValidationException> {
            ConfigValidator.validate(config)
        }
    }

    @Test
    fun `invalid id characters should fail validation`() {
        val config = SiteConfig(
            id = "Invalid Site ID!",
            name = "Valid Site",
            baseUrl = "https://valid.example.com"
        )
        assertThrows<ConfigValidationException> {
            ConfigValidator.validate(config)
        }
    }

    @Test
    fun `invalid baseUrl protocol should fail validation`() {
        val config = SiteConfig(
            id = "valid-site",
            name = "Valid Site",
            baseUrl = "ftp://invalid.example.com"
        )
        assertThrows<ConfigValidationException> {
            ConfigValidator.validate(config)
        }
    }

    @Test
    fun `unsupported version should fail validation`() {
        val config = SiteConfig(
            id = "valid-site",
            name = "Valid Site",
            baseUrl = "https://valid.example.com",
            version = 99
        )
        assertThrows<ConfigValidationException> {
            ConfigValidator.validate(config)
        }
    }

    @Test
    fun `search without query placeholder should fail validation`() {
        val config = SiteConfig(
            id = "valid-site",
            name = "Valid Site",
            baseUrl = "https://valid.example.com",
            search = SearchConfig(
                urlTemplate = "/search?page={page}",
                itemSelector = ".item"
            )
        )
        assertThrows<ConfigValidationException> {
            ConfigValidator.validate(config)
        }
    }

    @Test
    fun `attribute extraction missing attribute name should fail validation`() {
        val config = SiteConfig(
            id = "valid-site",
            name = "Valid Site",
            baseUrl = "https://valid.example.com",
            search = SearchConfig(
                urlTemplate = "/search?q={query}",
                itemSelector = ".item",
                fields = mapOf(
                    "poster" to ExtractionRule(
                        selector = "img",
                        extraction = ExtractionType.ATTRIBUTE,
                        attribute = null // missing attribute
                    )
                )
            )
        )
        assertThrows<ConfigValidationException> {
            ConfigValidator.validate(config)
        }
    }

    @Test
    fun `duplicate catalog IDs should fail validation`() {
        val config = SiteConfig(
            id = "valid-site",
            name = "Valid Site",
            baseUrl = "https://valid.example.com",
            catalogs = listOf(
                CatalogConfig(id = "movies", name = "Movies", type = CatalogType.MOVIES, urlTemplate = "/movies"),
                CatalogConfig(id = "movies", name = "Duplicate Movies", type = CatalogType.MOVIES, urlTemplate = "/movies-2")
            )
        )
        assertThrows<ConfigValidationException> {
            ConfigValidator.validate(config)
        }
    }
}
