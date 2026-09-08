package com.cloudstream.scraper.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

object JsonParser {

    private val jsonMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
    }

    /**
     * Parse JSON string into Jackson JsonNode.
     */
    fun parse(jsonString: String): JsonNode? {
        return try {
            jsonMapper.readTree(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Evaluates a dot-notation path like "props.pageProps.movie.title" or "items[0].name" on a JsonNode.
     */
    fun extractPath(jsonNode: JsonNode?, path: String): JsonNode? {
        if (jsonNode == null || path.isBlank()) return jsonNode
        val segments = path.split(".")
        var current: JsonNode? = jsonNode

        for (seg in segments) {
            if (current == null || current.isNull) return null

            // Check if segment contains array indexing like "items[0]"
            if (seg.contains("[") && seg.endsWith("]")) {
                val arrayName = seg.substringBefore("[")
                val indexStr = seg.substringAfter("[").substringBefore("]")
                val index = indexStr.toIntOrNull() ?: 0

                val arrayNode = if (arrayName.isNotEmpty()) current.get(arrayName) else current
                current = if (arrayNode != null && arrayNode.isArray && index < arrayNode.size()) {
                    arrayNode.get(index)
                } else {
                    null
                }
            } else {
                current = current.get(seg)
            }
        }
        return current
    }

    /**
     * Extract string value by path.
     */
    fun extractString(jsonString: String, path: String): String? {
        val root = parse(jsonString) ?: return null
        val target = extractPath(root, path) ?: return null
        return if (target.isValueNode) target.asText() else target.toString()
    }
}
