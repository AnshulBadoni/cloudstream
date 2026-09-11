package com.custom

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CustomProvider Plugin Entry Point for CloudStream.
 * Registers separated standalone high-performance providers and multi-source aggregator.
 */
@CloudstreamPlugin
class CustomProvider : Plugin() {
    override fun load(context: Context) {
        // Register separated standalone providers and multi-source aggregator
        registerMainAPI(YamyHub())
        registerMainAPI(DaftSex())
        registerMainAPI(TnaFlix())
        registerMainAPI(FPO())
        registerMainAPI(CustomScraper())

        // Load persisted settings
        try {
            val prefs = context.javaClass.getMethod("getSharedPreferences", String::class.java, Int::class.javaPrimitiveType)
                .invoke(context, "Anshul_CustomProviders_Settings", 0)
            val getInt = prefs.javaClass.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)

            val sPages = getInt.invoke(prefs, "search_pages", 2) as? Int ?: 2
            val mPages = getInt.invoke(prefs, "model_pages", 2) as? Int ?: 2

            YamyHub.searchPages = sPages
            YamyHub.modelPages = mPages
            DaftSex.searchPages = sPages
            DaftSex.modelPages = mPages
            TnaFlix.searchPages = sPages
            TnaFlix.modelPages = mPages
            FPO.searchPages = sPages
            FPO.modelPages = mPages
        } catch (_: Exception) {}

        // Customization dialog for page scrape depth
        this.openSettings = { ctx ->
            try {
                val prefs = ctx.javaClass.getMethod("getSharedPreferences", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(ctx, "Anshul_CustomProviders_Settings", 0)

                val builderClass = Class.forName("android.app.AlertDialog\$Builder")
                val listenerClass = Class.forName("android.content.DialogInterface\$OnClickListener")

                val mainOptions = arrayOf<CharSequence>(
                    "Search Scrape Depth: ${YamyHub.searchPages} Page(s)",
                    "Model Videos Depth: ${YamyHub.modelPages} Page(s)"
                )

                val mainBuilder = builderClass.getConstructor(Class.forName("android.content.Context")).newInstance(ctx)
                builderClass.getMethod("setTitle", CharSequence::class.java).invoke(mainBuilder, "Provider Scraper Settings")

                val mainListener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass)
                ) { _, method, args ->
                    if (method?.name == "onClick") {
                        val which = args?.getOrNull(1) as? Int ?: 0
                        val mainDialog = args?.getOrNull(0)
                        mainDialog?.javaClass?.getMethod("dismiss")?.invoke(mainDialog)

                        if (which == 0) {
                            // Search depth dialog
                            val searchOptions = arrayOf<CharSequence>(
                                "1 Page (Fastest)",
                                "2 Pages (Default / Recommended)",
                                "3 Pages (Deep)",
                                "4 Pages (Extended)",
                                "5 Pages (Maximum)"
                            )
                            val currentSearch = (YamyHub.searchPages - 1).coerceIn(0, 4)
                            val sBuilder = builderClass.getConstructor(Class.forName("android.content.Context")).newInstance(ctx)
                            builderClass.getMethod("setTitle", CharSequence::class.java).invoke(sBuilder, "Search Scrape Depth")

                            val sListener = java.lang.reflect.Proxy.newProxyInstance(
                                listenerClass.classLoader,
                                arrayOf(listenerClass)
                            ) { _, sMethod, sArgs ->
                                if (sMethod?.name == "onClick") {
                                    val sWhich = sArgs?.getOrNull(1) as? Int ?: 1
                                    val pages = sWhich + 1

                                    YamyHub.searchPages = pages
                                    DaftSex.searchPages = pages
                                    TnaFlix.searchPages = pages
                                    FPO.searchPages = pages

                                    val editor = prefs.javaClass.getMethod("edit").invoke(prefs)
                                    editor.javaClass.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType).invoke(editor, "search_pages", pages)
                                    editor.javaClass.getMethod("apply").invoke(editor)

                                    val sDialog = sArgs?.getOrNull(0)
                                    sDialog?.javaClass?.getMethod("dismiss")?.invoke(sDialog)
                                }
                                null
                            }
                            builderClass.getMethod("setSingleChoiceItems", Array<CharSequence>::class.java, Int::class.javaPrimitiveType, listenerClass)
                                .invoke(sBuilder, searchOptions, currentSearch, sListener)
                            builderClass.getMethod("setNegativeButton", CharSequence::class.java, listenerClass)
                                .invoke(sBuilder, "Cancel", null)
                            builderClass.getMethod("show").invoke(sBuilder)
                        } else {
                            // Model depth dialog
                            val modelOptions = arrayOf<CharSequence>(
                                "1 Page (Fastest)",
                                "2 Pages (Default / Recommended)",
                                "3 Pages (Deep)",
                                "5 Pages (Extended)",
                                "10 Pages (Maximum)"
                            )
                            val modelValues = intArrayOf(1, 2, 3, 5, 10)
                            val currentModel = modelValues.indexOf(YamyHub.modelPages).let { if (it >= 0) it else 1 }

                            val mBuilder = builderClass.getConstructor(Class.forName("android.content.Context")).newInstance(ctx)
                            builderClass.getMethod("setTitle", CharSequence::class.java).invoke(mBuilder, "Model Profile Scrape Depth")

                            val mListener = java.lang.reflect.Proxy.newProxyInstance(
                                listenerClass.classLoader,
                                arrayOf(listenerClass)
                            ) { _, mMethod, mArgs ->
                                if (mMethod?.name == "onClick") {
                                    val mWhich = mArgs?.getOrNull(1) as? Int ?: 1
                                    val pages = modelValues.getOrElse(mWhich) { 2 }

                                    YamyHub.modelPages = pages
                                    DaftSex.modelPages = pages
                                    TnaFlix.modelPages = pages
                                    FPO.modelPages = pages

                                    val editor = prefs.javaClass.getMethod("edit").invoke(prefs)
                                    editor.javaClass.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType).invoke(editor, "model_pages", pages)
                                    editor.javaClass.getMethod("apply").invoke(editor)

                                    val mDialog = mArgs?.getOrNull(0)
                                    mDialog?.javaClass?.getMethod("dismiss")?.invoke(mDialog)
                                }
                                null
                            }
                            builderClass.getMethod("setSingleChoiceItems", Array<CharSequence>::class.java, Int::class.javaPrimitiveType, listenerClass)
                                .invoke(mBuilder, modelOptions, currentModel, mListener)
                            builderClass.getMethod("setNegativeButton", CharSequence::class.java, listenerClass)
                                .invoke(mBuilder, "Cancel", null)
                            builderClass.getMethod("show").invoke(mBuilder)
                        }
                    }
                    null
                }

                builderClass.getMethod("setItems", Array<CharSequence>::class.java, listenerClass)
                    .invoke(mainBuilder, mainOptions, mainListener)
                builderClass.getMethod("setNegativeButton", CharSequence::class.java, listenerClass)
                    .invoke(mainBuilder, "Close", null)
                builderClass.getMethod("show").invoke(mainBuilder)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
