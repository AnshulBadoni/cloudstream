package com.custom

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TorrentAndStreamSupportTest {
    @Test
    fun `finds current LimeTorrent search result links`() {
        val document = Jsoup.parse(
            """
            <table class="table2"><tr>
              <td><a href="/the-black-demon-2023-1080p-webrip-x265rbg-432031.html">The Black Demon 2023 1080p WEBRip x265-RBG</a></td>
            </tr></table>
            <a href="/search/?catname=&q=black+demon">The Black Demon Download</a>
            """.trimIndent(),
            "https://limetorrent.net/search/?catname=&q=black+demon"
        )

        val results = TorrentSupport.findLimeSearchEntries(document, document.location())

        assertEquals(1, results.size)
        assertEquals("The Black Demon 2023 1080p WEBRip x265-RBG", results.single().title)
        assertEquals(
            "https://limetorrent.net/the-black-demon-2023-1080p-webrip-x265rbg-432031.html",
            results.single().url
        )
    }

    @Test
    fun `follows a LimeTorrent magnet detail host and extracts its magnet`() {
        val catalogueDetail = Jsoup.parse(
            """
            <h1>The Black Demon</h1>
            <a title="Magnet" href="https://limetorrent.store/the-black-demon-432031.html">Magnet</a>
            """.trimIndent(),
            "https://limetorrent.net/the-black-demon-432031.html"
        )
        val magnetDetail = Jsoup.parse(
            """
            <a href="magnet:?xt=urn:btih:ABE875B2E942762B931094D99698EBB27388557B&amp;dn=The+Black+Demon&amp;tr=udp%3A%2F%2Ftracker.example%2Fannounce">Magnet Download</a>
            """.trimIndent(),
            "https://limetorrent.store/the-black-demon-432031.html"
        )

        assertEquals(
            listOf("https://limetorrent.store/the-black-demon-432031.html"),
            TorrentSupport.alternateTorrentDetailUrls(catalogueDetail, catalogueDetail.location())
        )
        val torrent = TorrentSupport.extractTorrent(magnetDetail)
        assertNotNull(torrent)
        assertTrue(torrent!!.isMagnet)
        assertEquals(
            "magnet:?xt=urn:btih:ABE875B2E942762B931094D99698EBB27388557B&dn=The+Black+Demon&tr=udp%3A%2F%2Ftracker.example%2Fannounce",
            torrent.url
        )
    }

    @Test
    fun `extracts encoded PornoTorrent download magnets from href and script data`() {
        val expected = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=Movie+Title"
        val encoded = "magnet%3A%3Fxt%3Durn%3Abtih%3A0123456789ABCDEF0123456789ABCDEF01234567%26dn%3DMovie%2BTitle"
        val fromHref = Jsoup.parse("<a href=\"/download/?m=$encoded\">Download</a>")
        val fromScript = Jsoup.parse("<script>const torrent = '$encoded';</script>")

        assertEquals(expected, TorrentSupport.extractMagnet(fromHref))
        assertEquals(expected, TorrentSupport.extractMagnet(fromScript))
    }

    @Test
    fun `extracts direct MP4 HLS and escaped Playerjs sources`() {
        val playerHtml = """
            <script>
              const config = { file: "[1080]https:\/\/cdn.example.test\/movie-1080.mp4?token=abc\u0026x=1" };
              const hls = 'https://cdn.example.test/movie/master.m3u8?sig=ok';
            </script>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://cdn.example.test/movie-1080.mp4?token=abc&x=1",
                "https://cdn.example.test/movie/master.m3u8?sig=ok"
            ),
            StreamSupport.extractMediaUrls(playerHtml)
        )
        assertTrue(StreamSupport.isDirectMediaUrl("https://cdn.example.test/movie/master.m3u8?sig=ok"))
        assertTrue(!StreamSupport.isDirectMediaUrl("https://speedporn.net/movie.mp4-review/"))
    }

    @Test
    fun `PLibrary plugin registers the PLibrary API rather than MultiSource`() {
        val plugin: Plugin = PLibraryProvider()
        plugin.load(Context())

        assertEquals(1, plugin.registeredAPIs.size)
        assertTrue(plugin.registeredAPIs.single() is PLibrary)
    }
}
