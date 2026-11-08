package com.voyadio.automotive.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoyadioApiTest {
    @Test fun `public requests have no token and account requests use existing Bearer contract`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"stations\":[]}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            val api = VoyadioApi(server.url("/").toString())
            api.call("/api/pool")
            assertNull(server.takeRequest().getHeader("Authorization"))
            api.call("/api/favorites/sync", "test-only-token", JSONObject().put("action", "remove"))
            val request = server.takeRequest()
            assertEquals("/api/favorites/sync", request.path)
            assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
            assertEquals("remove", JSONObject(request.body.readUtf8()).getString("action"))
        }
    }
    @Test fun `API redirect cannot carry account token to a new endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://elsewhere.example.test"))
            val failure = runCatching { VoyadioApi(server.url("/").toString()).call("/api/me", "test-only-token") }.exceptionOrNull()
            assertTrue(failure is ApiFailure && failure.status == 302)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `plain HTTP is reserved for local contract tests`() {
        assertThrows(IllegalArgumentException::class.java) { VoyadioApi("http://voyadio.com") }
    }
}
