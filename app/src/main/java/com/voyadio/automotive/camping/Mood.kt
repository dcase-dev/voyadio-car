package com.voyadio.automotive.camping

import com.voyadio.automotive.data.Station
import com.voyadio.automotive.data.stations
import org.json.JSONObject
import java.util.Locale

/**
 * 캠핑 분위기 하나(서버 `/api/moods`). 방송 순서는 서버가 하루 고정으로 섞어 둔 그대로 쓴다.
 * [titles]: 언어 키(휴대폰 앱 번역 파일 이름과 같음: ko, en, ja, pt_BR, zh_Hant…) → 이름. 2026-10-15부터 50개 언어.
 */
data class Mood(val id: String, val titles: Map<String, String>, val stations: List<Station>) {
    /** 차 언어의 이름, 없으면 영어, 그것도 없으면 id. */
    fun title(locale: Locale = Locale.getDefault()): String =
        keys(locale).firstNotNullOfOrNull { titles[it] } ?: titles["en"] ?: id

    companion object {
        /** 기기 언어 → 서버 언어 키 후보(앞에서부터). 안드로이드의 옛 코드(iw·in·tl)와 번체·브라질 포르투갈어를 맞춘다. */
        internal fun keys(locale: Locale): List<String> {
            val lang = when (val l = locale.language) { "iw" -> "he"; "in" -> "id"; "tl" -> "fil"; "no" -> "nb"; else -> l }
            return when {
                lang == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) -> listOf("zh_Hant", "zh")
                lang == "pt" && locale.country == "BR" -> listOf("pt_BR", "pt")
                else -> listOf(lang)
            }
        }

        /** 방송이 하나라도 있는 분위기만, 서버 순서대로. 형식이 틀린 분위기는 건너뛴다. */
        fun parseAll(raw: JSONObject): List<Mood> {
            val list = raw.optJSONArray("moods") ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = o.optJSONObject("title")
                val titles = title?.keys()?.asSequence()?.mapNotNull { key ->
                    title.optString(key).takeIf { it.isNotBlank() }?.let { key to it }
                }?.toMap().orEmpty()
                val stations = o.optJSONArray("stations")?.stations().orEmpty().distinctBy { it.url }
                if (stations.isEmpty()) null else Mood(id, titles, stations)
            }
        }

        /** 음성·검색("모닥불 재즈 틀어줘"): 질의가 분위기 이름(어느 언어든)을 담거나 이름이 질의를 담으면 그 분위기. */
        fun matching(query: String, moods: List<Mood>): List<Mood> {
            val q = query.trim().lowercase()
            if (q.length < 2) return emptyList()
            return moods.filter { mood ->
                mood.titles.values.any { name -> name.lowercase().let { it.contains(q) || q.contains(it) } }
            }
        }
    }
}
