package com.custom

import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TpdbDiscoveryProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: TpdbDiscoveryProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()

        provider = TpdbDiscoveryProvider().apply {
            backendBaseUrl = server.url("/").toString().removeSuffix("/")
        }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `loadLinks successfully maps backend streams into ExtractorLinks`() = runBlocking {
        val mockBackendJson = """
            {
              "status": "SUCCESS",
              "content": { "id": "99214", "type": "MOVIE" },
              "results": [
                {
                  "source": "lulustream",
                  "quality": "1080p",
                  "url": "https://cdn.example.org/master.m3u8",
                  "isM3u8": true,
                  "size": 1845493820,
                  "headers": {
                    "Referer": "https://luluvido.com/"
                  }
                },
                {
                  "source": "mixdrop",
                  "quality": "720p",
                  "url": "https://delivery.example.net/v2/stream.mp4",
                  "isM3u8": false,
                  "size": 954832100,
                  "headers": {
                    "Referer": "https://mixdrop.ag/"
                  }
                }
              ],
              "cached": true
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockBackendJson))

        val emittedLinks = mutableListOf<ExtractorLink>()

        val success = provider.loadLinks(
            data = "tpdb://movie/99214",
            isCasting = false,
            subtitleCallback = {},
            callback = { emittedLinks.add(it) }
        )

        assertTrue(success, "loadLinks should return true when links are emitted")
        assertEquals(2, emittedLinks.size, "Should emit exactly 2 stream links")

        val luluLink = emittedLinks.first { it.source == "lulustream" }
        assertEquals("[LULUSTREAM] 1080p", luluLink.name)
        assertEquals("https://cdn.example.org/master.m3u8", luluLink.url)
        assertEquals(Qualities.P1080.value, luluLink.quality)
        assertTrue(luluLink.isM3u8)
        assertEquals("https://luluvido.com/", luluLink.referer)

        val mixdropLink = emittedLinks.first { it.source == "mixdrop" }
        assertEquals("[MIXDROP] 720p", mixdropLink.name)
        assertEquals("https://delivery.example.net/v2/stream.mp4", mixdropLink.url)
        assertEquals(Qualities.P720.value, mixdropLink.quality)
        assertFalse(mixdropLink.isM3u8)
        assertEquals("https://mixdrop.ag/", mixdropLink.referer)

        // Verify request to backend
        val recordedReq = server.takeRequest()
        assertEquals("/api/v1/streams/movie/99214", recordedReq.path)
        assertEquals("CloudStream-TpdbDiscovery/1.0", recordedReq.getHeader("User-Agent"))
    }

    @Test
    fun `loadLinks gracefully handles empty or indexing backend responses`() = runBlocking {
        val mockIndexingJson = """
            {
              "status": "INDEXING",
              "content": { "id": "11223", "type": "MOVIE" },
              "results": [],
              "cached": false,
              "retryAfter": 3
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockIndexingJson))

        val emittedLinks = mutableListOf<ExtractorLink>()

        val success = provider.loadLinks(
            data = "tpdb://movie/11223",
            isCasting = false,
            subtitleCallback = {},
            callback = { emittedLinks.add(it) }
        )

        assertFalse(success, "loadLinks should return false when no links are available")
        assertTrue(emittedLinks.isEmpty(), "No links should be emitted on INDEXING status")
    }

    @Test
    fun `loadLinks gracefully handles backend network or HTTP 500 error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val emittedLinks = mutableListOf<ExtractorLink>()

        val success = provider.loadLinks(
            data = "tpdb://movie/55555",
            isCasting = false,
            subtitleCallback = {},
            callback = { emittedLinks.add(it) }
        )

        assertFalse(success, "loadLinks should safely return false on HTTP 500")
        assertTrue(emittedLinks.isEmpty())
    }
}
