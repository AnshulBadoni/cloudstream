package com.cloudstream.scraper.engine

import com.cloudstream.scraper.config.FieldType
import com.cloudstream.scraper.config.TransformConfig
import java.net.URI

object Transformer {

    /**
     * Resolves a potentially relative URL against a base URL.
     * Handles protocols like //example.com, relative paths like /movie/1, and full URLs.
     */
    fun resolveUrl(baseUrl: String, rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            val scheme = if (baseUrl.startsWith("http://")) "http:" else "https:"
            return "$scheme$trimmed"
        }
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(trimmed).toString()
        } catch (e: Exception) {
            if (baseUrl.endsWith("/") && trimmed.startsWith("/")) {
                baseUrl.removeSuffix("/") + trimmed
            } else if (!baseUrl.endsWith("/") && !trimmed.startsWith("/")) {
                "$baseUrl/$trimmed"
            } else {
                "$baseUrl$trimmed"
            }
        }
    }

    /**
     * Applies a sequence of transformations to a raw string value.
     */
    fun transform(value: String?, transforms: List<TransformConfig>): String? {
        if (value == null) return null
        var current: String? = value

        for (t in transforms) {
            if (current == null) break

            if (t.trim == true) {
                current = current.trim()
            }
            if (t.removeWhitespace == true) {
                current = current.replace("\\s+".toRegex(), " ").trim()
            }
            if (t.lowercase == true) {
                current = current.lowercase()
            }
            if (t.uppercase == true) {
                current = current.uppercase()
            }
            t.regex?.let { pattern ->
                val match = Regex(pattern).find(current!!)
                current = if (match != null && t.group <= match.groupValues.size - 1) {
                    match.groupValues[t.group]
                } else {
                    null
                }
            }
            t.replace?.let { target ->
                current = current?.replace(target, t.replacement)
            }
            t.replaceRegex?.let { regexPattern ->
                current = current?.replace(Regex(regexPattern), t.replacement)
            }
            if (t.relativeYear == true && current != null) {
                val currentYear = java.time.Year.now().value
                val yearsAgoMatch = Regex("""(\d+)\s*years?\s*ago""", RegexOption.IGNORE_CASE).find(current!!)
                if (yearsAgoMatch != null) {
                    val yearsAgo = yearsAgoMatch.groupValues[1].toIntOrNull() ?: 0
                    current = (currentYear - yearsAgo).toString()
                } else if (Regex("""(?:months?|days?|hours?|weeks?|yesterday|today|just now)""", RegexOption.IGNORE_CASE).containsMatchIn(current!!)) {
                    current = currentYear.toString()
                } else {
                    val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(current!!)
                    if (yearMatch != null) {
                        current = yearMatch.groupValues[1]
                    }
                }
            }
            if (t.map.isNotEmpty()) {
                current = t.map[current] ?: current
            }
            t.prepend?.let { prefix ->
                current = "$prefix$current"
            }
            t.append?.let { suffix ->
                current = "$current$suffix"
            }
        }

        return current
    }

    /**
     * Converts a string value into the target FieldType.
     */
    fun cast(value: String?, type: FieldType, default: String? = null): Any? {
        val effectiveValue = value ?: default ?: return null
        return try {
            when (type) {
                FieldType.STRING -> effectiveValue
                FieldType.INT -> {
                    Regex("(-?\\d+)").find(effectiveValue)?.value?.toIntOrNull()
                }
                FieldType.DOUBLE -> {
                    Regex("(-?\\d+(?:\\.\\d+)?)").find(effectiveValue)?.value?.toDoubleOrNull()
                }
                FieldType.BOOLEAN -> effectiveValue.lowercase() in listOf("true", "1", "yes", "on")
                FieldType.LIST -> listOf(effectiveValue)
            }
        } catch (e: Exception) {
            null
        }
    }
}
