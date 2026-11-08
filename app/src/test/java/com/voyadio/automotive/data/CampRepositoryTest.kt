package com.voyadio.automotive.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private class MemoryVault(var data: JSONObject = JSONObject()) : Vault {
    override fun read() = JSONObject(data.toString())
    override fun write(value: JSONObject) { data = JSONObject(value.toString()) }
}

class CampRepositoryTest {
    private val station = Station("https://radio.example.test/live", "Mobile favorite", savedAt = 10)
    private fun initial() = MemoryVault(JSONObject().put("account", JSONObject().put("id", 42).put("nickname", "Camper").put("token", "test-token-42"))
        .put("favorites", FavoriteState(0, listOf(station)).json()).put("last", station.json()))
    private fun snapshot(stations: List<Station> = listOf(station), revision: Int = 1) = JSONObject()
        .put("ok", true).put("protocol", 2).put("revision", revision).put("favorites", JSONArray(stations.map { it.json() }))
        .put("tombstones", JSONArray())
    private fun MockWebServer.json(value: JSONObject) { enqueue(MockResponse().setBody(value.toString())) }
    private fun MockWebServer.me(id: Int = 42) { json(JSONObject().put("ok", true).put("id", id).put("nickname", "Camper")) }
    @Test fun `other device listened later so play-start takes that channel once`() = runBlocking {
        val car = Station("https://radio.example.test/car", "Car channel", savedAt = 20)
        MockWebServer().use { server ->
            server.me(); server.json(snapshot())
            server.json(JSONObject().put("ok", true).put("last_played", car.json().put("updated_at", 200)))
            server.json(JSONObject().put("ok", true).put("balance", 0))
            val vault = initial().apply { data.put("last_seen_at", 100) }
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), vault)
            repo.initialize(); repo.sync()
            assertEquals(car, repo.takeHandoff())
            assertNull(repo.takeHandoff())
        }
    }
    @Test fun `records seen while the car is playing never become a handoff`() = runBlocking {
        val car = Station("https://radio.example.test/car", "Car channel", savedAt = 20)
        MockWebServer().use { server ->
            repeat(2) {
                server.me(); server.json(snapshot())
                server.json(JSONObject().put("ok", true).put("last_played", car.json().put("updated_at", 200)))
                server.json(JSONObject().put("ok", true).put("balance", 0))
            }
            val vault = initial().apply { data.put("last_seen_at", 100) }
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), vault)
            repo.initialize()
            repo.markPlaying(true); repo.sync()
            assertNull(repo.takeHandoff())
            repo.markPlaying(false); repo.sync()
            assertNull(repo.takeHandoff())
        }
    }
    @Test fun `own upload is not treated as another device`() = runBlocking {
        MockWebServer().use { server ->
            server.me(); server.json(snapshot())
            server.json(JSONObject().put("ok", true).put("updated_at", 300))
            server.json(JSONObject().put("ok", true).put("last_played", station.json().put("updated_at", 300)))
            server.json(JSONObject().put("ok", true).put("balance", 0))
            val vault = initial().apply { data.put("pending_last", station.json()).put("pending_last_at", 5).put("last_seen_at", 100) }
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), vault)
            repo.initialize(); repo.sync()
            assertNull(repo.takeHandoff())
            assertEquals(300L, vault.data.optLong("last_seen_at"))
        }
    }
    @Test fun `car reads mobile favorites last channel and mileage from the same user`() = runBlocking {
        MockWebServer().use { server ->
            server.me(); server.json(snapshot())
            server.json(JSONObject().put("ok", true).put("last_played", station.json()))
            server.json(JSONObject().put("ok", true).put("balance", 120))
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), initial())
            repo.initialize(); repo.sync()
            assertEquals(42L, repo.state.value.accountId)
            assertEquals(station, repo.state.value.favorites.single())
            assertEquals(station, repo.state.value.lastPlayed)
            assertEquals(120, repo.state.value.mileage)
            repeat(4) { assertEquals("Bearer test-token-42", server.takeRequest().getHeader("Authorization")) }
        }
    }
    @Test fun `unauthorized account clears private data instead of treating it as guest data`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val vault = initial()
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), vault)
            repo.initialize(); repo.sync()
            assertNull(repo.state.value.accountId)
            assertNull(repo.state.value.lastPlayed)
            assertTrue(repo.state.value.favorites.isEmpty())
            assertFalse(vault.data.has("account"))
            assertEquals("session_expired", repo.state.value.status)
        }
    }
    @Test fun `offline favorite removal survives restart and retries with original operation ID`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val vault = initial()
            val api = VoyadioApi(server.url("/").toString())
            val repo = CampRepository(api, vault)
            repo.initialize(); repo.toggleFavorite(station)
            val op = vault.data.getJSONObject("favorites").getJSONArray("pending").getJSONObject(0)
            assertTrue(repo.state.value.favorites.isEmpty())
            assertEquals(1, repo.state.value.pending)
            server.me(); server.json(snapshot())
            server.json(snapshot(emptyList(), 2).put("op_id", op.getString("op_id")).put("operation_revision", 2).put("applied", true))
            server.json(JSONObject().put("ok", true)); server.json(JSONObject().put("ok", true).put("balance", 4))
            val restored = CampRepository(api, vault)
            restored.initialize(); restored.sync()
            assertTrue(restored.state.value.favorites.isEmpty())
            assertEquals(0, restored.state.value.pending)
            server.takeRequest(); server.takeRequest(); server.takeRequest()
            val retry = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(op.getString("op_id"), retry.getString("op_id"))
            assertEquals(0, retry.getInt("base_revision"))
        }
    }
    @Test fun `QR login shares server identity without importing guest favorites`() = runBlocking {
        MockWebServer().use { server ->
            val vault = MemoryVault()
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), vault)
            repo.initialize(); repo.toggleFavorite(station)
            server.json(JSONObject().put("ok", true).put("token", "new-test-token")); server.me(55)
            server.me(55); server.json(snapshot(emptyList()))
            server.json(JSONObject().put("ok", true)); server.json(JSONObject().put("ok", true).put("balance", 0))
            assertTrue(repo.claimLink(LinkCode("ABCDEFGH", "test-device-secret", System.currentTimeMillis() + 10000)))
            assertEquals(55L, repo.state.value.accountId)
            assertTrue(repo.state.value.favorites.isEmpty())
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals("test-device-secret", JSONObject(request.body.readUtf8()).getString("device_secret"))
        }
    }
    @Test fun `disconnect clears the car only and sends no delete account request`() = runBlocking {
        MockWebServer().use { server ->
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), initial())
            repo.initialize(); repo.disconnect()
            assertTrue(repo.state.value.favorites.isEmpty())
            assertNull(repo.state.value.accountId)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun `late response after disconnect cannot restore the previous users favorites`() = runBlocking {
        MockWebServer().use { server ->
            server.me()
            server.enqueue(MockResponse().setBody(snapshot().toString()).setBodyDelay(250, TimeUnit.MILLISECONDS))
            val vault = initial()
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), vault)
            repo.initialize()
            val sync = launch { repo.sync() }
            withContext(Dispatchers.IO) {
                requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            }
            val disconnect = launch(start = CoroutineStart.UNDISPATCHED) { repo.disconnect() }
            assertNull(repo.state.value.accountId)
            assertTrue(repo.state.value.favorites.isEmpty())
            sync.join(); disconnect.join()
            assertTrue(repo.state.value.favorites.isEmpty())
            assertFalse(vault.data.has("account"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `different server identity never receives cached users changes`() = runBlocking {
        MockWebServer().use { server ->
            server.me(99)
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), initial())
            repo.initialize(); repo.sync()
            assertNull(repo.state.value.accountId)
            assertTrue(repo.state.value.favorites.isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    private fun moodsBody(vararg ids: String) = JSONObject().put("slot", "night").put("moods", JSONArray(ids.map { id ->
        JSONObject().put("id", id).put("title", JSONObject().put("ko", id).put("en", id))
            .put("stations", JSONArray(listOf(JSONObject().put("name", "$id radio").put("url_resolved", "https://radio.example.test/$id"))))
    }))

    @Test fun `moods are fetched for the hour and their stations can be found`() = runBlocking {
        MockWebServer().use { server ->
            server.json(moodsBody("campfire_jazz", "night_jazz"))
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), MemoryVault())
            val moods = repo.moods(21, nowMs = 1_000)
            assertEquals(listOf("campfire_jazz", "night_jazz"), moods.map { it.id })
            assertEquals("/api/moods?hour=21", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            // 캠핑 화면이 튼 분위기 방송도 미디어 서비스가 ID로 찾아야 재생된다.
            val station = moods[0].stations[0]
            assertEquals(station, repo.find(station.id))
        }
    }

    @Test fun `same time slot within an hour uses the remembered list`() = runBlocking {
        MockWebServer().use { server ->
            server.json(moodsBody("campfire_jazz"))
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), MemoryVault())
            repo.moods(19, nowMs = 0)
            assertTrue(repo.moodsFresh(21, nowMs = 59 * 60_000L))
            assertEquals(listOf("campfire_jazz"), repo.moods(21, nowMs = 59 * 60_000L).map { it.id })
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `a new time slot or an old list fetches again`() = runBlocking {
        MockWebServer().use { server ->
            server.json(moodsBody("campfire_jazz")); server.json(moodsBody("night_jazz")); server.json(moodsBody("ambient"))
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), MemoryVault())
            repo.moods(21, nowMs = 0)
            assertEquals("night_jazz", repo.moods(22, nowMs = 1_000)[0].id)          // 저녁 → 밤
            assertEquals("ambient", repo.moods(23, nowMs = 61 * 60_000L)[0].id)      // 같은 밤이어도 1시간 지남
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `a failed fetch keeps the last list`() = runBlocking {
        MockWebServer().use { server ->
            server.json(moodsBody("campfire_jazz"))
            server.enqueue(MockResponse().setResponseCode(500))
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), MemoryVault())
            repo.moods(21, nowMs = 0)
            assertEquals(listOf("campfire_jazz"), repo.moods(22, nowMs = 1_000).map { it.id })
            assertEquals(listOf("campfire_jazz"), repo.lastMoods.map { it.id })
        }
    }

    @Test fun `no list and a failed fetch is an error the screen can show`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val repo = CampRepository(VoyadioApi(server.url("/").toString()), MemoryVault())
            val failure = runCatching { repo.moods(21, nowMs = 0) }.exceptionOrNull()
            assertTrue(failure is ApiFailure)
        }
    }
}
