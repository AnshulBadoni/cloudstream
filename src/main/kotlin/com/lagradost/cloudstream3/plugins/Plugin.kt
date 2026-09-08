package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin

abstract class BasePlugin {
    open var filename: String? = null
    val registeredAPIs = mutableListOf<MainAPI>()

    open fun load() {}

    fun registerMainAPI(api: MainAPI) {
        registeredAPIs.add(api)
    }
}

abstract class Plugin : BasePlugin() {
    open var resources: Any? = null

    open fun load(context: Context) {
        load()
    }
    open fun load(context: Any?) {
        load()
    }
}
