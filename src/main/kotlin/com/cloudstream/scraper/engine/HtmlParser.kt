package com.cloudstream.scraper.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

object HtmlParser {

    /**
     * Parses an HTML string with a specified baseUrl for relative link resolution.
     */
    fun parse(html: String, baseUrl: String): Document {
        return Jsoup.parse(html, baseUrl)
    }

    /**
     * Select elements safely with a CSS selector. Returns empty Elements on invalid selector.
     */
    fun select(element: Element, selector: String?): Elements {
        if (selector.isNullOrBlank()) return Elements()
        return try {
            element.select(selector)
        } catch (e: Exception) {
            Elements()
        }
    }

    /**
     * Select the first matching element safely.
     */
    fun selectFirst(element: Element, selector: String?): Element? {
        if (selector.isNullOrBlank()) return null
        return try {
            element.selectFirst(selector)
        } catch (e: Exception) {
            null
        }
    }
}
