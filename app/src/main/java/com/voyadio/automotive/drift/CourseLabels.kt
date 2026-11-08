package com.voyadio.automotive.drift

import android.content.Context
import com.voyadio.automotive.R

/** 항로의 화면 이름(차 언어). 나라 하나 항로는 [countryName]이 기기 언어 나라 이름을 준다. */
object CourseLabels {
    fun label(context: Context, course: Course, countryName: (String) -> String): String {
        // '가는 길 · 프랑스 → 독일'(2026-10-21) — 국내면 '가는 길 · 대한민국'.
        if (Courses.isTrip(course)) return context.getString(R.string.course_trip,
            (course as Course.Countries).codes.joinToString(" → ", transform = countryName))
        Courses.countryOf(course)?.let { return countryName(it) }
        val res = when (course.id) {
            Course.Any.id -> R.string.course_any
            "public_radio" -> R.string.course_public_radio
            "podcasts" -> R.string.course_podcasts
            "popular_music" -> R.string.course_popular_music
            "classical" -> R.string.course_classical
            "asmr" -> R.string.course_asmr
            "lang_en" -> R.string.course_lang_en
            "lang_zh" -> R.string.course_lang_zh
            "lang_ja" -> R.string.course_lang_ja
            "lang_ko" -> R.string.course_lang_ko
            "lang_es" -> R.string.course_lang_es
            "lang_fr" -> R.string.course_lang_fr
            "lang_de" -> R.string.course_lang_de
            "lang_pt" -> R.string.course_lang_pt
            else -> R.string.course_any
        }
        return context.getString(res)
    }
}
