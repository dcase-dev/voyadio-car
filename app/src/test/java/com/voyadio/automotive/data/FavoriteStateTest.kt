package com.voyadio.automotive.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FavoriteStateTest {
    private val station = Station("https://radio.example.test/live", "Test radio", "Korea", "KR")
    private fun snapshot(revision: Long, stations: List<Station> = emptyList()) = JSONObject()
        .put("ok", true).put("protocol", 2).put("revision", revision)
        .put("favorites", JSONArray(stations.map { it.json() })).put("tombstones", JSONArray())
    @Test fun `refresh never gives a stale offline add permission to undo a mobile deletion`() {
        val state = FavoriteState(revision = 2)
        state.toggle(station, 1)
        state.accept(snapshot(8))
        assertEquals(2L, state.pending.single().base)
        val op = state.pending.single()
        assertFalse(state.accept(snapshot(8).put("op_id", op.id).put("applied", false).put("operation_revision", 8), op))
        assertTrue(state.visible.isEmpty())
    }
    @Test fun `remove then add follows only the acknowledged local operation`() {
        val state = FavoriteState(2, listOf(station))
        state.toggle(station, 10); state.toggle(station, 20)
        val remove = state.pending.first()
        assertEquals(remove.id, state.pending.last().follows)
        state.accept(snapshot(3).put("op_id", remove.id).put("applied", true).put("operation_revision", 3), remove)
        assertEquals(3L, state.pending.single().base)
        assertNull(state.pending.single().follows)
        assertEquals(20L, state.visible.single().savedAt)
    }
    @Test fun `restart preserves operation identity and deletion intention`() {
        val state = FavoriteState(7, listOf(station))
        state.toggle(station, 20)
        val restored = FavoriteState.parse(JSONObject(state.json().toString()))
        assertEquals(state.pending.single().request().toString(), restored.pending.single().request().toString())
        assertTrue(restored.visible.isEmpty())
    }
    @Test fun `wrong acknowledgement does not discard queued changes`() {
        val state = FavoriteState(); state.toggle(station, 1)
        val op = state.pending.single()
        assertThrows(IllegalArgumentException::class.java) {
            state.accept(snapshot(1).put("op_id", "another").put("applied", true).put("operation_revision", 1), op)
        }
        assertEquals(op.id, state.pending.single().id)
    }
    @Test fun `malformed snapshot does not erase saved stations`() {
        val state = FavoriteState(2, listOf(station))
        val bad = snapshot(3).put("favorites", JSONArray().put(JSONObject().put("name", "missing URL")))
        assertThrows(IllegalArgumentException::class.java) { state.accept(bad) }
        assertEquals(listOf(station), state.visible)
        assertEquals(2L, state.revision)
    }
    @Test fun `station IDs match between mobile-shaped favorites and pool records`() {
        val pool = Station.parse(JSONObject().put("url_resolved", station.url).put("name", "new name"))!!
        val favorite = Station.parse(station.json())!!
        assertEquals(pool.id, favorite.id)
    }
    @Test fun `untrusted URLs and embedded passwords are not accepted`() {
        for (url in listOf("file:///sdcard/a", "content://private/a", "javascript:test", "https://user:pass@example.test/a")) {
            assertNull(Station.parse(JSONObject().put("stream_url", url)))
        }
    }
    @Test fun `QR only carries public pairing code`() {
        val link = LinkCode("ABCDEFGH", "device-private-value", 1)
        assertEquals("voyadio://link?code=ABCDEFGH", link.qr)
        assertFalse(link.qr.contains(link.secret))
    }
}
