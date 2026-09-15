package com.LimeTorrents

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LimeTorrentsProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LimeTorrents())
    }
}
