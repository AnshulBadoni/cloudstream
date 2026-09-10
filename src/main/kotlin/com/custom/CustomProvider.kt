package com.custom

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CustomProvider Plugin Entry Point for CloudStream.
 *
 * This class is loaded by the CloudStream plugin runtime
 * to register the MultiSource scraper API.
 */
@CloudstreamPlugin
class CustomProvider : Plugin() {
    override fun load(context: Context) {
        // Registers the Multi-Source aggregator and PLibrary providers into CloudStream
        registerMainAPI(CustomScraper())
        registerMainAPI(PLibrary())
    }
}
