package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RoadNetTest {
    private val roads by lazy { RoadNet.parse(File("src/main/assets/geo/roads.json").readText()) }
    private val world by lazy { WorldAtlas.parse(File("src/main/assets/geo/world.json").readText()) }
    private val sea by lazy { SeaGrid.parse(File("src/main/assets/geo/sea.json").readText()) }

    private val gyeongju = LatLon(35.86, 129.22)
    private val seoul = LatLon(37.57, 126.98)
    private val busan = LatLon(35.18, 129.07)
    private val beijing = LatLon(39.90, 116.40)
    private val madrid = LatLon(40.42, -3.70)

    @Test fun `Gyeongju to Seoul follows the roads, a little longer than the straight line`() {
        val drive = roads.drive(gyeongju, seoul)!!
        val straight = GeoMath.distanceKm(gyeongju, seoul)
        assertTrue("km ${drive.km}", drive.km in straight * 1.1..straight * 1.5)
        assertTrue("hours ${drive.hours}", drive.hours in 3.0..6.0)
        assertTrue("bends ${drive.path.size}", drive.path.size > 5)
        assertEquals(gyeongju, drive.path.first())
        assertEquals(seoul, drive.path.last())
    }

    @Test fun `Seoul to Busan is about the length of the Gyeongbu expressway`() {
        val km = roads.drive(seoul, busan)!!.km
        assertTrue("km $km", km in 380.0..480.0) // the real expressway is about 416 km
    }

    @Test fun `the roads join across Eurasia`() {
        val drive = roads.drive(beijing, madrid)!!
        assertTrue("km ${drive.km}", drive.km in 10_000.0..15_000.0)
        // No jump: consecutive points never more than a few hundred km apart.
        val longest = drive.path.zipWithNext { a, b -> GeoMath.distanceKm(a, b) }.max()
        assertTrue("longest step $longest", longest < 400)
    }

    @Test fun `far from any road there is no drive`() {
        assertNull(roads.drive(LatLon(0.0, -30.0), seoul)) // mid-Atlantic
        assertNull(roads.drive(seoul, seoul))
    }

    @Test fun `a route keeps its ferry and drives the land on roads`() {
        val route = RouteFinder(world, sea, roads).find(gyeongju, "KR", "JP")!!
        assertEquals(1, route.ferries)
        val land = route.legs.filter { it.kind == LegKind.LAND }
        assertTrue(land.isNotEmpty())
        assertTrue("land legs follow roads", land.any { it.path.size > 2 })
        // Legs still join end to end.
        route.legs.zipWithNext { a, b -> assertEquals(a.to, b.from) }
    }

    @Test fun `a drive inside one country becomes one road leg`() {
        val route = RouteFinder(world, sea, roads).find(gyeongju, "KR", "KR")!!
        val leg = route.legs.single()
        assertTrue(leg.path.size > 5)
        assertEquals(leg.km, route.km, 1e-9)
        assertTrue("road km ${route.km} vs straight ${route.directKm}", route.km > route.directKm)
    }
}
