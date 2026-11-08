package com.voyadio.automotive.geo

import com.voyadio.automotive.data.Station

/** 0 = dark, 1 = one station, 2 = 2–4, 3 = 5–9, 4 = 10 or more. */
object FavoriteGlow {
    fun level(count: Int) = when {
        count <= 0 -> 0
        count == 1 -> 1
        count <= 4 -> 2
        count <= 9 -> 3
        else -> 4
    }

    fun levels(favorites: List<Station>): Map<String, Int> =
        favorites.filter { it.countryCode.isNotBlank() }
            .groupingBy { it.countryCode }.eachCount()
            .mapValues { level(it.value) }
}
