package com.cloudstream.scraper.cloudstream

import com.cloudstream.scraper.config.ConfigLoader

@CloudstreamPlugin
class PornTrexPlugin : Plugin() {
    override fun load(context: Any?) {
        try {
            val config = ConfigLoader.loadFromResource("/sites/first-site.yaml")
            registerMainAPI(GenericCloudStreamProvider(config))
        } catch (e: Exception) {
            println("Failed to initialize PornTrexPlugin: ${e.message}")
        }
    }
}
