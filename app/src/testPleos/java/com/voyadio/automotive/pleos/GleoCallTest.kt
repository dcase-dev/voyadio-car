package com.voyadio.automotive.pleos

import com.voyadio.automotive.drift.Course
import com.voyadio.automotive.drift.Courses
import com.voyadio.automotive.voice.VoiceAction
import com.voyadio.automotive.voice.VoiceResult
import com.voyadio.automotive.voice.VoiceResult.Outcome
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GleoCallTest {
    private fun course(vararg params: Pair<String, String>): Course? =
        (GleoCall.from("SET_DRIFT_COURSE", mapOf(*params))!!.action() as? VoiceAction.Steer)?.course

    @Test fun `parameters are read from every form Gleo's documents describe`() {
        val japan = VoiceAction.Steer(Courses.country("JP"))
        val forms = listOf(
            mapOf("actionParameters" to """{"countryCode":"JP"}"""),
            mapOf("ACTION_PARAM" to """{"packageName":"p","action":"SET_DRIFT_COURSE","actionParameters":{"countryCode":"JP"}}"""),
            mapOf("ACTION_PARAM" to """{"actionParameters":"{\"countryCode\":\"JP\"}"}"""),
            mapOf("ACTION_PARAM" to """{"countryCode":"JP"}"""),
            mapOf("countryCode" to "JP"),
        )
        for (extras in forms) assertEquals("$extras", japan, GleoCall.from("SET_DRIFT_COURSE", extras)!!.action())
    }

    @Test fun `only our three functions are answered`() {
        assertNull(GleoCall.from("PLAY_FREQUENCY", emptyMap()))
        assertNull(GleoCall.from(null, emptyMap()))
        assertEquals(VoiceAction.Next, GleoCall.from("DRIFT_NEXT_STATION", emptyMap())!!.action())
        assertEquals(VoiceAction.Save, GleoCall.from("SAVE_CURRENT_STATION", emptyMap())!!.action())
    }

    @Test fun `a course is a country first, then a language, then a kind of program`() {
        assertEquals(Courses.country("BR"), course("countryCode" to "br", "languageCode" to "pt"))
        assertEquals(Courses.byId("lang_ja"), course("languageCode" to "JA"))
        assertEquals(Courses.byId("classical"), course("programType" to "classical"))
    }

    @Test fun `names in place of codes still find the course`() {
        assertEquals(Courses.country("JP"), course("countryCode" to "Japan"))
        assertEquals(Courses.country("JP"), course("countryCode" to "일본"))
        assertEquals(Courses.byId("lang_es"), course("languageCode" to "Spanish"))
        assertEquals(Courses.byId("classical"), course("programType" to "classical music"))
        assertEquals(Courses.byId("public_radio"), course("programType" to "public radio"))
    }

    @Test fun `a course call with nothing usable asks the driver instead of guessing`() {
        assertNull(course())
        assertNull(course("countryCode" to "Atlantis"))
        assertNull("a language with no course of its own", course("languageCode" to "sw"))
        assertNull(course("programType" to "x"))
        assertNull(GleoCall.from("SET_DRIFT_COURSE", mapOf("actionParameters" to "not json"))!!.action())
    }
}

class GleoReplyTest {
    private val zambia = GleoReply.Facts(station = "One Love Radio", country = "Zambia", city = "Lusaka")

    @Test fun `a new station the radio names itself keeps Gleo quiet`() {
        val json = GleoReply.json("DRIFT_NEXT_STATION", VoiceResult(Outcome.PLAYING, "s", announced = true), zambia)
        assertEquals("DRIFT_NEXT_STATION", json.getString("functionName"))
        assertEquals("success", json.getString("status"))
        assertEquals("silent", json.getString("mode"))
        assertEquals("Now playing One Love Radio from Lusaka, Zambia.", json.getString("message"))
        val data = JSONArray(json.getString("data")).getJSONObject(0)
        assertEquals("One Love Radio", data.getString("station"))
        assertEquals("Zambia", data.getString("country"))
    }

    @Test fun `without the voice guide Gleo speaks, and a course is named`() {
        val result = VoiceResult(Outcome.PLAYING, "s", courseId = "country_jp", announced = false)
        val json = GleoReply.json("SET_DRIFT_COURSE", result, GleoReply.Facts("NHK Radio 1", "Japan", "Tokyo"))
        assertEquals("voice", json.getString("mode"))
        assertEquals("Drifting through stations from Japan. Now playing NHK Radio 1 from Tokyo, Japan.", json.getString("message"))
    }

    @Test fun `saving answers with the station`() {
        val saved = GleoReply.json("SAVE_CURRENT_STATION", VoiceResult(Outcome.SAVED, "s"), zambia)
        assertEquals("success", saved.getString("status"))
        assertEquals("voice", saved.getString("mode"))
        assertEquals("Saved One Love Radio to favorites.", saved.getString("message"))
        val again = GleoReply.json("SAVE_CURRENT_STATION", VoiceResult(Outcome.ALREADY_SAVED, "s"), zambia)
        assertEquals("success", again.getString("status"))
        assertEquals("One Love Radio is already in favorites.", again.getString("message"))
    }

    @Test fun `what went wrong is told apart for Gleo`() {
        val none = GleoReply.json("SET_DRIFT_COURSE", VoiceResult(Outcome.NO_STATIONS, courseId = "country_is"), GleoReply.Facts())
        assertEquals("fail", none.getString("status"))
        assertEquals("No stations from Iceland are available right now.", none.getString("message"))
        assertFalse("no station, no data", none.has("data"))
        assertEquals("fail", GleoReply.json("SAVE_CURRENT_STATION", VoiceResult(Outcome.NOTHING_PLAYING), GleoReply.Facts()).getString("status"))
        assertEquals("error", GleoReply.json("SAVE_CURRENT_STATION", VoiceResult(Outcome.FAILED), GleoReply.Facts()).getString("status"))
        assertEquals("error", GleoReply.json("DRIFT_NEXT_STATION", null, GleoReply.Facts()).getString("status"))
    }

    @Test fun `a course call with nothing usable asks back`() {
        val json = GleoReply.asking("SET_DRIFT_COURSE")
        assertEquals("asking", json.getString("status"))
        assertEquals("voice", json.getString("mode"))
        assertTrue(json.getString("message").endsWith("?"))
    }

    @Test fun `courses are put into English for Gleo`() {
        assertEquals("stations from Japan", GleoReply.stations(Courses.country("JP")))
        assertEquals("Japanese-language stations", GleoReply.stations(Courses.byId("lang_ja")!!))
        assertEquals("classical music stations", GleoReply.stations(Courses.byId("classical")!!))
    }
}
