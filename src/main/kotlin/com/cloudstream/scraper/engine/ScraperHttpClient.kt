package com.cloudstream.scraper.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val url: String = "",
    val isSuccessful: Boolean = code in 200..299
)

class HttpException(
    val statusCode: Int,
    val url: String,
    override val message: String
) : IOException("HTTP $statusCode for $url: $message")

interface ScraperHttpClient {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cacheTtlSeconds: Long = 0
    ): HttpResponse

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse
}

/**
 * Standard OkHttp implementation with configurable timeouts, retries, headers, and in-memory/disk caching.
 */
class OkHttpScraperClient(
    private val defaultHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    ),
    private val maxRetries: Int = 2,
    cacheDir: File? = null,
    cacheSizeMb: Long = 10
) : ScraperHttpClient {

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, String>>()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectionSpecs(listOf(
            okhttp3.ConnectionSpec.MODERN_TLS,
            okhttp3.ConnectionSpec.COMPATIBLE_TLS,
            okhttp3.ConnectionSpec.CLEARTEXT
        ))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .cookieJar(object : okhttp3.CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                val host = url.host
                val map = cookieStore.getOrPut(host) { ConcurrentHashMap() }
                cookies.forEach { map[it.name] = it.value }
            }

            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                val host = url.host
                val list = mutableListOf<okhttp3.Cookie>()
                cookieStore[host]?.forEach { (name, value) ->
                    okhttp3.Cookie.Builder()
                        .domain(host)
                        .name(name)
                        .value(value)
                        .build()
                        .let { list.add(it) }
                }
                return list
            }
        })
        .apply {
            if (cacheDir != null) {
                cache(Cache(cacheDir, cacheSizeMb * 1024 * 1024))
            }
        }
        .build()

    // In-memory cache for fast repeated queries during a scraping session
    private data class CacheEntry(val response: HttpResponse, val timestamp: Long, val ttlMs: Long)
    private val inMemoryCache = ConcurrentHashMap<String, CacheEntry>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        cacheTtlSeconds: Long
    ): HttpResponse = withContext(Dispatchers.IO) {
        val cacheKey = "GET:$url"
        if (cacheTtlSeconds > 0) {
            val cached = inMemoryCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < cached.ttlMs) {
                return@withContext cached.response
            }
        }

        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val requestBuilder = Request.Builder().url(url)
                val combinedHeaders = defaultHeaders + headers
                combinedHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                val bodyString = response.body?.string() ?: ""
                val responseHeaders = response.headers.toMap()

                val httpResponse = HttpResponse(
                    code = response.code,
                    body = bodyString,
                    headers = responseHeaders,
                    url = response.request.url.toString()
                )

                if (response.isSuccessful) {
                    if (cacheTtlSeconds > 0) {
                        inMemoryCache[cacheKey] = CacheEntry(
                            httpResponse,
                            System.currentTimeMillis(),
                            cacheTtlSeconds * 1000
                        )
                    }
                    return@withContext httpResponse
                } else if (response.code in 400..499 && response.code != 429) {
                    // Do not retry 4xx errors except rate limits
                    throw HttpException(response.code, url, "Client error: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt == maxRetries || e is HttpException) {
                    throw e
                }
                kotlinx.coroutines.delay((attempt + 1) * 500L)
            }
        }
        throw lastException ?: IOException("Failed to execute GET request for $url")
    }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>
    ): HttpResponse = withContext(Dispatchers.IO) {
        val mediaType = (headers["Content-Type"] ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
        val requestBody = body.toRequestBody(mediaType)

        val requestBuilder = Request.Builder().url(url).post(requestBody)
        val combinedHeaders = defaultHeaders + headers
        combinedHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        HttpResponse(
            code = response.code,
            body = response.body?.string() ?: "",
            headers = response.headers.toMap(),
            url = response.request.url.toString()
        )
    }
}

/**
 * Mock HTTP client for unit tests to provide static fixture responses without network calls.
 */
class MockScraperHttpClient(
    private val fixtureMap: Map<String, String> = emptyMap(),
    private val defaultResponse: String = "<html></html>"
) : ScraperHttpClient {

    private val recordedRequests = mutableListOf<String>()

    fun getRecordedRequests(): List<String> = recordedRequests

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        cacheTtlSeconds: Long
    ): HttpResponse {
        recordedRequests.add("GET $url")
        val body = fixtureMap[url] ?: fixtureMap.entries.firstOrNull { url.contains(it.key) }?.value ?: defaultResponse
        return HttpResponse(
            code = 200,
            body = body,
            url = url
        )
    }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>
    ): HttpResponse {
        recordedRequests.add("POST $url: $body")
        val responseBody = fixtureMap[url] ?: defaultResponse
        return HttpResponse(
            code = 200,
            body = responseBody,
            url = url
        )
    }
}
