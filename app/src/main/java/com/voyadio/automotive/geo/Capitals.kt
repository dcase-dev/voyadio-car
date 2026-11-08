package com.voyadio.automotive.geo

import org.json.JSONObject
import java.util.Locale

/**
 * Capital names in the car's language (PO 2026-10-21: "베네수엘라 · Caracas" mixed scripts).
 * From Wikidata (CC0) via scripts/build-car-capitals.py; only names that differ from the map's English one are kept,
 * so a missing name falls back to the English GeoNames name already in [WorldAtlas].
 */
class Capitals(private val names: Map<String, Map<String, String>>) {
    /** [english] (the map's name) in [locale]'s language, or [english] itself. */
    fun name(code: String, english: String, locale: Locale = Locale.getDefault()): String =
        names[code.uppercase(Locale.ROOT)]?.get(key(locale)) ?: english

    companion object {
        fun parse(text: String): Capitals {
            val all = JSONObject(text).getJSONObject("names")
            return Capitals(all.keys().asSequence().associateWith { code ->
                val labels = all.getJSONObject(code)
                labels.keys().asSequence().associateWith(labels::getString)
            })
        }

        /** The file's language key for [locale] — the app's resource folders map onto these (in → id, iw → he …). */
        fun key(locale: Locale): String {
            val lang = when (locale.language) { "in" -> "id"; "iw" -> "he"; "tl" -> "fil"; "no" -> "nb"; else -> locale.language }
            return when (lang) {
                "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-hant" else "zh"
                "pt" -> if (locale.country == "BR") "pt-br" else "pt"
                else -> lang
            }
        }
    }
}
