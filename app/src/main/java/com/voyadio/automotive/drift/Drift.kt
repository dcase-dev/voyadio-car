package com.voyadio.automotive.drift

import com.voyadio.automotive.data.Station
import kotlin.random.Random

/**
 * 표류의 방향 — 항로(2026-10-15 설계 `2026-10-15-car-drift-course-design.md` §2).
 * 방송을 고르는 것이 아니라 방향만 정한다. 휴대폰 앱의 탐색 방향(ExploreDirection)·즐겨찾는 나라와 같은 생각.
 */
sealed interface Course {
    val id: String

    /** 항로 없음 — 세계 어디로든, 나라가 고르게 섞이게. */
    data object Any : Course { override val id = "any" }

    /** 내용 항로(공영·클래식…): 태그나 이름 단어가 맞는 방송. 8번에 1번은 세계 어디든(뜻밖의 신호). */
    data class Content(override val id: String, val tags: List<String>, val nameTerms: List<String> = emptyList()) : Course

    /** 나라·언어권 항로: 이 나라들 안에서만, 나라끼리 번갈아. 사용자가 분명히 고른 의도라 밖으로 새지 않는다. */
    data class Countries(override val id: String, val codes: List<String>) : Course

    /** 캠핑 분위기 항로: 이 방송들 안에서, 8번에 1번은 [wider](같은 시간대 다른 분위기) 안에서. */
    data class Stations(override val id: String, val stations: List<Station>, val wider: List<Station> = emptyList()) : Course
}

/** 나침반 한 칸 — 다음 방송을 고른다. 순수 규칙(기기 안 채널 풀만 씀, 네트워크 없음). */
object Drift {
    /** 뜻밖의 신호: 이 수만큼 넘길 때 한 번꼴(PO 결정 2026-10-15: 8번에 1번). */
    const val SURPRISE = 8
    /** 최근 들은 방송 이 개수는 다시 고르지 않는다. */
    const val RECENT = 30

    fun pick(pool: List<Station>, course: Course, recent: List<String>, current: Station?, random: Random): Station? {
        if (pool.isEmpty()) return null
        val avoid = recent.takeLast(RECENT).toSet() + listOfNotNull(current?.id)
        return when (course) {
            Course.Any -> diverse(pool, avoid, current, random)
            is Course.Content -> {
                val matching = pool.filter { matches(it, course) }
                if (matching.isEmpty() || random.nextInt(SURPRISE) == 0) diverse(pool, avoid, current, random)
                else diverse(matching, avoid, current, random)
            }
            is Course.Countries -> {
                val codes = course.codes.toSet()
                val inside = pool.filter { it.countryCode in codes }
                diverse(inside.ifEmpty { pool }, avoid, current, random)
            }
            is Course.Stations -> {
                val base = if (course.wider.isNotEmpty() && random.nextInt(SURPRISE) == 0) course.wider else course.stations
                // 목록이 짧다(분위기 하나 40개): '최근 30개 피하기'가 목록을 다 막지 않게 목록 절반까지만 피한다.
                val window = recent.takeLast(minOf(RECENT, base.size / 2)).toSet()
                anyOf(base, window, current, random) ?: anyOf(base, emptySet(), current, random)
                    ?: diverse(pool, avoid, current, random)
            }
        }
    }

    /**
     * 이 항로에 방송이 하나라도 있나(2026-10-21, 음성 명령). [pick]은 빈 항로를 세계 전체로 넓힌다 — 화면에서 누른 항로엔
     * 맞지만, "일본 라디오"라고 했는데 일본 방송이 없으면 엉뚱한 나라 대신 '없다'고 답해야 한다.
     */
    fun reaches(pool: List<Station>, course: Course): Boolean = when (course) {
        Course.Any -> pool.isNotEmpty()
        is Course.Content -> pool.any { matches(it, course) }
        is Course.Countries -> course.codes.toSet().let { codes -> pool.any { it.countryCode in codes } }
        is Course.Stations -> course.stations.isNotEmpty()
    }

    /** 지금 다음으로 [size]개 — 앞에서 고른 것도 '최근'으로 보고 이어 고른다. */
    fun queue(pool: List<Station>, course: Course, recent: List<String>, start: Station?, size: Int, random: Random): List<Station> {
        val out = ArrayList<Station>(size)
        val seen = ArrayList(recent).apply { start?.let { add(it.id) } }
        var current = start
        repeat(size) {
            val next = pick(pool, course, seen, current, random) ?: return out
            out += next; seen += next.id; current = next
        }
        return out
    }

    private fun matches(station: Station, course: Course.Content): Boolean {
        val tags = station.tags.lowercase().split(',').map { it.trim() }
        return course.tags.any { it in tags } || course.nameTerms.any { station.name.contains(it, ignoreCase = true) }
    }

    /** 나라를 먼저 고르고(지금 나라는 피함) 그 나라 방송 하나 — 큰 나라가 표류를 독차지하지 않게. */
    private fun diverse(list: List<Station>, avoid: Set<String>, current: Station?, random: Random): Station? {
        val byCountry = list.groupBy { it.countryCode.ifBlank { "?" } }
        val others = byCountry.keys.filter { it != current?.countryCode?.ifBlank { "?" } }.ifEmpty { byCountry.keys.toList() }
        for (country in others.shuffled(random)) {
            anyOf(byCountry.getValue(country), avoid, null, random)?.let { return it }
        }
        // 모두 최근에 들었다: 최근 피하기를 풀고 지금 방송만 피한다.
        return list.filter { it.id != current?.id }.ifEmpty { list }.random(random)
    }

    private fun anyOf(list: List<Station>, avoid: Set<String>, current: Station?, random: Random): Station? =
        list.filter { it.id !in avoid && it.id != current?.id }.randomOrNull(random)
}
