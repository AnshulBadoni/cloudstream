package com.cloudstream.scraper.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import sun.misc.Unsafe

/**
 * Universal binary compatibility bridge for CloudStream, CloudStream Beta, Pre-release, and Zangetsu.
 *
 * Uses Unsafe.allocateInstance to bypass data class constructor signature drift across host APK versions,
 * preventing NoSuchMethodError / No direct method <init> crashes entirely.
 */
object CloudStreamBridge {

    private val unsafe: Unsafe? by lazy {
        try {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun createMovieLoadResponse(
        api: MainAPI,
        name: String,
        url: String,
        type: TvType,
        dataUrl: String
    ): MovieLoadResponse {
        val res = tryInstantiate<MovieLoadResponse>("com.lagradost.cloudstream3.MovieLoadResponse")
        setField(res, "name", name)
        setField(res, "url", url)
        setField(res, "apiName", api.name)
        setField(res, "type", type)
        setField(res, "dataUrl", dataUrl)
        setField(res, "trailers", mutableListOf<Any>())
        setField(res, "syncData", mutableMapOf<String, String>())
        return res
    }

    @Suppress("UNCHECKED_CAST")
    fun createTvSeriesLoadResponse(
        api: MainAPI,
        name: String,
        url: String,
        type: TvType,
        episodes: List<Episode>
    ): TvSeriesLoadResponse {
        val res = tryInstantiate<TvSeriesLoadResponse>("com.lagradost.cloudstream3.TvSeriesLoadResponse")
        setField(res, "name", name)
        setField(res, "url", url)
        setField(res, "apiName", api.name)
        setField(res, "type", type)
        setField(res, "episodes", episodes)
        setField(res, "trailers", mutableListOf<Any>())
        setField(res, "syncData", mutableMapOf<String, String>())
        return res
    }

    @Suppress("UNCHECKED_CAST")
    fun createMovieSearchResponse(
        api: MainAPI,
        name: String,
        url: String,
        type: TvType
    ): MovieSearchResponse {
        val res = tryInstantiate<MovieSearchResponse>("com.lagradost.cloudstream3.MovieSearchResponse")
        setField(res, "name", name)
        setField(res, "url", url)
        setField(res, "apiName", api.name)
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
        val res = tryInstantiate<TvSeriesSearchResponse>("com.lagradost.cloudstream3.TvSeriesSearchResponse")
        setField(res, "name", name)
        setField(res, "url", url)
        setField(res, "apiName", api.name)
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
        val res = tryInstantiate<ExtractorLink>("com.lagradost.cloudstream3.utils.ExtractorLink")
        setField(res, "source", source)
        setField(res, "name", name)
        setField(res, "url", url)
        setField(res, "referer", referer)
        setField(res, "quality", quality)
        setField(res, "type", type)
        setField(res, "isM3u8", false)
        setField(res, "headers", emptyMap<String, String>())
        return res
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> tryInstantiate(className: String): T {
        val clazz = Class.forName(className)
        val u = unsafe
        if (u != null) {
            try {
                return u.allocateInstance(clazz) as T
            } catch (_: Throwable) {}
        }

        val constructors = (clazz.declaredConstructors + clazz.constructors).distinct().sortedBy { it.parameterTypes.size }
        for (constructor in constructors) {
            try {
                constructor.isAccessible = true
                val paramTypes = constructor.parameterTypes
                val args = arrayOfNulls<Any>(paramTypes.size)
                for (i in paramTypes.indices) {
                    val p = paramTypes[i]
                    when {
                        p == java.lang.Boolean.TYPE -> args[i] = false
                        p == java.lang.Integer.TYPE -> args[i] = 0
                        p == java.lang.Long.TYPE -> args[i] = 0L
                        p == java.lang.Double.TYPE -> args[i] = 0.0
                        p == java.lang.Float.TYPE -> args[i] = 0.0f
                        p == List::class.java -> args[i] = mutableListOf<Any>()
                        p == Map::class.java -> args[i] = mutableMapOf<String, String>()
                        p == Set::class.java -> args[i] = mutableSetOf<Any>()
                        p.name.contains("TvType") -> args[i] = TvType.NSFW
                        else -> args[i] = null
                    }
                }
                return constructor.newInstance(*args) as T
            } catch (_: Throwable) {}
        }

        throw IllegalStateException("Failed to instantiate $className")
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
