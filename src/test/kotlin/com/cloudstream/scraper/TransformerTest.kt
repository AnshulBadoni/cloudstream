package com.cloudstream.scraper

import com.cloudstream.scraper.config.FieldType
import com.cloudstream.scraper.config.TransformConfig
import com.cloudstream.scraper.engine.Transformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransformerTest {

    @Test
    fun `resolveUrl should properly handle relative paths, scheme-relative, and full URLs`() {
        val baseUrl = "https://example.com/movies/"

        assertEquals("https://example.com/movie/123", Transformer.resolveUrl(baseUrl, "/movie/123"))
        assertEquals("https://example.com/images/poster.jpg", Transformer.resolveUrl(baseUrl, "//example.com/images/poster.jpg"))
        assertEquals("https://cdn.other.com/poster.jpg", Transformer.resolveUrl(baseUrl, "https://cdn.other.com/poster.jpg"))
        assertEquals("https://example.com/movies/details/123", Transformer.resolveUrl(baseUrl, "details/123"))
    }

    @Test
    fun `transform should execute trim, regex capture, uppercase, lowercase, and replacement`() {
        // Regex group extraction
        val yearTransforms = listOf(
            TransformConfig(regex = "Release Year:\\s*(\\d{4})", group = 1)
        )
        assertEquals("2023", Transformer.transform("Release Year: 2023 (USA)", yearTransforms))

        // Text cleanup & mapping
        val mapTransforms = listOf(
            TransformConfig(trim = true, lowercase = true),
            TransformConfig(map = mapOf("tv" to "TV_SERIES", "movie" to "MOVIE"))
        )
        assertEquals("TV_SERIES", Transformer.transform("  TV  ", mapTransforms))

        // Replace
        val replaceTransforms = listOf(
            TransformConfig(replace = "1080p", replacement = "FHD")
        )
        assertEquals("Batman FHD", Transformer.transform("Batman 1080p", replaceTransforms))
    }

    @Test
    fun `cast should properly convert string to int, double, boolean`() {
        assertEquals(2023, Transformer.cast(" 2023 ", FieldType.INT))
        assertEquals(8.5, Transformer.cast("8.5/10", FieldType.DOUBLE))
        assertEquals(true, Transformer.cast("true", FieldType.BOOLEAN))
        assertEquals(false, Transformer.cast("no", FieldType.BOOLEAN))
        assertEquals("hello", Transformer.cast("hello", FieldType.STRING))
    }
}
