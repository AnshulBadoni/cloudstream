package com.cloudstream.scraper.cloudstream

import android.content.Context
import com.cloudstream.scraper.config.CatalogConfig
import com.cloudstream.scraper.config.DetailsConfig
import com.cloudstream.scraper.config.ExtractionRule
import com.cloudstream.scraper.config.ExtractionType
import com.cloudstream.scraper.config.FieldType
import com.cloudstream.scraper.config.PaginationConfig
import com.cloudstream.scraper.config.PeopleConfig
import com.cloudstream.scraper.config.PersonDetailConfig
import com.cloudstream.scraper.config.SearchConfig
import com.cloudstream.scraper.config.SiteConfig
import com.cloudstream.scraper.config.TransformConfig
import com.cloudstream.scraper.model.CatalogType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PornTrexPlugin : Plugin() {

    override fun load() {
        initPlugin()
    }

    override fun load(context: Context) {
        initPlugin()
    }

    override fun load(context: Any?) {
        initPlugin()
    }

    private fun initPlugin() {
        try {
            val config = buildConfig()
            val provider = GenericCloudStreamProvider(config)
            registerMainAPI(provider)
            println("✓ Successfully registered PornTrex provider: ${provider.name}")
        } catch (e: Throwable) {
            println("Failed to initialize PornTrexPlugin: ${e.message}")
        }
    }

    /** Builds the SiteConfig entirely from Kotlin objects — no Jackson, no YAML, no external deps. */
    private fun buildConfig(): SiteConfig {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cookie" to "confirmed=true; age_verified=1; kt_tplayer=1; kt_lang=en"
        )

        val searchConfig = SearchConfig(
            urlTemplate = "/search/{query}/?page={page}",
            method = "GET",
            itemSelector = ".list-videos .item:has(a[href*='/videos/']), .list-videos .item:has(a.thumb), #list_videos_videos_list_search_result_items .item, #list_videos_common_videos_list_items .item, .video-preview-screen",
            fields = mapOf(
                "id" to ExtractionRule(selector = "a[href*='/videos/'], a.thumb, a", attribute = "href"),
                "title" to ExtractionRule(
                    selector = "strong.title, a.title, p.inf a, .title, a[title]",
                    extraction = ExtractionType.TEXT,
                    transforms = listOf(TransformConfig(trim = true))
                ),
                "url" to ExtractionRule(selector = "a[href*='/videos/'], a.thumb, a", attribute = "href"),
                "posterUrl" to ExtractionRule(
                    selector = "img.thumb, img.cover, img",
                    attribute = "data-src",
                    fallbacks = listOf(
                        ExtractionRule(selector = "img.thumb, img.cover, img", attribute = "src")
                    )
                ),
                "type" to ExtractionRule(default = "NSFW"),
                "rating" to ExtractionRule(
                    selector = ".rating",
                    extraction = ExtractionType.TEXT,
                    transforms = listOf(TransformConfig(regex = "(\\d+)", group = 1)),
                    type = FieldType.DOUBLE
                )
            )
        )

        val videoItemSelector = ".list-videos .item:has(a[href*='/videos/']), .list-videos .item:has(a.thumb), #list_videos_common_videos_list_items .item, #list_videos_latest_videos_list_items .item, #list_videos_top_rated_videos_items .item, #list_videos_most_popular_videos_items .item, .video-preview-screen"

        val catalogs = listOf(
            CatalogConfig(
                id = "latest",
                name = "Latest Videos",
                type = CatalogType.MOVIES,
                urlTemplate = "/latest-updates/?page={page}",
                itemSelector = videoItemSelector,
                pagination = PaginationConfig(type = "query", param = "page", nextSelector = ".pagination .next, a.next")
            ),
            CatalogConfig(
                id = "actors",
                name = "Models & Stars",
                type = CatalogType.PEOPLE,
                urlTemplate = "/models/?page={page}",
                itemSelector = ".list-models .item:has(a[href*='/models/']), .list-models .item:has(a[href*='/pornstars/']), #list_models_models_list_items .item, #list_models_common_models_list_items .item",
                fields = mapOf(
                    "name" to ExtractionRule(
                        selector = "strong.title, .title, a",
                        extraction = ExtractionType.TEXT,
                        transforms = listOf(TransformConfig(trim = true))
                    ),
                    "url" to ExtractionRule(
                        selector = "a[href*='/models/'], a[href*='/pornstars/'], a",
                        attribute = "href",
                        fallbacks = listOf(ExtractionRule(attribute = "href"))
                    ),
                    "photoUrl" to ExtractionRule(
                        selector = "img.thumb, img",
                        attribute = "data-src",
                        fallbacks = listOf(ExtractionRule(selector = "img.thumb, img", attribute = "src"))
                    )
                )
            ),
            CatalogConfig(
                id = "top-rated",
                name = "Top Rated",
                type = CatalogType.MOVIES,
                urlTemplate = "/top-rated/?page={page}",
                itemSelector = videoItemSelector
            ),
            CatalogConfig(
                id = "most-popular",
                name = "Most Popular",
                type = CatalogType.MOVIES,
                urlTemplate = "/most-popular/?page={page}",
                itemSelector = videoItemSelector
            )
        )

        val detailsCommon = mapOf(
            "id" to ExtractionRule(selector = "meta[property='og:url']", attribute = "content"),
            "title" to ExtractionRule(
                selector = "meta[property='og:title']",
                attribute = "content",
                transforms = listOf(
                    TransformConfig(regex = "^(.*?)(?:\\s*\\|.*)?$", group = 1),
                    TransformConfig(trim = true)
                ),
                fallbacks = listOf(
                    ExtractionRule(
                        selector = "h1.title, h1, .headline h1, .video-details h1",
                        extraction = ExtractionType.TEXT,
                        transforms = listOf(
                            TransformConfig(regex = "^(.*?)(?:\\s*\\|.*)?$", group = 1),
                            TransformConfig(trim = true)
                        )
                    ),
                    ExtractionRule(
                        selector = "script:containsData(video_title)",
                        extraction = ExtractionType.TEXT,
                        transforms = listOf(
                            TransformConfig(regex = """(?i)(?:video_title|title)\s*[:=]\s*['"]([^'"]+)['"]""", group = 1),
                            TransformConfig(trim = true)
                        )
                    )
                )
            ),
            "posterUrl" to ExtractionRule(
                selector = "meta[property='og:image']",
                attribute = "content",
                fallbacks = listOf(
                    ExtractionRule(selector = "meta[name='twitter:image']", attribute = "content"),
                    ExtractionRule(selector = "#player-holder video[poster], .player-holder video[poster], video[poster]", attribute = "poster"),
                    ExtractionRule(selector = "link[rel='image_src']", attribute = "href")
                )
            ),
            "description" to ExtractionRule(
                selector = ".videodesc .items-holder em.des-link, .videodesc .des-link, .videodesc .items-holder, .videodesc, .main-container .description-block, .description-block, .video-details, .description",
                extraction = ExtractionType.TEXT,
                transforms = listOf(
                    TransformConfig(replaceRegex = "^Description:\\s*", replacement = ""),
                    TransformConfig(trim = true)
                ),
                fallbacks = listOf(
                    ExtractionRule(selector = "meta[property='og:description']", attribute = "content"),
                    ExtractionRule(selector = "meta[name='description']", attribute = "content")
                )
            ),
            "rating" to ExtractionRule(
                selector = ".vote-percentage, .rating",
                extraction = ExtractionType.TEXT,
                transforms = listOf(TransformConfig(regex = "(\\d+)", group = 1)),
                type = FieldType.DOUBLE
            ),
            "genres" to ExtractionRule(
                selector = ".block-details a[href*='/categories/'], .block-details a[href*='/tags/'], .item-categories a, .item-tags a, .tags a",
                extraction = ExtractionType.TEXT_LIST
            ),
            "tags" to ExtractionRule(
                selector = ".block-details a[href*='/tags/'], .item-tags a, .tags a",
                extraction = ExtractionType.TEXT_LIST
            ),
            "releaseYear" to ExtractionRule(
                selector = ".info-block .item span:has(i.fa-calendar) em.badge, .info-block i.fa-calendar + em, .meta-year, .year",
                extraction = ExtractionType.TEXT,
                transforms = listOf(TransformConfig(relativeYear = true)),
                type = FieldType.INT
            )
        )

        val detailsMovie = mapOf(
            "duration" to ExtractionRule(
                selector = ".info-block .item span:has(i.fa-clock-o) em.badge, .info-block i.fa-clock-o + em, .durations, .duration, .time",
                extraction = ExtractionType.TEXT,
                transforms = listOf(TransformConfig(regex = "(\\d+)", group = 1)),
                type = FieldType.INT
            )
        )

        val peopleConfig = PeopleConfig(
            itemSelector = ".block-details a[href*='/models/'], .block-details a[href*='/pornstars/'], .item-models a, a[href*='/models/'], a[href*='/pornstars/'], .list-models .item",
            fields = mapOf(
                "name" to ExtractionRule(
                    selector = "self",
                    extraction = ExtractionType.TEXT,
                    transforms = listOf(TransformConfig(trim = true)),
                    fallbacks = listOf(
                        ExtractionRule(selector = "strong.title, .title, a", extraction = ExtractionType.TEXT)
                    )
                ),
                "url" to ExtractionRule(selector = "self", attribute = "href", fallbacks = listOf(ExtractionRule(selector = "a", attribute = "href"))),
                "photoUrl" to ExtractionRule(
                    selector = "img.thumb, img",
                    attribute = "data-src",
                    fallbacks = listOf(ExtractionRule(selector = "img.thumb, img", attribute = "src"))
                )
            ),
            detail = PersonDetailConfig(
                name = ExtractionRule(
                    selector = ".profile-model-info h1, .profile-model-info .name h1, h1.title, h1, meta[property='og:title']",
                    extraction = ExtractionType.TEXT,
                    fallbacks = listOf(
                        ExtractionRule(selector = "meta[property='og:title']", attribute = "content")
                    )
                ),
                biography = ExtractionRule(
                    selector = ".profile-model-info .description-block, .profile-model-info .description, .model-description, .main-container .description-block, .description-block",
                    extraction = ExtractionType.TEXT,
                    transforms = listOf(TransformConfig(trim = true)),
                    fallbacks = listOf(
                        ExtractionRule(selector = "meta[property='og:description']", attribute = "content")
                    )
                ),
                photoUrl = ExtractionRule(
                    selector = ".profile-model-info .img-holder img, .profile-model-info img, .img-holder img",
                    attribute = "data-src",
                    fallbacks = listOf(
                        ExtractionRule(selector = ".profile-model-info .img-holder img, .profile-model-info img, .img-holder img", attribute = "src"),
                        ExtractionRule(selector = "meta[property='og:image']", attribute = "content")
                    )
                ),
                knownForSelector = ".list-videos .item:has(a[href*='/videos/']), .list-videos .item:has(a.thumb), .video-preview-screen, .video-item, .item:has(a.thumb)",
                knownForFields = mapOf(
                    "title" to ExtractionRule(selector = "p.inf a, strong.title, .title, a[title]", extraction = ExtractionType.TEXT),
                    "url" to ExtractionRule(selector = "a.thumb, a[href*='/videos/'], a", attribute = "href"),
                    "posterUrl" to ExtractionRule(
                        selector = "img.cover, img.thumb, img",
                        attribute = "data-src",
                        fallbacks = listOf(ExtractionRule(selector = "img.cover, img.thumb, img", attribute = "src"))
                    )
                )
            )
        )

        return SiteConfig(
            id = "porntrex",
            name = "PornTrex",
            baseUrl = "https://www.porntrex.com",
            version = 1,
            isNsfw = true,
            headers = headers,
            search = searchConfig,
            catalogs = catalogs,
            details = DetailsConfig(
                common = detailsCommon,
                movie = detailsMovie
            ),
            people = peopleConfig
        )
    }
}

