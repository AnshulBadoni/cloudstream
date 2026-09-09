package com.lagradost.nicehttp

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

open class NiceResponse(
    open val text: String = "",
    open val document: Document = Document(""),
    open val url: String = "",
    open val code: Int = 200,
    open val headers: okhttp3.Headers = okhttp3.Headers.Builder().build(),
    open val okhttpResponse: Response? = null
)

open class Requests {
    open suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser = ResponseParser()
    ): NiceResponse {
        val client = okhttp3.OkHttpClient.Builder().build()
        val reqBuilder = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        val res = client.newCall(reqBuilder.build()).execute()
        val body = res.body?.string() ?: ""
        return NiceResponse(body, Jsoup.parse(body, url), url, res.code, res.headers, res)
    }
}

open class ResponseParser

