package com.voyadio.automotive

import java.text.Collator
import java.util.Locale

/** Continents for the car's country folders (2026-10-13): short lists a driver can glance at. */
internal enum class Continent { ASIA, EUROPE, AFRICA, NORTH_AMERICA, SOUTH_AMERICA, OCEANIA, OTHER }

/**
 * Finding a country without scrolling 200 rows (PO request 2026-10-13): by its name in the device language,
 * in English, as the station data spells it, or by its code — so "일본", "일본 라디오 틀어줘", "Japan" and "JP" all
 * find Japan. Words such as "라디오"/"radio" are ignored. Pure: names and continents come in from the caller.
 */
internal class CountryDirectory(
    /** Every name a country goes by, most familiar first (device language, English, the data's own spelling). */
    private val names: (String) -> List<String>,
    private val continentOf: (String) -> Continent,
    locale: Locale,
) {
    private val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }

    /** The name to show: the device language's when known. */
    fun label(code: String) = names(code).firstOrNull { it.isNotBlank() } ?: code

    /** Countries whose names fit [query], best first: whole name, then name start, then anywhere in a name. */
    fun find(query: String, codes: Collection<String>): List<String> {
        val words = clean(query)
        if (words.isEmpty()) return emptyList()
        val ranked = codes.mapNotNull { code -> rank(words, code)?.let { code to it } }
        return ranked.sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy(collator) { label(it.first) }).map { it.first }
    }

    /** [codes] by name in the device language's order, with [first] (recent, favourite) kept on top in its own order. */
    fun sorted(codes: Collection<String>, first: List<String> = emptyList()): List<String> {
        val pinned = first.distinct().filter { it in codes }
        return pinned + codes.filter { it !in pinned }.sortedWith(compareBy(collator) { label(it) })
    }

    /** [codes] grouped by continent, continents in [Continent] order, countries sorted inside each. */
    fun byContinent(codes: Collection<String>): List<Pair<Continent, List<String>>> =
        codes.groupBy(continentOf).toSortedMap().map { (continent, list) -> continent to sorted(list) }

    private fun rank(words: List<String>, code: String): Int? {
        if (words.size == 1 && words[0].equals(code, ignoreCase = true)) return 0
        val keys = names(code).map(::squash).filter { it.isNotEmpty() }
        val phrase = words.joinToString("")
        return when {
            keys.any { it == phrase } -> 0
            keys.any { it.startsWith(phrase) } -> 1
            phrase.length >= 2 && keys.any { phrase in it } -> 2
            // "일본 도쿄 라디오": a whole country name as one of the words.
            words.any { w -> keys.any { it == w } } -> 3
            // "일본으로 바꿔줘" (the talk button, 2026-10-21): a particle stuck to the name. The longest name wins —
            // "인도네시아로" is Indonesia, not India.
            else -> words.maxOf { w -> keys.filter { it.length >= 2 && w.startsWith(it) }.maxOfOrNull { it.length } ?: 0 }
                .takeIf { it > 0 }?.let { PARTICLE - it }
        }
    }

    companion object {
        /** Ranks for a name with a particle stuck on come after every other match: this less the name's length. */
        private const val PARTICLE = 100

        /** Spoken or typed filler that never names a country. */
        private val FILLER = setOf(
            "라디오", "방송", "채널", "틀어줘", "틀어", "켜줘", "들려줘", "듣기", "보야디오", "에서", "의",
            "radio", "radios", "station", "stations", "fm", "am", "play", "on", "voyadio", "from", "in",
        )

        /** Lower-case words without filler; Korean particles stuck to a word ("일본의") are trimmed. */
        fun clean(query: String): List<String> = query.lowercase(Locale.ROOT)
            .split(Regex("[\\s,.!?·]+"))
            .map { it.removeSuffix("의").removeSuffix("에서") }
            .filter { it.isNotEmpty() && it !in FILLER }

        private fun squash(name: String) = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        /** Names from the platform's country data: [locale]'s, then English, then [extra] (the stations' spelling). */
        fun platformNames(code: String, locale: Locale, vararg extra: String): List<String> {
            val region = Locale("", code)
            return (listOf(region.getDisplayCountry(locale), region.getDisplayCountry(Locale.ENGLISH)) + extra)
                .filter { it.isNotBlank() && !it.equals(code, ignoreCase = true) }.distinct()
        }
    }
}
