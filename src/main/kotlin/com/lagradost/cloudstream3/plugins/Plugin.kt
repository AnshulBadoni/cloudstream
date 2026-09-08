package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin

abstract class Plugin {
    open var resources: Any? = null
    open var filename: String? = null
    val registeredAPIs = mutableListOf<MainAPI>()

    open fun load(context: Context) {}
    open fun load(context: Any? = null) {}

    fun registerMainAPI(api: MainAPI) {
        registeredAPIs.add(api)
    }
}
