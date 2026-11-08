package com.voyadio.automotive.geo

import com.voyadio.automotive.data.Station
import org.junit.Assert.*
import org.junit.Test

class GlowAndTimeTest {
    @Test fun `favorite counts map to four brightness steps`() {
        assertEquals(listOf(0, 1, 2, 2, 3, 3, 4, 4), listOf(0, 1, 2, 4, 5, 9, 10, 40).map(FavoriteGlow::level))
    }

    @Test fun `levels are counted per country code`() {
        val stations = List(5) { Station("https://n$it.example/s", "N$it", countryCode = "NL") } +
            Station("https://k.example/s", "K", countryCode = "KR") + Station("https://x.example/s", "X")
        assertEquals(mapOf("NL" to 3, "KR" to 1), FavoriteGlow.levels(stations))
    }

    @Test fun `long trips read as days and hours`() {
        assertEquals(TravelTime(6, 14, 0), TravelTime.of(158.2))
        assertEquals(TravelTime(2, 0, 0), TravelTime.of(47.9))
    }

    @Test fun `short trips read as hours and ten-minute steps`() {
        assertEquals(TravelTime(0, 5, 40), TravelTime.of(5.68))
        assertEquals(TravelTime(0, 1, 0), TravelTime.of(0.98))
    }

    @Test fun `just under a day rounds up to one day, not 24 hours`() {
        assertEquals(TravelTime(1, 0, 0), TravelTime.of(23.95))
        assertEquals(TravelTime(1, 0, 0), TravelTime.of(24.0))
    }
}
