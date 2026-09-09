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
            val savedPages = getInt.invoke(prefs, "search_pages", 2) as? Int ?: 2
            Porntrex.searchPages = savedPages
        } catch (_: Exception) {}

        this.openSettings = { ctx ->
            try {
                val prefs = ctx.javaClass.getMethod("getSharedPreferences", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(ctx, "Porntrex_Settings", 0)

                val options = arrayOf<CharSequence>(
                    "1 Page (~85 results - Fastest)",
                    "2 Pages (~170 results - Recommended)",
                    "3 Pages (~255 results - Deep)",
                    "4 Pages (~340 results - Maximum)"
                )
                val current = (Porntrex.searchPages - 1).coerceIn(0, 3)

                val builderClass = Class.forName("android.app.AlertDialog\$Builder")
                val builder = builderClass.getConstructor(Class.forName("android.content.Context")).newInstance(ctx)
                
                builderClass.getMethod("setTitle", CharSequence::class.java).invoke(builder, "PornTrex Search Depth")
                
                val listenerClass = Class.forName("android.content.DialogInterface\$OnClickListener")
                val listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass)
                ) { _, method, args ->
                    if (method?.name == "onClick") {
                        val which = args?.getOrNull(1) as? Int ?: 1
                        val pages = which + 1
                        Porntrex.searchPages = pages
                        val editor = prefs.javaClass.getMethod("edit").invoke(prefs)
                        editor.javaClass.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType).invoke(editor, "search_pages", pages)
                        editor.javaClass.getMethod("apply").invoke(editor)
                        val dialog = args?.getOrNull(0)
                        dialog?.javaClass?.getMethod("dismiss")?.invoke(dialog)
                    }
                    null
                }

                builderClass.getMethod("setSingleChoiceItems", Array<CharSequence>::class.java, Int::class.javaPrimitiveType, listenerClass)
                    .invoke(builder, options, current, listener)

                val cancelListener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass)
                ) { _, method, args ->
                    if (method?.name == "onClick") {
                        val dialog = args?.getOrNull(0)
                        dialog?.javaClass?.getMethod("dismiss")?.invoke(dialog)
                    }
                    null
                }

                builderClass.getMethod("setNegativeButton", CharSequence::class.java, listenerClass)
                    .invoke(builder, "Cancel", cancelListener)

                builderClass.getMethod("show").invoke(builder)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
