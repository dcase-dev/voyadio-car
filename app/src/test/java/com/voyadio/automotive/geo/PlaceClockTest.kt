package com.voyadio.automotive.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class PlaceClockTest {
    /** 23:05 in Seoul and Tokyo, 16:05 in Windhoek, 10:05 in New York. */
    private val now = Instant.parse("2026-10-21T14:05:00Z")

    @Test fun `a country with one zone uses it`() {
        assertEquals(ZoneId.of("Africa/Windhoek"), PlaceClock.zone("NA", listOf("Africa/Windhoek"), "Windhoek", 17.08, now))
    }

    @Test fun `big countries use the capital's zone, not the first in the list`() {
        val us = listOf("America/Los_Angeles", "America/Chicago", "America/New_York")
        assertEquals(ZoneId.of("America/New_York"), PlaceClock.zone("US", us, "Washington, D.C.", -77.04, now))
        // Canberra keeps summer time like Sydney; Brisbane, the same standard hour, does not.
        val au = listOf("Australia/Brisbane", "Australia/Perth", "Australia/Sydney")
        assertEquals(ZoneId.of("Australia/Sydney"), PlaceClock.zone("AU", au, "Canberra", 149.13, now))
        val ru = listOf("Europe/Kaliningrad", "Europe/Moscow", "Asia/Vladivostok")
        assertEquals(ZoneId.of("Europe/Moscow"), PlaceClock.zone("RU", ru, "Moscow", 37.62, now))
    }

    @Test fun `elsewhere the capital's name, then its longitude, picks among several`() {
        assertEquals(ZoneId.of("Asia/Dhaka"), PlaceClock.zone("XX", listOf("Asia/Kolkata", "Asia/Dhaka"), "Dhaka", 90.41, now))
        assertEquals(ZoneId.of("Europe/Paris"), PlaceClock.zone("XX", listOf("Asia/Tokyo", "Europe/Paris"), "Nowhere", 2.35, now))
    }

    @Test fun `no zone, or several and no capital, says nothing`() {
        assertNull(PlaceClock.zone("XX", emptyList(), "Nowhere", 0.0, now))
        assertNull(PlaceClock.zone("XX", listOf("Asia/Tokyo", "Europe/Paris"), null, null, now))
        assertNull("an unknown zone name is skipped", PlaceClock.zone("XX", listOf("Mars/Olympus"), null, null, now))
    }

    @Test fun `the hour is said in the car's language, and not at all when it is the car's own`() {
        assertEquals("오후 11:05", PlaceClock.spoken(ZoneId.of("Asia/Tokyo"), ZoneId.of("Africa/Windhoek"), now, Locale.KOREAN))
        assertEquals("10:05 AM", PlaceClock.spoken(ZoneId.of("America/New_York"), ZoneId.of("Asia/Seoul"), now, Locale.US)
            ?.replace(' ', ' '))
        assertNull("Tokyo keeps Seoul's hour", PlaceClock.spoken(ZoneId.of("Asia/Tokyo"), ZoneId.of("Asia/Seoul"), now, Locale.KOREAN))
    }
}
