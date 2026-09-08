package com.cloudstream.scraper.cloudstream

import com.lagradost.cloudstream3.*

/**
 * Universal binary compatibility bridge for CloudStream, CloudStream Beta, Pre-release, and Zangetsu.
 *
 * Dynamically resolves constructors and properties at runtime to prevent NoSuchMethodError /
 * No direct method <init> crashes caused by data class constructor signature drift across host APK versions.
 */
object CloudStreamBridge {

    @Suppress("UNCHECKED_CAST")
    fun createMovieLoadResponse(
        api: MainAPI,
        name: String,
        url: String,
        type: TvType,
        dataUrl: String
    ): MovieLoadResponse {
        return tryCreateInstance("com.lagradost.cloudstream3.MovieLoadResponse", api, name, url, type, dataUrl)
    }

    @Suppress("UNCHECKED_CAST")
    fun createTvSeriesLoadResponse(
        api: MainAPI,
        name: String,
        url: String,
        type: TvType,
        episodes: List<Episode>
    ): TvSeriesLoadResponse {
        return tryCreateInstance("com.lagradost.cloudstream3.TvSeriesLoadResponse", api, name, url, type, episodes)
    }

    @Suppress("UNCHECKED_CAST")
    fun createMovieSearchResponse(
        api: MainAPI,
        name: String,
        url: String,
        type: TvType
    ): MovieSearchResponse {
        return tryCreateInstance("com.lagradost.cloudstream3.MovieSearchResponse", api, name, url, type, null)
    }

    @Suppress("UNCHECKED_CAST")
    fun createTvSeriesSearchResponse(
        api: MainAPI,
        name: String,
        url: String,
        type: TvType
    ): TvSeriesSearchResponse {
        return tryCreateInstance("com.lagradost.cloudstream3.TvSeriesSearchResponse", api, name, url, type, null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> tryCreateInstance(
        className: String,
        api: MainAPI,
        name: String,
        url: String,
        type: TvType,
        extraData: Any?
    ): T {
        val clazz = Class.forName(className)
        val constructors = clazz.constructors.sortedByDescending { it.parameterTypes.size }

        for (constructor in constructors) {
            try {
                val paramTypes = constructor.parameterTypes
                val args = arrayOfNulls<Any>(paramTypes.size)
                var stringCount = 0

                for (i in paramTypes.indices) {
                    val pType = paramTypes[i]
                    when {
                        pType == String::class.java -> {
                            when (stringCount) {
                                0 -> args[i] = name
                                1 -> args[i] = url
                                2 -> args[i] = api.name
                                3 -> args[i] = if (extraData is String) extraData else url
                                else -> args[i] = null
                            }
                            stringCount++
                        }
                        pType.name.contains("TvType") -> args[i] = type
                        pType == List::class.java -> {
                            if (extraData is List<*>) {
                                args[i] = extraData
                            } else {
                                args[i] = mutableListOf<Any>()
                            }
                        }
                        pType == Map::class.java -> {
                            args[i] = mutableMapOf<String, String>()
                        }
                        pType == Set::class.java -> {
                            args[i] = mutableSetOf<Any>()
                        }
                        pType == java.lang.Boolean.TYPE -> args[i] = false
                        pType == java.lang.Integer.TYPE -> args[i] = 0
                        pType == java.lang.Long.TYPE -> args[i] = 0L
                        pType == java.lang.Double.TYPE -> args[i] = 0.0
                        pType == java.lang.Float.TYPE -> args[i] = 0.0f
                        pType.name.contains("DefaultConstructorMarker") -> args[i] = null
                        else -> args[i] = null
                    }
                }

                return constructor.newInstance(*args) as T
            } catch (_: Throwable) {
                // Try next available constructor
            }
        }

        throw IllegalStateException("Failed to instantiate $className on any available constructor")
    }

    fun setField(target: Any, name: String, value: Any?) {
        if (value == null) return
        try {
            val setterName = "set" + name.replaceFirstChar { it.uppercase() }
            val method = target.javaClass.methods.firstOrNull {
                it.name == setterName && it.parameterTypes.size == 1 && (
                    it.parameterTypes[0].isAssignableFrom(value.javaClass) ||
                    (it.parameterTypes[0] == java.lang.Integer.TYPE && value is Int) ||
                    (it.parameterTypes[0] == java.lang.Boolean.TYPE && value is Boolean) ||
                    (it.parameterTypes[0] == java.lang.Long.TYPE && value is Long) ||
                    (it.parameterTypes[0] == java.lang.Double.TYPE && value is Double)
                )
            }
            if (method != null) {
                method.invoke(target, value)
                return
            }

            val field = target.javaClass.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
            }
        } catch (_: Throwable) {}
    }
}
