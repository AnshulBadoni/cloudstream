package com.lagradost.nicehttp

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

open class NiceResponse(
    open val text: String = "",
    open val document: Document = Document(""),
    open val url: String = "",
    open val code: Int = 200,
    open val headers: okhttp3.Headers = okhttp3.Headers.Builder().build(),
    open val okhttpResponse: Response? = null
)

open class Requests(
    open var defaultHeaders: Map<String, String> = emptyMap()
) {
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslSocketFactory = runCatching {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        sslContext.socketFactory
    }.getOrNull()

    private fun createClient(allowRedirects: Boolean, timeout: Long): okhttp3.OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
            .followRedirects(allowRedirects)
            .followSslRedirects(allowRedirects)
        if (timeout > 0) {
            builder.connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
        }
        if (sslSocketFactory != null) {
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

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
        val client = createClient(allowRedirects, timeout)
        val reqBuilder = okhttp3.Request.Builder().url(url)
        val mergedHeaders = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to "https://www.porntrex.com/"
        )
        mergedHeaders.putAll(defaultHeaders)
        mergedHeaders.putAll(headers)
        if (referer != null) mergedHeaders["Referer"] = referer
        mergedHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        val res = client.newCall(reqBuilder.build()).execute()
        val body = res.body?.string() ?: ""
        return NiceResponse(body, Jsoup.parse(body, url), url, res.code, res.headers, res)
    }

    open suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        timeout: Long = 0L,
        verify: Boolean = true,
        responseParser: ResponseParser = ResponseParser()
    ): NiceResponse {
        val client = createClient(allowRedirects, timeout)
        val formBuilder = okhttp3.FormBody.Builder()
        data.forEach { (k, v) -> formBuilder.add(k, v) }
        val reqBuilder = okhttp3.Request.Builder().url(url).post(formBuilder.build())
        val mergedHeaders = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        )
        mergedHeaders.putAll(defaultHeaders)
        mergedHeaders.putAll(headers)
        if (referer != null) mergedHeaders["Referer"] = referer
        mergedHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        val res = client.newCall(reqBuilder.build()).execute()
        val body = res.body?.string() ?: ""
        return NiceResponse(body, Jsoup.parse(body, url), url, res.code, res.headers, res)
    }
}

open class ResponseParser
