package com.voyadio.automotive.data

import com.voyadio.automotive.camping.Mood
import com.voyadio.automotive.camping.TimeSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CampState(
    val stations: List<Station> = emptyList(), val favorites: List<Station> = emptyList(),
    val lastPlayed: Station? = null, val accountName: String? = null, val mileage: Int? = null,
    val pending: Int = 0, val status: String = "", val loading: Boolean = false, val accountId: Long? = null,
)

/** All account calls use the same mobile endpoints and server user ID. Call on the main dispatcher. */
class CampRepository(private val api: VoyadioApi, private val vault: Vault) {
    private val mutable = MutableStateFlow(CampState())
    val state = mutable.asStateFlow()
    private var account: Account? = null
    private var favorites = FavoriteState()
    private var generation = 0L
    private val syncLock = Mutex()
    private var lastPlayed: Station? = null
    private var pendingLast: Station? = null
    private var pendingLastAt = 0L
    // Part 3: newest server time this car has seen; a larger server value means another device listened later.
    private var seenAt = 0L
    private var handoff: Station? = null
    private var handoffAt = 0L
    // Set by RadioService; while audio plays, newer server records are only marked seen.
    @Volatile private var playing = false
    private var initialized = false
    private val initLock = Mutex()
    private val refreshLock = Mutex()
    private var storeHealthy = true
    // 캠핑 분위기(2026-10-13): 캠핑 화면과 미디어 서비스가 같은 목록을 쓴다. 기기에 저장하지 않는다(매번 서버가 고른다).
    private val moodLock = Mutex()
    @Volatile private var moodList: List<Mood> = emptyList()
    private var moodSlot: TimeSlot? = null
    private var moodAt = 0L

    suspend fun initialize() = initLock.withLock {
        if (initialized) return@withLock
        val ticket = generation
        try {
            // Parsing the stored pool (thousands of stations) must not run on the main thread.
            val (data, pool) = withContext(Dispatchers.IO) {
                val stored = vault.read()
                stored to stored.optJSONArray("pool")?.stations().orEmpty()
            }
            if (ticket != generation) return@withLock
            val user = data.optJSONObject("account")
            account = user?.let { Account(it.getLong("id"), it.getString("nickname"), it.getString("token")) }
            favorites = data.optJSONObject("favorites")?.let(FavoriteState::parse) ?: FavoriteState()
            lastPlayed = data.optJSONObject("last")?.let(Station::parse)
            pendingLast = data.optJSONObject("pending_last")?.let(Station::parse)
            pendingLastAt = data.optLong("pending_last_at")
            seenAt = data.optLong("last_seen_at")
            mutable.value = mutable.value.copy(stations = pool)
            publish()
            initialized = true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Do not replace an unreadable outbox with an empty one silently.
            storeHealthy = false
            mutable.value = mutable.value.copy(status = "storage_error")
        }
    }

