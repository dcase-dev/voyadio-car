package com.voyadio.automotive

/**
 * Text for the car's own media screen (Drive View), the only screen a driver sees while moving:
 * the camp screen is parked-only, so the journey line has to travel with the channel itself.
 */
internal object DriveView {
    /** "Uruguay · Montevideo에서 오는 방송"; just the country when the capital is unknown. */
    fun subtitle(country: String, capital: String?, from: (String) -> String): String {
        val place = capital?.trim().orEmpty()
        if (place.isEmpty()) return country
        return listOf(country.trim(), from(place)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
