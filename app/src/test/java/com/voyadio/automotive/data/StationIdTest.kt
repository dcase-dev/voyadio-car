package com.voyadio.automotive.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class StationIdTest {
    private fun expectedId(url: String) = "station:" +
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun `id is the sha-256 hex of the url with the station prefix`() {
        val url = "https://example.com/stream"
        assertEquals(expectedId(url), Station(url, "x").id)
        assertEquals("station:244daf4618184b03a4ff4d0813228b91910122b0b2f04429d57515326b18e541", Station(url, "x").id)
    }

    @Test fun `ids computed concurrently match the sequential ids`() {
        val urls = (0 until 500).map { "https://radio$it.example.test/live?x=$it" }
        val sequential = urls.map { Station(it, "n").id }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 8).map { pool.submit(Callable { urls.map { url -> Station(url, "n").id } }) }.map { it.get() }
            results.forEach { assertEquals(sequential, it) }
            assertEquals(urls.map(::expectedId), sequential)
        } finally { pool.shutdownNow() }
    }
}
