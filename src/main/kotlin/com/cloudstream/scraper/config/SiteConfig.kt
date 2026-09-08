package com.cloudstream.scraper.config

import com.cloudstream.scraper.model.CatalogType
import com.cloudstream.scraper.model.MediaType
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SiteConfig(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val version: Int = 1,
    val status: String = "active",
    val isNsfw: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val search: SearchConfig? = null,
    val catalogs: List<CatalogConfig> = emptyList(),
    val details: DetailsConfig? = null,
    val people: PeopleConfig? = null,
    val sources: SourcesConfig? = null,
    val adapterClass: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchConfig(
    val urlTemplate: String = "",
    val method: String = "GET",
    val itemSelector: String = "",
    val fields: Map<String, ExtractionRule> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogConfig(
    val id: String = "",
    val name: String = "",
    val type: CatalogType = CatalogType.MIXED,
    val urlTemplate: String = "",
    val itemSelector: String? = null,
    val fields: Map<String, ExtractionRule> = emptyMap(),
    val pagination: PaginationConfig? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaginationConfig(
    val type: String = "query",
    val param: String = "page",
    val nextSelector: String? = null
)

enum class ExtractionType {
    @JsonProperty("text")
    TEXT,
    @JsonProperty("attribute")
    ATTRIBUTE,
    @JsonProperty("html")
    HTML,
    @JsonProperty("text_list")
    TEXT_LIST,
    @JsonProperty("attribute_list")
    ATTRIBUTE_LIST
}

enum class FieldType {
    @JsonProperty("string")
    STRING,
    @JsonProperty("int")
    INT,
    @JsonProperty("double")
    DOUBLE,
    @JsonProperty("boolean")
    BOOLEAN,
    @JsonProperty("list")
    LIST
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractionRule(
    val selector: String? = null,
    val extraction: ExtractionType = ExtractionType.TEXT,
    val attribute: String? = null,
    val transforms: List<TransformConfig> = emptyList(),
    val fallbacks: List<ExtractionRule> = emptyList(),
    val type: FieldType = FieldType.STRING,
    val default: String? = null,
    val jsonPath: String? = null,
    val required: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TransformConfig(
    val trim: Boolean? = null,
    val lowercase: Boolean? = null,
    val uppercase: Boolean? = null,
    val removeWhitespace: Boolean? = null,
    val regex: String? = null,
    val group: Int = 1,
    val replace: String? = null,
    val replaceRegex: String? = null,
    val replacement: String = "",
    val relativeYear: Boolean? = null,
    val map: Map<String, String> = emptyMap(),
    val prepend: String? = null,
    val append: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DetailsConfig(
    val typeDetector: TypeDetectorConfig? = null,
    val common: Map<String, ExtractionRule> = emptyMap(),
    val movie: Map<String, ExtractionRule> = emptyMap(),
    val series: SeriesConfig? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TypeDetectorConfig(
    val selector: String = "",
    val existsAs: MediaType = MediaType.TV_SERIES,
    val default: MediaType = MediaType.NSFW
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeriesConfig(
    val seasonsSelector: String? = null,
    val seasonNumber: ExtractionRule? = null,
    val episodesSelector: String = "",
    val episode: Map<String, ExtractionRule> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PeopleConfig(
    val itemSelector: String? = null,
    val fields: Map<String, ExtractionRule> = emptyMap(),
    val detail: PersonDetailConfig? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PersonDetailConfig(
    val name: ExtractionRule? = null,
    val photoUrl: ExtractionRule? = null,
    val biography: ExtractionRule? = null,
    val birthDate: ExtractionRule? = null,
    val knownForSelector: String? = null,
    val knownForFields: Map<String, ExtractionRule> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourcesConfig(
    val extractorSelector: String? = null,
    val extractorAttribute: String? = null,
    val directStreamSelector: String? = null,
    val directStreamAttribute: String? = null,
    val jsonScriptSelector: String? = null,
    val jsonPath: String? = null
)
