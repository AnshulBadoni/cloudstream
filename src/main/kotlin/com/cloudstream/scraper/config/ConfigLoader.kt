package com.cloudstream.scraper.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.io.InputStream

object ConfigLoader {

    private val yamlMapper = ObjectMapper(YAMLFactory()).apply {
        registerModule(KotlinModule.Builder().build())
    }

    /**
     * Parse a YAML string into a validated SiteConfig.
     */
    fun loadFromString(yamlContent: String, validate: Boolean = true): SiteConfig {
        try {
            val config = yamlMapper.readValue(yamlContent, SiteConfig::class.java)
                ?: throw ConfigParseException("Parsed YAML is null")
            if (validate) {
                ConfigValidator.validate(config)
            }
            return config
        } catch (e: ConfigValidationException) {
            throw e
        } catch (e: Exception) {
            throw ConfigParseException("Failed to parse YAML site configuration: ${e.message}", e)
        }
    }

    /**
     * Parse a YAML file into a validated SiteConfig.
     */
    fun loadFromFile(file: File, validate: Boolean = true): SiteConfig {
        if (!file.exists()) {
            throw ConfigParseException("Site configuration file not found: ${file.absolutePath}")
        }
        return loadFromString(file.readText(Charsets.UTF_8), validate)
    }

    /**
     * Parse a YAML resource from the classpath into a validated SiteConfig.
     */
    fun loadFromResource(resourcePath: String, validate: Boolean = true): SiteConfig {
        val stream: InputStream = ConfigLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw ConfigParseException("Resource not found: $resourcePath")
        val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return loadFromString(content, validate)
    }

    /**
     * Discover and load all .yaml / .yml configs from a directory.
     */
    fun loadAllFromDirectory(dir: File, validate: Boolean = true): List<SiteConfig> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("yaml", ignoreCase = true) || it.extension.equals("yml", ignoreCase = true)) }
            .map { loadFromFile(it, validate) }
            .toList()
    }
}
