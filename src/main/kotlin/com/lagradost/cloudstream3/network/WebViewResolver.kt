package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

open class WebViewResolver(
    val matchUrl: Regex,
    val interceptUrl: Regex? = null,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = null
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}
