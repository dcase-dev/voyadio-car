package com.voyadio.automotive.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

private val SHA256: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

data class Station(
    val url: String, val name: String, val country: String = "",
    val countryCode: String = "", val uuid: String = "", val state: String = "",
    val savedAt: Long = 0,
    /** 장르 태그(쉼표로 구분, 서버 풀 그대로) — 내용 항로(공영·클래식…)가 쓴다. 2026-10-15. */
    val tags: String = "",
) {
    // Stable ID is computed once, not on every lookup through thousands of stations.
    // The digest is reused per thread: getInstance on every one of ~5,000 stations froze the main thread (ANR).
    val id: String = "station:" + SHA256.get()!!.digest(url.toByteArray()).let { bytes ->
        val hex = "0123456789abcdef"
        buildString(bytes.size * 2) { bytes.forEach { append(hex[(it.toInt() and 255) ushr 4]); append(hex[it.toInt() and 15]) } }
    }
    fun json() = JSONObject().put("stream_url", url).put("name", name)
        .put("country", country).put("countrycode", countryCode)
        .put("stationuuid", uuid).put("state", state).put("saved_at", savedAt).put("tags", tags)
    companion object {
        fun parse(raw: JSONObject): Station? {
            val url = listOf("stream_url", "url_resolved", "url").firstNotNullOfOrNull {
                raw.optString(it).takeIf { value -> value.isNotBlank() && value != "null" }
            } ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            fun str(key: String) = raw.optString(key).takeUnless { it == "null" }.orEmpty()
            return Station(url, str("name"), str("country"), str("countrycode").uppercase(),
                str("stationuuid"), str("state"), raw.optLong("saved_at"), str("tags"))
        }
    }
}

fun JSONArray.stations(): List<Station> = (0 until length()).mapNotNull { optJSONObject(it)?.let(Station::parse) }
data class Account(val id: Long, val nickname: String, val token: String)
data class LinkCode(val code: String, val secret: String, val expiresAt: Long) {
    // Construct from the public code only. Never put the secret or session in the QR.
    val qr: String get() = "voyadio://link?code=$code"
}

data class FavoriteChange(
    val id: String, val action: String, val station: Station, var base: Long, var follows: String? = null,
) {
    fun request() = JSONObject().put("op_id", id).put("action", action)
        .put("stream_url", station.url).put("base_revision", base).apply {
            if (action != "remove") put("favorite", station.json())
        }
    fun persisted() = request().put("station", station.json()).put("follows", follows)
}

/** Same v2 operation protocol as the mobile app; a refresh never rebases old offline adds. */
class FavoriteState(
    var revision: Long = 0,
    var confirmed: List<Station> = emptyList(),
    val pending: MutableList<FavoriteChange> = mutableListOf(),
) {
    val visible: List<Station> get() {
        val result = confirmed.associateByTo(linkedMapOf()) { it.url }
        pending.forEach { if (it.action == "remove") result.remove(it.station.url) else result[it.station.url] = it.station }
        return result.values.sortedByDescending { it.savedAt }
    }
    fun toggle(station: Station, now: Long) {
        val action = if (visible.any { it.url == station.url }) "remove" else "add"
        pending.add(FavoriteChange(UUID.randomUUID().toString(), action, station.copy(savedAt = now),
            revision, pending.lastOrNull { it.station.url == station.url }?.id))
    }
    fun accept(raw: JSONObject, expected: FavoriteChange? = null): Boolean {
        require(raw.optBoolean("ok") && raw.optInt("protocol") == 2)
        val nextRevision = raw.getLong("revision")
        require(nextRevision >= revision && raw.has("tombstones"))
        val array = raw.getJSONArray("favorites")
        val stations = array.stations()
        require(stations.size == array.length())
        if (expected != null) {
            require(pending.firstOrNull()?.id == expected.id && raw.getString("op_id") == expected.id)
            require(raw.get("applied") is Boolean)
            val opRevision = raw.getLong("operation_revision")
            require(opRevision >= 0 && opRevision <= nextRevision)
            pending.removeAt(0)
            pending.filter { it.follows == expected.id }.forEach {
                if (raw.getBoolean("applied")) it.base = maxOf(it.base, opRevision)
                it.follows = null
            }
        }
        revision = nextRevision
        confirmed = stations
        return expected == null || raw.getBoolean("applied")
    }
    fun json() = JSONObject().put("revision", revision)
        .put("confirmed", JSONArray(confirmed.map { it.json() }))
        .put("pending", JSONArray(pending.map { it.persisted() }))
    companion object {
        fun parse(raw: JSONObject): FavoriteState {
            val state = FavoriteState(raw.getLong("revision"), raw.getJSONArray("confirmed").stations())
            val changes = raw.getJSONArray("pending")
            for (i in 0 until changes.length()) {
                val op = changes.getJSONObject(i)
                val station = requireNotNull(Station.parse(op.getJSONObject("station")))
                val action = op.getString("action")
                require(action in listOf("add", "remove"))
                state.pending.add(FavoriteChange(op.getString("op_id"), action, station,
                    op.getLong("base_revision"), op.optString("follows").takeUnless { it.isEmpty() || it == "null" }))
            }
            return state
        }
    }
}
