package com.voyadio.automotive.voyage

/**
 * Today's voyage (2026-10-22, design docs/superpowers/specs/2026-10-22-car-story-voyager-design.md §4): from the
 * moment the car leaves P until it is back in P, the countries whose stations were really heard on the road. The
 * road is the navigation's; the ears wandered the world — back in P the car tells where they went.
 * Kept in memory only; nothing leaves the car.
 */
class Voyage {
    var sailing = false
        private set
    /** Country code → the road distance to it when heard (km), null without the car's place. */
    private val countries = LinkedHashMap<String, Double?>()

    /** How many countries this (or the last) voyage met — none means the car only moved to park. */
    val met: Int get() = countries.size

    /** The car left P: a new voyage. */
    fun depart() { countries.clear(); sailing = true }

    /** A station of [code] was heard on the road; [km] how far its country was by road and sea. */
    fun heard(code: String, km: Double?) {
        if (!sailing || code.isBlank()) return
        val before = countries[code]
        if (code !in countries || (km != null && (before == null || km > before))) countries[code] = km
    }

    /** Back in P: what the voyage met, or null when there is nothing to tell (fewer than [MIN_COUNTRIES]). */
    fun dock(): Log? {
        if (!sailing) return null
        sailing = false
        if (countries.size < MIN_COUNTRIES) return null
        val far = countries.entries.filter { it.value != null }.maxByOrNull { it.value!! }
        return Log(countries.keys.toList(), far?.key, far?.value)
    }

    /** The countries met, in order, and the farthest of them (null without the car's place). */
    data class Log(val countries: List<String>, val farthest: String?, val farthestKm: Double?)

    companion object {
        const val MIN_COUNTRIES = 2
        /** A station counts once it has been heard this long (passing stations by does not). */
        const val HEARD_MS = 10_000L
    }
}
