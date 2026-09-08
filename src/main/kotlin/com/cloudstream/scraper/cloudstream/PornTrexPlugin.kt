package com.cloudstream.scraper.cloudstream

import android.content.Context
import com.cloudstream.scraper.config.ConfigLoader
import com.cloudstream.scraper.config.SiteConfig
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PornTrexPlugin : Plugin() {

    init {
        initPlugin()
    }

    override fun load(context: Context) {
        initPlugin()
    }

    override fun load(context: Any?) {
        initPlugin()
    }

    private var initialized = false

    private fun initPlugin() {
        if (initialized) return
        initialized = true
        try {
            val config = loadConfig()
            val provider = GenericCloudStreamProvider(config)
            registerMainAPI(provider)
            tryRegisterPluginManager(provider)
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

    private fun tryRegisterPluginManager(provider: Any) {
        try {
            val pluginManagerClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
            val instanceField = pluginManagerClass.declaredFields.firstOrNull { it.name == "INSTANCE" }
            val instance = instanceField?.get(null) ?: pluginManagerClass.getDeclaredConstructor().newInstance()
            val registerMethod = pluginManagerClass.declaredMethods.firstOrNull {
                it.name == "registerPlugin" || it.name == "registerMainAPI"
            }
            registerMethod?.isAccessible = true
            registerMethod?.invoke(instance, provider)
        } catch (_: Throwable) {
        }
    }
}
