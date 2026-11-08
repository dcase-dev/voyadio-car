package com.voyadio.automotive.camping

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MoodTest {
    private fun station(n: Int) = JSONObject().put("stationuuid", "u$n").put("name", "Radio $n")
        .put("url_resolved", "https://radio.example.test/$n").put("country", "Netherlands").put("countrycode", "nl")
    private fun mood(id: String, ko: String, en: String, vararg stations: Int) = JSONObject().put("id", id)
        .put("title", JSONObject().put("ko", ko).put("en", en))
        .put("stations", JSONArray(stations.map(::station)))
    private fun body(vararg moods: JSONObject) = JSONObject().put("slot", "night").put("moods", JSONArray(moods.toList()))

    @Test fun `parses titles and stations in server order`() {
        val moods = Mood.parseAll(body(mood("campfire_jazz", "모닥불 재즈", "Campfire jazz", 1, 2, 3)))
        assertEquals(1, moods.size)
        assertEquals("campfire_jazz", moods[0].id)
        assertEquals(listOf("Radio 1", "Radio 2", "Radio 3"), moods[0].stations.map { it.name })
        assertEquals("NL", moods[0].stations[0].countryCode)
    }

    @Test fun `skips moods without stations or id`() {
        val moods = Mood.parseAll(body(mood("empty", "빈", "Empty"), mood("", "이름 없음", "No id", 1), mood("ok", "좋음", "Ok", 2)))
        assertEquals(listOf("ok"), moods.map { it.id })
    }

    @Test fun `a broken body is an empty list`() {
        assertTrue(Mood.parseAll(JSONObject().put("error", "hour must be 0-23")).isEmpty())
    }

    @Test fun `title follows the car language and falls back to English`() {
        val m = Mood.parseAll(body(mood("night_jazz", "밤의 재즈", "Night jazz", 1)
            .apply { getJSONObject("title").put("ja", "夜のジャズ").put("zh_Hant", "夜晚爵士").put("zh", "夜晚爵士简").put("pt_BR", "Jazz noturno BR").put("he", "ג'אז לילי") }))[0]
        assertEquals("밤의 재즈", m.title(Locale.KOREAN))
        assertEquals("夜のジャズ", m.title(Locale.JAPANESE))
        assertEquals("Night jazz", m.title(Locale.GERMAN)) // 이름이 없으면 영어
        assertEquals("夜晚爵士", m.title(Locale.TRADITIONAL_CHINESE))
        assertEquals("夜晚爵士", m.title(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("夜晚爵士简", m.title(Locale.SIMPLIFIED_CHINESE))
        assertEquals("Jazz noturno BR", m.title(Locale.forLanguageTag("pt-BR")))
        assertEquals("Night jazz", m.title(Locale.forLanguageTag("pt-PT"))) // pt 이름이 없으면 영어
        assertEquals("ג'אז לילי", m.title(Locale("iw"))) // 안드로이드 옛 히브리어 코드
    }

    @Test fun `older servers with only Korean and English still work`() {
        val m = Mood.parseAll(body(mood("night_jazz", "밤의 재즈", "Night jazz", 1)))[0]
        assertEquals("Night jazz", m.title(Locale.JAPANESE))
    }

    @Test fun `voice phrases find the mood by name`() {
        val moods = Mood.parseAll(body(mood("campfire_jazz", "모닥불 재즈", "Campfire jazz", 1),
            mood("night_jazz", "밤의 재즈", "Night jazz", 2), mood("ambient", "별 보며 앰비언트", "Stargazing ambient", 3)))
        assertEquals(listOf("campfire_jazz"), Mood.matching("모닥불 재즈 틀어줘", moods).map { it.id })
        assertEquals(listOf("campfire_jazz", "night_jazz"), Mood.matching("jazz", moods).map { it.id })
        assertEquals(listOf("ambient"), Mood.matching("stargazing ambient", moods).map { it.id })
        val jaMoods = Mood.parseAll(body(mood("campfire_jazz", "모닥불 재즈", "Campfire jazz", 1).apply { getJSONObject("title").put("ja", "焚き火ジャズ") }))
        assertEquals(listOf("campfire_jazz"), Mood.matching("焚き火ジャズ", jaMoods).map { it.id }) // 음성은 어느 언어 이름으로도
        assertTrue(Mood.matching("x", moods).isEmpty())
    }
}