    suspend fun refresh() = refreshLock.withLock {
        initialize()
        mutable.value = mutable.value.copy(loading = true)
        try {
            val raw = api.call("/api/pool")
            val pool = withContext(Dispatchers.Default) {
                raw.getJSONArray("stations").stations().distinctBy { it.url }
            }
            require(pool.isNotEmpty())
            mutable.value = mutable.value.copy(stations = pool)
            syncLock.withLock { persist() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value = mutable.value.copy(status = "network_error")
        }
        finally { mutable.value = mutable.value.copy(loading = false) }
        sync()
    }

    suspend fun startLink(): LinkCode {
        val raw = api.call("/api/link/start", body = JSONObject())
        val code = raw.getString("code")
        require(Regex("[A-Z0-9]{8}").matches(code))
        return LinkCode(code, raw.getString("device_secret"), raw.getLong("expires_at"))
    }

    /** null means pending; UI owns expiry and polling cancellation. */
    suspend fun claimLink(link: LinkCode): Boolean {
        val ticket = generation
        require(System.currentTimeMillis() < link.expiresAt)
        val raw = api.call("/api/link/claim", body = JSONObject().put("code", link.code).put("device_secret", link.secret))
        if (raw.optBoolean("pending")) return false
        val token = raw.getString("token")
        val me = api.call("/api/me", token)
        if (ticket != generation) return false
        syncLock.withLock {
            if (ticket != generation) return false
            val next = Account(me.getLong("id"), me.getString("nickname"), token)
            // Never import a different account's or guest's car data into a mobile account.
            if (account?.id != next.id) {
                favorites = FavoriteState(); lastPlayed = null; pendingLast = null
                seenAt = 0; handoff = null
            }
            account = next
            generation++
            persist()
            publish()
        }
        sync()
        return true
    }

    suspend fun disconnect() {
        generation++ // invalidate responses before waiting for the network lock
        account = null; favorites = FavoriteState(); lastPlayed = null; pendingLast = null
        seenAt = 0; handoff = null
        mutable.value = mutable.value.copy(favorites = emptyList(), lastPlayed = null,
            accountName = null, accountId = null, mileage = null, pending = 0, status = "")
        syncLock.withLock { persist() }
    }

    suspend fun toggleFavorite(station: Station) {
        val ticket = generation
        syncLock.withLock {
            if (ticket != generation) return
            check(storeHealthy)
            val prior = FavoriteState.parse(favorites.json())
            favorites.toggle(station, System.currentTimeMillis())
            if (account == null) {
                favorites.confirmed = favorites.visible
                favorites.pending.clear()
            }
            try { persist() } catch (error: Exception) { favorites = prior; throw error }
            publish(if (account == null) "" else "sync_pending")
        }
        sync()
    }

    /** Only called after two uninterrupted seconds of actual audio playback. */
    suspend fun recordPlayed(station: Station) {
        val ticket = generation
        syncLock.withLock {
            if (ticket != generation) return
            lastPlayed = station; handoff = null
            if (account != null) { pendingLast = station; pendingLastAt = System.currentTimeMillis() }
            persist(); publish()
        }
        sync()
    }

    suspend fun sync() = syncLock.withLock {
        val user = account ?: return@withLock
        val ticket = generation
        fun current() = generation == ticket && account?.id == user.id
        try {
            // Validate account before displaying cached identity; a deleted account cannot transfer data.
            val identity = api.call("/api/me", user.token)
            if (!current()) return@withLock
            if (identity.getLong("id") != user.id) throw ApiFailure(401)
            val snapshot = api.call("/api/favorites/sync", user.token)
            if (!current()) return@withLock
            favorites.accept(snapshot); persist(); publish()
            var conflicted = false
            while (favorites.pending.isNotEmpty()) {
                val op = favorites.pending.first()
                val response = api.call("/api/favorites/sync", user.token, op.request())
                if (!current()) return@withLock
                if (!favorites.accept(response, op)) conflicted = true
                persist(); publish()
            }
            val last = pendingLast
            if (last != null) {
                val saved = api.call("/api/last-played", user.token, last.json().put("last_played_at", pendingLastAt))
                if (!current()) return@withLock
                seenAt = maxOf(seenAt, saved.optLong("updated_at"))
                pendingLast = null; persist()
            }
            val serverLast = api.call("/api/last-played", user.token)
            if (!current()) return@withLock
            val row = serverLast.optJSONObject("last_played")
            lastPlayed = row?.let(Station::parse)
            val at = row?.optLong("updated_at") ?: 0L
            if (lastPlayed != null && at > seenAt) {
                if (playing) seenAt = at else { handoff = lastPlayed; handoffAt = at }
            }
            val balance = api.call("/api/mileage", user.token)
            if (!current()) return@withLock
            mutable.value = mutable.value.copy(mileage = balance.getInt("balance"))
            persist(); publish(if (conflicted) "sync_conflict" else "sync_done")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!current()) return@withLock
            if (error is ApiFailure && error.status == 401) {
                generation++; account = null; favorites = FavoriteState(); lastPlayed = null; pendingLast = null
                seenAt = 0; handoff = null
                mutable.value = mutable.value.copy(mileage = null)
                persist(); publish("session_expired")
            } else publish(if (favorites.pending.isNotEmpty() || pendingLast != null) "sync_pending" else "network_error")
        }
    }

