package com.custom

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Dedicated Plugin for Himeros Master Aggregator
 */
@CloudstreamPlugin
class HimerosProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Himeros())
        ProviderSettingsHelper.initSettings(context)
        this.openSettings = { ctx -> ProviderSettingsHelper.openSettingsDialog(ctx) }
    }
}
