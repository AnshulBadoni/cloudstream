package com.cloudstream.scraper.engine

import com.cloudstream.scraper.config.ExtractionRule
import com.cloudstream.scraper.config.ExtractionType
import com.cloudstream.scraper.config.FieldType
import org.jsoup.nodes.Element

object RuleEvaluator {

    /**
     * Evaluates an ExtractionRule against a Jsoup Element.
     * Supports CSS selection, attribute/text/html extraction, transforms, and fallback chains.
     */
    fun evaluate(element: Element, rule: ExtractionRule?, baseUrl: String): Any? {
        if (rule == null) return null

        val result = evaluateSingleRule(element, rule, baseUrl)
        if (result != null && (result !is String || result.isNotBlank()) && (result !is Collection<*> || result.isNotEmpty())) {
            return result
        }

        // Try fallbacks in order
        for (fallback in rule.fallbacks) {
            val fallbackResult = evaluate(element, fallback, baseUrl)
            if (fallbackResult != null && (fallbackResult !is String || fallbackResult.isNotBlank()) && (fallbackResult !is Collection<*> || fallbackResult.isNotEmpty())) {
                return fallbackResult
            }
        }

        // Return default value if present
        return rule.default?.let { Transformer.cast(it, rule.type) }
    }

    private fun evaluateSingleRule(element: Element, rule: ExtractionRule, baseUrl: String): Any? {
        val targetElements = if (rule.selector.isNullOrBlank()) {
            listOf(element)
        } else {
            HtmlParser.select(element, rule.selector)
        }

        if (targetElements.isEmpty()) return null

        val extraction = if (!rule.attribute.isNullOrBlank() && rule.extraction == ExtractionType.TEXT) {
            ExtractionType.ATTRIBUTE
        } else {
            rule.extraction
        }

        return when (extraction) {
            ExtractionType.TEXT -> {
                val firstEl = targetElements.firstOrNull()
                val rawText = if (firstEl != null && firstEl.tagName().equals("script", ignoreCase = true)) {
                    firstEl.data().ifBlank { firstEl.html() }
                } else {
                    firstEl?.text()?.ifBlank { firstEl.data() }
                }
                val transformed = Transformer.transform(rawText, rule.transforms)
                Transformer.cast(transformed, rule.type, rule.default)
            }
            ExtractionType.ATTRIBUTE -> {
                val attrName = rule.attribute ?: return null
                var rawAttr = targetElements.firstOrNull()?.attr(attrName)
                if (rawAttr.isNullOrBlank()) {
                    rawAttr = targetElements.firstOrNull()?.absUrl(attrName)
                }
                // If it's a URL-like attribute or src/href, resolve URL
                if (attrName.contains("src", ignoreCase = true) || attrName.contains("href", ignoreCase = true) || attrName.contains("url", ignoreCase = true)) {
                    rawAttr = Transformer.resolveUrl(baseUrl, rawAttr)
                }
                val transformed = Transformer.transform(rawAttr, rule.transforms)
                Transformer.cast(transformed, rule.type, rule.default)
            }
            ExtractionType.HTML -> {
                val rawHtml = targetElements.firstOrNull()?.html()
                val transformed = Transformer.transform(rawHtml, rule.transforms)
                Transformer.cast(transformed, rule.type, rule.default)
            }
            ExtractionType.TEXT_LIST -> {
                targetElements.mapNotNull { el ->
                    val transformed = Transformer.transform(el.text(), rule.transforms)
                    if (transformed.isNullOrBlank()) null else transformed
                }
            }
            ExtractionType.ATTRIBUTE_LIST -> {
                val attrName = rule.attribute ?: return emptyList<String>()
                targetElements.mapNotNull { el ->
                    var raw = el.attr(attrName)
                    if (raw.isBlank()) raw = el.absUrl(attrName)
                    if (attrName.contains("src", ignoreCase = true) || attrName.contains("href", ignoreCase = true) || attrName.contains("url", ignoreCase = true)) {
                        raw = Transformer.resolveUrl(baseUrl, raw) ?: raw
                    }
                    val transformed = Transformer.transform(raw, rule.transforms)
                    if (transformed.isNullOrBlank()) null else transformed
                }
            }
        }
    }

    fun extractString(element: Element, rule: ExtractionRule?, baseUrl: String): String? {
        val res = evaluate(element, rule, baseUrl) ?: return null
        return res.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun extractList(element: Element, rule: ExtractionRule?, baseUrl: String): List<String> {
        val res = evaluate(element, rule, baseUrl) ?: return emptyList()
        return when (res) {
            is List<*> -> res.filterIsInstance<String>()
            is String -> if (res.isNotBlank()) listOf(res) else emptyList()
            else -> listOf(res.toString())
        }
    }

    fun extractInt(element: Element, rule: ExtractionRule?, baseUrl: String): Int? {
        val res = evaluate(element, rule, baseUrl) ?: return null
        return when (res) {
            is Int -> res
            is Number -> res.toInt()
            is String -> Transformer.cast(res, FieldType.INT) as? Int
            else -> null
        }
    }

    fun extractDouble(element: Element, rule: ExtractionRule?, baseUrl: String): Double? {
        val res = evaluate(element, rule, baseUrl) ?: return null
        return when (res) {
            is Double -> res
            is Number -> res.toDouble()
            is String -> Transformer.cast(res, FieldType.DOUBLE) as? Double
            else -> null
        }
    }

    fun extractBoolean(element: Element, rule: ExtractionRule?, baseUrl: String): Boolean? {
        val res = evaluate(element, rule, baseUrl) ?: return null
        return when (res) {
            is Boolean -> res
            is String -> res.lowercase() in listOf("true", "1", "yes", "on")
            else -> null
        }
    }
}
