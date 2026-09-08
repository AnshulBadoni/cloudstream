package com.cloudstream.scraper.adapters

import com.cloudstream.scraper.config.SiteConfig
import java.util.concurrent.ConcurrentHashMap

object AdapterRegistry {

    private val adapters = ConcurrentHashMap<String, SiteAdapter>()

    fun register(siteId: String, adapter: SiteAdapter) {
        adapters[siteId] = adapter
    }

    fun getAdapter(config: SiteConfig): SiteAdapter {
        // 1. Direct registry by site ID
        adapters[config.id]?.let { return it }

        // 2. Reflection loading via adapterClass specified in SiteConfig
        config.adapterClass?.let { className ->
            try {
                val clazz = Class.forName(className)
                val instance = clazz.kotlin.objectInstance ?: clazz.getDeclaredConstructor().newInstance()
                if (instance is SiteAdapter) {
                    adapters[config.id] = instance
                    return instance
                }
            } catch (e: Exception) {
                // Fall back to default if adapter class loading fails
            }
        }

        // 3. Fallback to default pass-through
        return DefaultSiteAdapter
    }
}
