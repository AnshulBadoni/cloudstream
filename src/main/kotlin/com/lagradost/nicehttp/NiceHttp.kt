package com.lagradost.nicehttp

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

open class NiceResponse(
    open val text: String = "",
    open val document: Document = Document("")
)

open class Requests {
    open suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        cacheTime: Int = 0
    ): NiceResponse {
        val client = okhttp3.OkHttpClient.Builder().build()
        val reqBuilder = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        val res = client.newCall(reqBuilder.build()).execute()
        val body = res.body?.string() ?: ""
        return NiceResponse(body, Jsoup.parse(body, url))
    }
}
