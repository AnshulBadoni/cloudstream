package com.cloudstream.scraper.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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
        val res: MovieSearchResponse = tryCreateInstance("com.lagradost.cloudstream3.MovieSearchResponse", api, name, url, type, null)
        setField(res, "type", type)
        return res
    }

    @Suppress("UNCHECKED_CAST")
    fun createTvSeriesSearchResponse(
        api: MainAPI,
        name: String,
        url: String,
        type: TvType
    ): TvSeriesSearchResponse {
        val res: TvSeriesSearchResponse = tryCreateInstance("com.lagradost.cloudstream3.TvSeriesSearchResponse", api, name, url, type, null)
        setField(res, "type", type)
        return res
    }


    @Suppress("UNCHECKED_CAST")
    fun createExtractorLink(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO
    ): ExtractorLink {
        val clazz = ExtractorLink::class.java
        val constructors = (clazz.declaredConstructors + clazz.constructors).distinct().sortedByDescending { it.parameterTypes.size }

        for (constructor in constructors) {
            try {
                constructor.isAccessible = true
                val paramTypes = constructor.parameterTypes
                val args = arrayOfNulls<Any>(paramTypes.size)
                var stringCount = 0

                for (i in paramTypes.indices) {
                    val pType = paramTypes[i]
                    when {
                        pType == String::class.java -> {
                            when (stringCount) {
                                0 -> args[i] = source
                                1 -> args[i] = name
                                2 -> args[i] = url
                                3 -> args[i] = referer
                                else -> args[i] = null
                            }
                            stringCount++
                        }
                        pType == java.lang.Integer.TYPE || pType == Integer::class.java -> args[i] = quality
                        pType == java.lang.Boolean.TYPE || pType == java.lang.Boolean::class.java -> args[i] = false
                        pType == Map::class.java -> args[i] = emptyMap<String, String>()
                        pType.name.contains("ExtractorLinkType") -> args[i] = type
                        pType.name.contains("DefaultConstructorMarker") -> args[i] = null
                        else -> args[i] = null
                    }
                }
                val instance = constructor.newInstance(*args) as ExtractorLink
                setField(instance, "quality", quality)
                setField(instance, "referer", referer)
                return instance
            } catch (_: Throwable) {}
        }

        return ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = referer,
            quality = quality,
            type = type
        )
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
        val constructors = (clazz.declaredConstructors + clazz.constructors).distinct().sortedByDescending { it.parameterTypes.size }

        for (constructor in constructors) {
            try {
                constructor.isAccessible = true
                val paramTypes = constructor.parameterTypes
                val args = arrayOfNulls<Any>(paramTypes.size)
                var stringCount = 0

                val isSynthetic = paramTypes.isNotEmpty() && paramTypes.last().name.contains("DefaultConstructorMarker")

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
                        pType == java.lang.Integer.TYPE -> {
                            if (isSynthetic && i == paramTypes.size - 2) {
                                args[i] = -1
                            } else {
                                args[i] = 0
                            }
                        }
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

            // 1. Try public setter method
            val methods = target.javaClass.methods.filter { it.name == setterName && it.parameterTypes.size == 1 }
            for (method in methods) {
                val paramType = method.parameterTypes[0]
                try {
                    when {
                        paramType.isAssignableFrom(value.javaClass) -> {
                            method.invoke(target, value)
                            return
                        }
                        (paramType == java.lang.Integer.TYPE || paramType == Integer::class.java) && value is Int -> {
                            method.invoke(target, value)
                            return
                        }
                        (paramType == java.lang.Boolean.TYPE || paramType == java.lang.Boolean::class.java) && value is Boolean -> {
                            method.invoke(target, value)
                            return
                        }
                        (paramType == java.lang.Long.TYPE || paramType == java.lang.Long::class.java) && value is Long -> {
                            method.invoke(target, value)
                            return
                        }
                        (paramType == java.lang.Double.TYPE || paramType == java.lang.Double::class.java) && value is Double -> {
                            method.invoke(target, value)
                            return
                        }
                        paramType.name.contains("Score") && value is Int -> {
                            method.invoke(target, Score.from(value, 100))
                            return
                        }
                    }
                } catch (_: Throwable) {}
            }

            // 2. Try declared fields via reflection
            var currentClass: Class<*>? = target.javaClass
            while (currentClass != null && currentClass != Any::class.java) {
                val field = currentClass.declaredFields.firstOrNull { it.name == name }
                if (field != null) {
                    field.isAccessible = true
                    val fieldType = field.type
                    when {
                        fieldType.isAssignableFrom(value.javaClass) -> {
                            field.set(target, value)
                            return
                        }
                        (fieldType == java.lang.Integer.TYPE || fieldType == Integer::class.java) && value is Int -> {
                            field.set(target, value)
                            return
                        }
                        (fieldType == java.lang.Boolean.TYPE || fieldType == java.lang.Boolean::class.java) && value is Boolean -> {
                            field.set(target, value)
                            return
                        }
                        fieldType.name.contains("Score") && value is Int -> {
                            field.set(target, Score.from(value, 100))
                            return
                        }
                    }
                }
                currentClass = currentClass.superclass
            }
        } catch (_: Throwable) {}
    }
}

