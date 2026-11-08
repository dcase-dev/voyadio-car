package com.voyadio.automotive.geo

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NightLightsTest {
    private val world by lazy { WorldAtlas.parse(File("src/main/assets/geo/world.json").readText()) }

    @Test fun `inside works on a simple square`() {
        val square = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0, 0.0, 0.0)
        assertTrue(NightLights.inside(5.0, 5.0, square))
        assertFalse(NightLights.inside(15.0, 5.0, square))
    }

    @Test fun `lights are deterministic, inside their country and capped`() {
        val a = NightLights.lights(world, perCountry = 10)
        val b = NightLights.lights(world, perCountry = 10)
        assertEquals(a, b)
        assertTrue(a.size in 500..world.countries.size * 10)
        val korea = world.outlines.getValue("KR")
        val nearSeoul = a.filter { GeoMath.distanceKm(it, world.countries.getValue("KR").capital) < 400 }
        assertTrue(nearSeoul.isNotEmpty())
        assertTrue(nearSeoul.all { p -> world.outlines.values.flatten().any { NightLights.inside(p.lat, p.lon, it) } })
        assertTrue(korea.isNotEmpty())
    }

    @Test fun `web joins only near neighbours, each pair once`() {
        val web = NightLights.web(world, maxKm = 1_800.0)
        assertTrue(web.isNotEmpty())
        assertTrue(web.all { GeoMath.distanceKm(it.first(), it.last()) < 1_800.0 + 1 })
        val ends = web.map { setOf(it.first(), it.last()) }
        assertEquals(ends.size, ends.toSet().size)
    }

    @Test fun `night data splits every fourth light into the bright set and keeps the arcs`() {
        val lights = (0 until 10).map { LatLon(it.toDouble(), it * 2.0) }
        val data = NightData.of(lights, listOf(listOf(LatLon(0.0, 0.0), LatLon(1.0, 1.0)), listOf(LatLon(5.0, 5.0))))
        assertEquals(3, data.brightCount) // indexes 0, 4, 8
        assertEquals(7, data.smallCount)
        assertEquals(1, data.arcCount) // a one-point arc is dropped
        val empty = NightData.of(emptyList(), emptyList())
        assertEquals(0, empty.smallCount + empty.brightCount + empty.arcCount)
    }
}
