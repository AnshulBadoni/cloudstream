package com.cloudstream.scraper

import com.cloudstream.scraper.config.ExtractionRule
import com.cloudstream.scraper.config.ExtractionType
import com.cloudstream.scraper.config.FieldType
import com.cloudstream.scraper.config.TransformConfig
import com.cloudstream.scraper.engine.HtmlParser
import com.cloudstream.scraper.engine.RuleEvaluator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuleEvaluatorTest {

    private val sampleHtml = """
        <div class="movie-card">
            <a class="main-link" href="/movie/batman-2022">
                <img class="primary-img" data-src="/posters/batman.jpg" alt="Batman Poster" />
                <h2 class="title"> The Batman (2022) </h2>
            </a>
            <span class="meta-year">Year: 2022</span>
            <span class="meta-rating">8.3 / 10</span>
            <div class="genres">
                <span class="genre">Action</span>
                <span class="genre">Crime</span>
                <span class="genre">Drama</span>
            </div>
            <div class="fallback-section">
                <span class="empty-primary"></span>
                <span class="valid-fallback">Fallback Extracted Value</span>
            </div>
        </div>
    """.trimIndent()

    private val doc = HtmlParser.parse(sampleHtml, "https://example.com")
    private val card = HtmlParser.selectFirst(doc, ".movie-card")!!

    @Test
    fun `extract text with trim and regex`() {
        val titleRule = ExtractionRule(
            selector = ".title",
            extraction = ExtractionType.TEXT,
            transforms = listOf(TransformConfig(trim = true))
        )
        assertEquals("The Batman (2022)", RuleEvaluator.extractString(card, titleRule, "https://example.com"))

        val yearRule = ExtractionRule(
            selector = ".meta-year",
            extraction = ExtractionType.TEXT,
            transforms = listOf(TransformConfig(regex = "(\\d{4})", group = 1)),
            type = FieldType.INT
        )
        assertEquals(2022, RuleEvaluator.extractInt(card, yearRule, "https://example.com"))
    }

    @Test
    fun `extract attribute and resolve absolute URL`() {
        val posterRule = ExtractionRule(
            selector = "img.primary-img",
            extraction = ExtractionType.ATTRIBUTE,
            attribute = "data-src"
        )
        assertEquals(
            "https://example.com/posters/batman.jpg",
            RuleEvaluator.extractString(card, posterRule, "https://example.com")
        )
    }

    @Test
    fun `extract list of strings`() {
        val genreRule = ExtractionRule(
            selector = ".genres .genre",
            extraction = ExtractionType.TEXT_LIST
        )
        val genres = RuleEvaluator.extractList(card, genreRule, "https://example.com")
        assertEquals(listOf("Action", "Crime", "Drama"), genres)
    }

    @Test
    fun `fallback rule should be evaluated when primary is missing or empty`() {
        val ruleWithFallback = ExtractionRule(
            selector = ".empty-primary",
            extraction = ExtractionType.TEXT,
            fallbacks = listOf(
                ExtractionRule(
                    selector = ".valid-fallback",
                    extraction = ExtractionType.TEXT
                )
            )
        )
        val value = RuleEvaluator.extractString(card, ruleWithFallback, "https://example.com")
        assertEquals("Fallback Extracted Value", value)
    }

    @Test
    fun `default value should be returned if all selectors fail`() {
        val failingRule = ExtractionRule(
            selector = ".non-existent-selector",
            extraction = ExtractionType.TEXT,
            default = "Default Movie Title"
        )
        val value = RuleEvaluator.extractString(card, failingRule, "https://example.com")
        assertEquals("Default Movie Title", value)
    }
}
