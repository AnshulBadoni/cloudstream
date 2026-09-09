@file:JvmName("MainActivityKt")
package com.lagradost.cloudstream3

import com.lagradost.nicehttp.Requests

val app: Requests = Requests().apply {
    defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://www.porntrex.com/"
    )
}