    fun markPlaying(value: Boolean) {
        playing = value
        if (value) handoff = null
    }

    /** Play-start only: a channel another device played after this car last looked. Main thread; never waits on network. */
    fun takeHandoff(): Station? {
        val next = handoff ?: return null
        handoff = null
        seenAt = maxOf(seenAt, handoffAt)
        return next
    }

    /** 계정 항로(폰과 공유, `/api/preferences/explore-direction`). 게스트면 null. 2026-10-15. */
    suspend fun loadCourse(): String? {
        val user = account ?: return null
        return api.call("/api/preferences/explore-direction", user.token).optString("explore_direction_id", "any")
    }

    /** 고른 항로를 계정에 저장(폰도 따라온다). 게스트면 아무것도 안 함. */
    suspend fun saveCourse(id: String) {
        val user = account ?: return
        api.call("/api/preferences/explore-direction", user.token, JSONObject().put("explore_direction_id", id))
    }

    /** 마지막으로 받은 분위기(없으면 빈 목록). 기다리지 않는다 — 미디어 화면 목록이 바로 읽는다. */
    val lastMoods: List<Mood> get() = moodList

    /** 같은 시간대에 받은 지 1시간 안이면 다시 받을 필요가 없다. */
    fun moodsFresh(hour: Int, nowMs: Long = System.currentTimeMillis()) =
        moodList.isNotEmpty() && moodSlot == TimeSlot.of(hour) && nowMs - moodAt in 0 until MOOD_TTL_MS

    /**
     * 지금 시간대의 분위기(`/api/moods?hour=`). 받기에 실패하면 마지막으로 받은 목록을 돌려주고,
     * 그것도 없으면 예외를 그대로 던진다 — 화면이 "분위기를 불러오지 못했어요"를 띄운다.
     */
    suspend fun moods(hour: Int, nowMs: Long = System.currentTimeMillis()): List<Mood> = moodLock.withLock {
        val slot = TimeSlot.of(hour)
        if (moodsFresh(hour, nowMs)) return@withLock moodList
        try {
            val raw = api.call("/api/moods?hour=$hour")
            val parsed = withContext(Dispatchers.Default) { Mood.parseAll(raw) }
            require(parsed.isNotEmpty()) { "No moods" }
            moodList = parsed; moodSlot = slot; moodAt = nowMs
            parsed
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            moodList.ifEmpty { throw error }
        }
    }


    // 분위기 방송은 풀에 아직 없을 수 있다(기기의 풀은 최대 6시간 묵는다).
    fun find(id: String): Station? = (state.value.stations + state.value.favorites + listOfNotNull(state.value.lastPlayed)).find { it.id == id }
        ?: moodList.firstNotNullOfOrNull { mood -> mood.stations.find { it.id == id } }
    private fun publish(status: String = mutable.value.status) {
        mutable.value = mutable.value.copy(favorites = favorites.visible, lastPlayed = lastPlayed,
            accountName = account?.nickname, accountId = account?.id, pending = favorites.pending.size + if (pendingLast != null) 1 else 0,
            status = status)
    }
    private suspend fun persist() {
        check(storeHealthy)
        // Capture state here, on the calling thread; the heavy JSON build (~5k stations) runs off main.
        val favoritesNow = favorites.json()
        val stations = mutable.value.stations
        val last = lastPlayed
        val pendingLastNow = pendingLast
        val pendingLastAtNow = pendingLastAt
        val seenAtNow = seenAt
        val accountNow = account
        withContext(Dispatchers.IO) {
            val value = JSONObject().put("favorites", favoritesNow)
                .put("pool", JSONArray(stations.map { it.json() }))
                .put("last", last?.json()).put("pending_last", pendingLastNow?.json())
                .put("pending_last_at", pendingLastAtNow).put("last_seen_at", seenAtNow)
            accountNow?.let { value.put("account", JSONObject().put("id", it.id).put("nickname", it.nickname).put("token", it.token)) }
            vault.write(value)
        }
    }

    private companion object {
        const val MOOD_TTL_MS = 60 * 60_000L // 서버 캐시(max-age=3600)와 같은 1시간
    }
}
