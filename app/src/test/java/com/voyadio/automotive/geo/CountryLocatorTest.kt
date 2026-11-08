package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CountryLocatorTest {
    private val square = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0)

    @Test fun `ray casting finds points inside a ring`() {
        assertTrue(CountryLocator.contains(square, LatLon(5.0, 5.0)))
        assertFalse(CountryLocator.contains(square, LatLon(15.0, 5.0)))
    }

    @Test fun `falls back to the nearest capital outside every outline`() {
        val atlas = WorldAtlas(
            mapOf("AA" to Country("AA", "A", "A", LatLon(5.0, 5.0), null, emptySet()),
                  "BB" to Country("BB", "B", "B", LatLon(50.0, 50.0), null, emptySet())),
            mapOf("AA" to listOf(square)),
        )
        val locator = CountryLocator(atlas)
        assertEquals("AA", locator.locate(LatLon(5.0, 5.0)))
        assertEquals("BB", locator.locate(LatLon(45.0, 45.0)))
    }

    @Test fun `real map - Seoul and Paris`() {
        val locator = CountryLocator(WorldAtlas.parse(File("src/main/assets/geo/world.json").readText()))
        assertEquals("KR", locator.locate(LatLon(37.5665, 126.978)))
        assertEquals("FR", locator.locate(LatLon(48.8566, 2.3522)))
    }
}
