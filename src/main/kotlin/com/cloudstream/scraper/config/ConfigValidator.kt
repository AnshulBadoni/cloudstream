package com.cloudstream.scraper.config

class ConfigValidationException(val errors: List<String>) :
    IllegalArgumentException("SiteConfig validation failed:\n" + errors.joinToString("\n") { " - $it" })

class ConfigParseException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

object ConfigValidator {

    private const val SUPPORTED_VERSION = 1

    /**
     * Validates a SiteConfig object for completeness, semantic correctness, and safety.
     * Throws ConfigValidationException if validation errors are found.
     */
    fun validate(config: SiteConfig) {
        val errors = mutableListOf<String>()

        // Core fields
        if (config.id.isBlank()) {
            errors.add("Site 'id' must not be blank")
        } else if (!config.id.matches(Regex("^[a-z0-9_-]+$"))) {
            errors.add("Site 'id' ('${config.id}') must contain only lowercase letters, digits, hyphens, and underscores")
        }

        if (config.name.isBlank()) {
            errors.add("Site 'name' must not be blank")
        }

        if (config.baseUrl.isBlank()) {
            errors.add("Site 'baseUrl' must not be blank")
        } else if (!config.baseUrl.startsWith("http://") && !config.baseUrl.startsWith("https://")) {
            errors.add("Site 'baseUrl' ('${config.baseUrl}') must start with http:// or https://")
        }

        if (config.version != SUPPORTED_VERSION) {
            errors.add("Site config version ${config.version} is not supported (current supported version is $SUPPORTED_VERSION)")
        }

        // Search validation
        config.search?.let { search ->
            if (search.urlTemplate.isBlank()) {
                errors.add("search.urlTemplate must not be blank if search is configured")
            } else if (!search.urlTemplate.contains("{query}")) {
                errors.add("search.urlTemplate must contain '{query}' placeholder")
            }

            if (search.itemSelector.isBlank()) {
                errors.add("search.itemSelector must not be blank if search is configured")
            }

            validateExtractionRuleMap("search.fields", search.fields, errors)
        }

        // Catalogs validation
        val catalogIds = mutableSetOf<String>()
        config.catalogs.forEachIndexed { index, catalog ->
            val prefix = "catalogs[$index]"
            if (catalog.id.isBlank()) {
                errors.add("$prefix.id must not be blank")
            } else if (!catalogIds.add(catalog.id)) {
                errors.add("Duplicate catalog id '${catalog.id}' found at $prefix")
            }

            if (catalog.name.isBlank()) {
                errors.add("$prefix.name must not be blank")
            }

            if (catalog.urlTemplate.isBlank()) {
                errors.add("$prefix.urlTemplate must not be blank")
            }

            validateExtractionRuleMap("$prefix.fields", catalog.fields, errors)
        }

        // Details validation
        config.details?.let { details ->
            validateExtractionRuleMap("details.common", details.common, errors)
            validateExtractionRuleMap("details.movie", details.movie, errors)

            details.series?.let { series ->
                if (series.episodesSelector.isBlank()) {
                    errors.add("details.series.episodesSelector must not be blank")
                }
                series.seasonNumber?.let { rule ->
                    validateExtractionRule("details.series.seasonNumber", rule, errors)
                }
                validateExtractionRuleMap("details.series.episode", series.episode, errors)
            }
        }

        // People validation
        config.people?.let { people ->
            validateExtractionRuleMap("people.fields", people.fields, errors)
            people.detail?.let { detail ->
                detail.name?.let { validateExtractionRule("people.detail.name", it, errors) }
                detail.photoUrl?.let { validateExtractionRule("people.detail.photoUrl", it, errors) }
                detail.biography?.let { validateExtractionRule("people.detail.biography", it, errors) }
                detail.birthDate?.let { validateExtractionRule("people.detail.birthDate", it, errors) }
                validateExtractionRuleMap("people.detail.knownForFields", detail.knownForFields, errors)
            }
        }

        if (errors.isNotEmpty()) {
            throw ConfigValidationException(errors)
        }
    }

    private fun validateExtractionRuleMap(
        prefix: String,
        rules: Map<String, ExtractionRule>,
        errors: MutableList<String>
    ) {
        rules.forEach { (key, rule) ->
            validateExtractionRule("$prefix.$key", rule, errors)
        }
    }

    private fun validateExtractionRule(
        fieldPath: String,
        rule: ExtractionRule,
        errors: MutableList<String>
    ) {
        if (rule.extraction == ExtractionType.ATTRIBUTE || rule.extraction == ExtractionType.ATTRIBUTE_LIST) {
            if (rule.attribute.isNullOrBlank()) {
                errors.add("$fieldPath specifies extraction='${rule.extraction}' but missing 'attribute' name")
            }
        }

        rule.transforms.forEachIndexed { idx, transform ->
            transform.regex?.let { pattern ->
                try {
                    Regex(pattern)
                } catch (e: Exception) {
                    errors.add("$fieldPath.transforms[$idx] has invalid regex '$pattern': ${e.message}")
                }
            }
        }

        rule.fallbacks.forEachIndexed { idx, fallback ->
            validateExtractionRule("$fieldPath.fallbacks[$idx]", fallback, errors)
        }
    }
}
