package com.voyadio.automotive.pleos

import com.voyadio.automotive.drift.Course
import com.voyadio.automotive.drift.Courses
import com.voyadio.automotive.voice.VoiceAction
import com.voyadio.automotive.voice.VoiceResult
import com.voyadio.automotive.voice.VoiceResult.Outcome
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A call from Gleo, the Pleos car assistant (2026-10-21), to a function of our schema.json (src/pleos/gleo/).
 * Gleo's documents disagree on where the parameters travel — a JSON "actionParameters" extra, a JSON "ACTION_PARAM"
 * extra holding {packageName, action, actionParameters}, or one extra per parameter — so every form is read.
 * Pure (org.json and java.util only).
 */
internal class GleoCall(val function: String, private val params: Map<String, String>) {
    /** What the radio should do; null when a course call names no course we have (Gleo then asks the driver). */
    fun action(): VoiceAction? = when (function) {
        NEXT -> VoiceAction.Next
        SAVE -> VoiceAction.Save
        else -> course()?.let(VoiceAction::Steer)
    }

    /** The most specific first: a country, then a language, then a kind of program. */
    private fun course(): Course? {
        params["countryCode"]?.let(::country)?.let { return Courses.country(it) }
        params["languageCode"]?.let(::language)?.let { Courses.byId("lang_$it") }?.let { return it }
        return params["programType"]?.let(::program)
    }

    companion object {
        const val NEXT = "DRIFT_NEXT_STATION"
        const val COURSE = "SET_DRIFT_COURSE"
        const val SAVE = "SAVE_CURRENT_STATION"
        /** A code the assistant may say that ISO does not use. */
        private val ALIASES = mapOf("UK" to "GB")

        /** [extras]: the intent's string extras. Null for anything but our three functions. */
        fun from(action: String?, extras: Map<String, String?>): GleoCall? {
            if (action != NEXT && action != COURSE && action != SAVE) return null
            val params = extras["actionParameters"]?.let(::json)
                ?: extras["ACTION_PARAM"]?.let(::json)?.let { wrapper ->
                    wrapper.optJSONObject("actionParameters") ?: json(wrapper.optString("actionParameters")) ?: wrapper
                }
                ?: JSONObject(extras.filterValues { it != null })
            return GleoCall(action, params.keys().asSequence().associateWith { params.optString(it) })
        }

        private fun json(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

        /** Names Gleo might use instead of a code: English, Korean (Pleos's own market), and the car's language. */
        private fun namesIn() = listOf(Locale.ENGLISH, Locale.KOREAN, Locale.getDefault()).distinct()

        /** "JP", "jp", "Japan", "일본" → "JP". */
        private fun country(value: String): String? {
            val text = value.trim().let { ALIASES[it.uppercase(Locale.ROOT)] ?: it }
            val locales = namesIn()
            return Locale.getISOCountries().firstOrNull { code ->
                code.equals(text, ignoreCase = true) || locales.any { Locale("", code).getDisplayCountry(it).equals(text, ignoreCase = true) }
            }
        }

        /** "ja", "Japanese", "일본어" → "ja". */
        private fun language(value: String): String? {
            val text = value.trim()
            val locales = namesIn()
            return Locale.getISOLanguages().firstOrNull { code ->
                code.equals(text, ignoreCase = true) || locales.any { Locale(code).getDisplayLanguage(it).equals(text, ignoreCase = true) }
            }
        }

        /** "classical", "classical music", "news" (a tag of public radio) → the content course. */
        private fun program(value: String): Course? {
            val key = value.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z]+"), "_").trim('_')
            if (key.length < 3) return null
            return Courses.CONTENT.firstOrNull { it.id == key || key.startsWith(it.id) || it.id.startsWith(key) }
                ?: Courses.CONTENT.firstOrNull { course -> course.tags.any { it.replace(' ', '_') == key } }
        }
    }
}

/** Gleo's Action Callback: what happened as an English sentence Gleo makes its own reply from, plus the facts. */
internal object GleoReply {
    const val ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    /** The station in English. */
    data class Facts(val station: String? = null, val country: String? = null, val city: String? = null)

    fun json(function: String, result: VoiceResult?, facts: Facts): JSONObject {
        val course = result?.courseId?.let(Courses::byId)
        val (status, message) = when (result?.outcome) {
            Outcome.PLAYING -> "success" to listOfNotNull(course?.let { "Drifting through ${stations(it)}." }, playing(facts))
                .joinToString(" ").ifEmpty { "Drifting to another station." }
            Outcome.SAVED -> "success" to "Saved ${facts.station ?: "the station"} to favorites."
            Outcome.ALREADY_SAVED -> "success" to "${facts.station ?: "The station"} is already in favorites."
            Outcome.NO_STATIONS -> "fail" to (course?.let { "No ${stations(it)} are available right now." }
                ?: "No stations are available right now. The car may be out of signal.")
            Outcome.NOTHING_PLAYING -> "fail" to "No station is playing to save."
            Outcome.FAILED, null -> "error" to "Voyadio could not do that right now."
        }
        return JSONObject().put("functionName", function).put("status", status).put("message", message)
            // The voice guide names a new station itself (in the car's language): Gleo stays quiet, not on top of it.
            .put("mode", if (result?.outcome == Outcome.PLAYING && result.announced) "silent" else "voice")
            .apply { data(facts)?.let { put("data", it) } }
    }

    fun asking(function: String): JSONObject = JSONObject().put("functionName", function).put("status", "asking")
        .put("message", "Which country, language, or kind of program should Voyadio drift through?").put("mode", "voice")

    /** "stations from Japan", "Japanese-language stations", "classical music stations". */
    fun stations(course: Course): String {
        Courses.countryOf(course)?.let { return "stations from ${Locale("", it).getDisplayCountry(Locale.ENGLISH)}" }
        if (course.id.startsWith(LANGUAGE)) return "${Locale(course.id.removePrefix(LANGUAGE)).getDisplayLanguage(Locale.ENGLISH)}-language stations"
        return "${PROGRAMS[course.id] ?: "matching"} stations"
    }

    private const val LANGUAGE = "lang_"
    private val PROGRAMS = mapOf("public_radio" to "public radio", "podcasts" to "podcast", "popular_music" to "popular music",
        "classical" to "classical music", "asmr" to "ASMR")

    private fun playing(facts: Facts): String? {
        val station = facts.station ?: return null
        val place = listOfNotNull(facts.city, facts.country).joinToString(", ")
        return if (place.isEmpty()) "Now playing $station." else "Now playing $station from $place."
    }

    /** Recommended even when Gleo stays quiet: it keeps the conversation's context ("저장해 줘" right after). */
    private fun data(facts: Facts): String? = facts.station?.let {
        JSONArray().put(JSONObject().put("station", it).putOpt("country", facts.country).putOpt("city", facts.city)).toString()
    }
}
