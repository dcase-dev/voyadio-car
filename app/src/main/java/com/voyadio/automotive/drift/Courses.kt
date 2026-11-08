package com.voyadio.automotive.drift

/**
 * 항로 목록 — 휴대폰 앱 `lib/models/explore_direction.dart`와 같은 이름(id)·태그·나라 묶음.
 * 같은 id라야 로그인 계정의 항로(`/api/preferences/explore-direction`)가 폰↔차로 이어진다.
 * 나라 하나 항로(`country_jp`)는 차에만 있다(1차, 설계 §3).
 */
object Courses {
    val CONTENT: List<Course.Content> = listOf(
        Course.Content("public_radio", listOf("public radio", "public", "news", "talk"),
            listOf("kbs", "bbc", "nhk", "npr", "pbs", "cbc", "abc radio", "rnz", "rte", "deutschlandfunk", "france inter")),
        Course.Content("podcasts", listOf("podcast", "podcasts")),
        Course.Content("popular_music", listOf("pop", "hits", "top 40", "top40", "chart", "music")),
        Course.Content("classical", listOf("classical", "classic")),
        Course.Content("asmr", listOf("asmr", "sleep", "relaxation", "ambient")),
    )

    val LANGUAGE: List<Course.Countries> = listOf(
        Course.Countries("lang_en", listOf("US", "GB", "CA", "AU", "NZ", "IE")),
        Course.Countries("lang_zh", listOf("CN", "TW", "HK", "MO", "SG")),
        Course.Countries("lang_ja", listOf("JP")),
        Course.Countries("lang_ko", listOf("KR")),
        Course.Countries("lang_es", listOf("ES", "MX", "AR", "CO", "CL", "PE", "VE", "UY", "PY", "BO", "EC", "CR", "DO",
            "GT", "HN", "NI", "PA", "SV", "PR")),
        Course.Countries("lang_fr", listOf("FR", "BE", "CA", "CH", "LU", "MC")),
        Course.Countries("lang_de", listOf("DE", "AT", "CH")),
        Course.Countries("lang_pt", listOf("PT", "BR", "AO", "MZ")),
    )

    private const val COUNTRY = "country_"
    private const val TRIP = "trip_"

    /** 나라 하나 항로. */
    fun country(code: String): Course.Countries = Course.Countries(COUNTRY + code.lowercase(), listOf(code.uppercase()))

    /**
     * '가는 길' 항로(2026-10-21, 길 위의 라디오 — 설계 §4): 지금 차가 있는 나라 → 내비 목적지의 나라(같으면 하나).
     * 차에만 있고 기억하지 않는다([DriftState.trip]). 나라들을 id에 담아 차량 미디어 목록에서 눌러도 그대로 돌아온다.
     */
    fun trip(here: String?, to: String): Course.Countries {
        val codes = listOfNotNull(here, to).map { it.uppercase() }.distinct()
        return Course.Countries(TRIP + codes.joinToString("_") { it.lowercase() }, codes)
    }

    fun isTrip(course: Course?): Boolean = course?.id?.startsWith(TRIP) == true

    /** 저장된 id → 항로. 모르는 id(옛 값·다른 기기의 새 항로)는 null — 부르는 쪽이 '전체'로. */
    fun byId(id: String?): Course? = when {
        id == null -> null
        id == Course.Any.id -> Course.Any
        id.startsWith(COUNTRY) && id.length == COUNTRY.length + 2 -> country(id.removePrefix(COUNTRY))
        id.startsWith(TRIP) -> id.removePrefix(TRIP).split('_').filter { it.length == 2 }.map { it.uppercase() }
            .takeIf { it.isNotEmpty() }?.let { Course.Countries(id, it) }
        else -> (CONTENT + LANGUAGE).find { it.id == id }
    }

    /** 휴대폰과 나누는 항로인가(나라 하나·분위기는 차에만). */
    fun shared(course: Course): Boolean = course == Course.Any || course in CONTENT || course in LANGUAGE

    /** 나라 하나 항로의 나라 코드(아니면 null). */
    fun countryOf(course: Course): String? = (course as? Course.Countries)?.takeIf { it.id.startsWith(COUNTRY) }?.codes?.single()
}
