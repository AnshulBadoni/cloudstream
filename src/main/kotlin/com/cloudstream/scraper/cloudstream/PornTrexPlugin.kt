package com.cloudstream.scraper.cloudstream

import android.content.Context
import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.config.SiteConfig
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PornTrexPlugin : Plugin() {

    override fun load(context: Context) {
        initPlugin()
    }

    override fun load(context: Any?) {
        initPlugin()
    }

    private fun initPlugin() {
        try {
            val config = loadConfig()
            val provider = GenericCloudStreamProvider(config)
            registerMainAPI(provider)
            println("✓ Successfully registered PornTrex provider: ${provider.name}")
        } catch (e: Throwable) {
            println("Failed to initialize PornTrexPlugin: ${e.message}")
        }
    }

    private fun loadConfig(): SiteConfig {
        return try {
            ConfigLoader.loadFromResource("/sites/first-site.yaml")
        } catch (e: Throwable) {
            ConfigLoader.loadFromString(EMBEDDED_PORNTREX_YAML)
        }
    }
}
