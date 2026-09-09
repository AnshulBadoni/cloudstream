package com.megix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PorntrexProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Porntrex())

        try {
            val prefs = context.javaClass.getMethod("getSharedPreferences", String::class.java, Int::class.javaPrimitiveType)
                .invoke(context, "Porntrex_Settings", 0)
            val getInt = prefs.javaClass.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            Porntrex.searchPages = getInt.invoke(prefs, "search_pages", 2) as? Int ?: 2
            Porntrex.modelPages = getInt.invoke(prefs, "model_pages", 3) as? Int ?: 3
        } catch (_: Exception) {}

        this.openSettings = { ctx ->
            try {
                val prefs = ctx.javaClass.getMethod("getSharedPreferences", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(ctx, "Porntrex_Settings", 0)

                val builderClass = Class.forName("android.app.AlertDialog\$Builder")
                val listenerClass = Class.forName("android.content.DialogInterface\$OnClickListener")

                val mainOptions = arrayOf<CharSequence>(
                    "Search Results Depth: ${Porntrex.searchPages} Page(s)",
                    "Model Videos Depth: ${Porntrex.modelPages} Page(s)"
                )

                val mainBuilder = builderClass.getConstructor(Class.forName("android.content.Context")).newInstance(ctx)
                builderClass.getMethod("setTitle", CharSequence::class.java).invoke(mainBuilder, "PornTrex Settings")

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
                                "1 Page (~85 results - Fastest)",
                                "2 Pages (~170 results - Recommended)",
                                "3 Pages (~255 results - Deep)",
                                "4 Pages (~340 results - Maximum)"
                            )
                            val currentSearch = (Porntrex.searchPages - 1).coerceIn(0, 3)
                            val sBuilder = builderClass.getConstructor(Class.forName("android.content.Context")).newInstance(ctx)
                            builderClass.getMethod("setTitle", CharSequence::class.java).invoke(sBuilder, "PornTrex Search Depth")

                            val sListener = java.lang.reflect.Proxy.newProxyInstance(
                                listenerClass.classLoader,
                                arrayOf(listenerClass)
                            ) { _, sMethod, sArgs ->
                                if (sMethod?.name == "onClick") {
                                    val sWhich = sArgs?.getOrNull(1) as? Int ?: 1
                                    val pages = sWhich + 1
                                    Porntrex.searchPages = pages
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
                                "1 Page (~120 videos - Fastest)",
                                "2 Pages (~240 videos)",
                                "3 Pages (~360 videos - Balanced)",
                                "5 Pages (~600 videos - Recommended)",
                                "10 Pages (~1200 videos - Maximum)"
                            )
                            val modelValues = intArrayOf(1, 2, 3, 5, 10)
                            val currentModel = modelValues.indexOf(Porntrex.modelPages).let { if (it >= 0) it else 2 }

                            val mBuilder = builderClass.getConstructor(Class.forName("android.content.Context")).newInstance(ctx)
                            builderClass.getMethod("setTitle", CharSequence::class.java).invoke(mBuilder, "Model Profile Videos Depth")

                            val mListener = java.lang.reflect.Proxy.newProxyInstance(
                                listenerClass.classLoader,
                                arrayOf(listenerClass)
                            ) { _, mMethod, mArgs ->
                                if (mMethod?.name == "onClick") {
                                    val mWhich = mArgs?.getOrNull(1) as? Int ?: 2
                                    val pages = modelValues.getOrElse(mWhich) { 3 }
                                    Porntrex.modelPages = pages
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
