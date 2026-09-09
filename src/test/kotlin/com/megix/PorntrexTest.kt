package com.megix

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PorntrexTest {

    @Test
    fun testPorntrexMetadata() {
        val provider = Porntrex()
        assertEquals("PornTrex", provider.name)
        assertEquals("https://www.porntrex.com", provider.mainUrl)
        assertTrue(provider.hasMainPage)
        assertTrue(provider.mainPage.any { it.data == "models" && it.name == "Models & Stars" })
    }
}
