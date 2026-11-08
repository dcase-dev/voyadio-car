package com.voyadio.automotive.drift

import android.content.SharedPreferences
import com.voyadio.automotive.data.Station
import kotlin.random.Random

/**
 * 지금 항로와 최근 들은 방송 — 앱 화면과 재생 서비스(같은 프로세스)가 함께 쓴다.
 * 재생 서비스가 대기열을 표류로 채우므로 앱 화면의 "다음", 운전대 버튼, 차량 미디어 화면이 모두 같은 표류를 탄다.
 * 항로는 기기에 기억한다([prefs]). 계정 항로(폰과 공유)는 [adoptFromServer]로 받는다.
 */
class DriftState(private val prefs: SharedPreferences? = null, private val random: Random = Random.Default) {
    @Volatile var course: Course = Courses.byId(prefs?.getString(COURSE, null)) ?: Course.Any
        private set
    private val recent = ArrayDeque<String>()
    /** 잠시 이 항로로(캠핑 화면의 분위기 항로) — 기억하지 않고, 캠핑 화면을 나가면 null. 있으면 [course]보다 먼저. */
    @Volatile var along: Course? = null

    /**
     * '가는 길' 항로(2026-10-21) — 내비 목적지로 가는 동안만. 기억하지 않는다: 도착·목적지 취소·앱 재시작이면 [course]로
     * 돌아간다. [along]보다 뒤, [course]보다 앞.
     */
    @Volatile var trip: Course.Countries? = null

    /** 운전자가 지금 따르는 항로: 가는 길이 있으면 그것, 아니면 기억한 항로. */
    val steering: Course get() = trip ?: course

    /** 사용자가 항로를 골랐다. '가는 길'이면 잠시만, 다른 항로면 가는 길을 끝내고 기억한다. */
    fun choose(next: Course) {
        if (Courses.isTrip(next)) { trip = next as? Course.Countries; return }
        trip = null
        course = next
        prefs?.edit()?.putString(COURSE, next.id)?.apply()
    }

    /**
     * 서버(폰과 공유하는) 항로를 봤다. **서버 값이 지난번 본 것과 달라졌을 때만**(폰에서 바꿨을 때만) 따른다 —
     * 차에서 고른 나라 하나 항로를 다른 설정 변경 때문에 덮지 않게. 따랐으면 true.
     */
    fun adoptFromServer(serverId: String): Boolean {
        val seen = prefs?.getString(SERVER_SEEN, null)
        if (seen == serverId) return false
        prefs?.edit()?.putString(SERVER_SEEN, serverId)?.apply()
        if (seen == null && course != Course.Any) return false // 처음 보는 서버 값: 차에서 이미 고른 항로를 지킨다
        val next = Courses.byId(serverId) ?: return false
        if (next == course) return false
        choose(next); return true
    }

    /** 내가 서버에 올렸다 — 다음에 같은 값을 '폰에서 바뀜'으로 오해하지 않게. */
    fun pushed(id: String) { prefs?.edit()?.putString(SERVER_SEEN, id)?.apply() }

    /** 실제로 들은 방송(2초 이상 소리가 난 것), 최근 것부터 — 항로의 '최근 들은 나라'. */
    @Synchronized fun heard(): List<String> = recent.reversed()

    @Synchronized fun remember(id: String) {
        recent.remove(id); recent.addLast(id)
        while (recent.size > Drift.RECENT * 2) recent.removeFirst()
    }

    /** [start] 다음으로 이어질 [size]개. */
    @Synchronized fun next(pool: List<Station>, start: Station?, size: Int, way: Course = along ?: trip ?: course): List<Station> =
        Drift.queue(pool, way, recent.toList(), start, size, random)

    companion object {
        /** 처음 대기열(고른 방송 뒤에 붙는 수)과 모자랄 때 더 붙이는 수. */
        const val FIRST = 19
        const val TOP_UP = 10
        const val LOW = 3
        private const val COURSE = "course"
        private const val SERVER_SEEN = "course_server_seen"
    }
}
