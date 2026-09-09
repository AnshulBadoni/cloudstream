package com.custom

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CustomProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CustomScraper())
    }
}
