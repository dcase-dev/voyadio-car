package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RouteFinderTest {
    private fun country(code: String, lat: Double, lon: Double, port: LatLon?, vararg neighbours: String) =
        code to Country(code, code, code, LatLon(lat, lon), port, neighbours.toSet())

    // A and B are close by sea, but connected by land only through a far-away C.
    private val toy = WorldAtlas(mapOf(
        country("A", 0.0, 0.0, LatLon(0.0, 0.5), "C"),
        country("B", 0.0, 4.0, LatLon(0.0, 3.5), "C"),
        country("C", 30.0, 2.0, null, "A", "B"),
        country("I", -10.0, 2.0, LatLon(-10.0, 2.0)),
    ), emptyMap())

    @Test fun `land wins even when the ferry would be faster`() {
        val route = RouteFinder(toy).find(LatLon(0.0, 0.0), "A", "B")!!
        assertEquals(0, route.ferries)
        assertEquals(listOf(LegKind.LAND, LegKind.LAND), route.legs.drop(1).map { it.kind })
        assertEquals(LatLon(30.0, 2.0), route.legs[1].to)
    }

    @Test fun `islands are reached with exactly one ferry`() {
        val route = RouteFinder(toy).find(LatLon(0.0, 0.0), "A", "I")!!
        assertEquals(1, route.ferries)
        val ferry = route.legs.single { it.kind == LegKind.FERRY }
        assertEquals(LatLon(0.0, 0.5), ferry.from)
        assertEquals(ferry.km / RouteFinder.SEA_KMH + RouteFinder.BOARDING_HOURS, ferry.hours, 1e-9)
        // Ferry time is added as is; only the land legs follow the driving schedule.
        val driving = route.legs.filter { it.kind == LegKind.LAND }.sumOf { it.hours }
        assertEquals(DriveSchedule.elapsedHours(driving) + ferry.hours, route.hours, 1e-9)
    }

    @Test fun `same country is a single land leg to the capital`() {
        val route = RouteFinder(toy).find(LatLon(1.0, 1.0), "A", "A")!!
        assertEquals(1, route.legs.size)
        assertEquals(route.legs[0].km / RouteFinder.LAND_KMH, route.legs[0].hours, 1e-9)
        assertEquals(DriveSchedule.elapsedHours(route.legs[0].hours), route.hours, 1e-9)
        assertEquals(GeoMath.distanceKm(LatLon(1.0, 1.0), LatLon(0.0, 0.0)) * RouteFinder.LAND_DETOUR, route.legs[0].km, 1e-9)
    }

    @Test fun `unknown countries have no route`() {
        assertNull(RouteFinder(toy).find(LatLon(0.0, 0.0), "A", "ZZ"))
    }

    private val world by lazy { WorldAtlas.parse(File("src/main/assets/geo/world.json").readText()) }
    private val seoul = LatLon(37.5665, 126.978)

    @Test fun `real map - Rome to Cairo goes around the Mediterranean by land`() {
        assertEquals(0, RouteFinder(world).find(LatLon(41.9, 12.5), "IT", "EG")!!.ferries)
    }

    @Test fun `real map - Seoul to Tokyo needs one ferry`() {
        assertEquals(1, RouteFinder(world).find(seoul, "KR", "JP")!!.ferries)
    }

    @Test fun `real map - Seoul to Amsterdam is all land and about 8,500 km away`() {
        val route = RouteFinder(world).find(seoul, "KR", "NL")!!
        assertEquals(0, route.ferries)
        assertTrue("direct ${route.directKm}", route.directKm in 8_450.0..8_700.0)
        // ~160 hours at the wheel becomes about two weeks with breaks and overnight stops.
        assertTrue("hours ${route.hours}", route.hours in 250.0..400.0)
    }

    @Test fun `real map - every country is reachable from Korea`() {
        val finder = RouteFinder(world)
        val missing = world.countries.keys.filter { finder.find(seoul, "KR", it) == null }
        assertEquals(emptyList<String>(), missing)
    }

    // ── 뱃길(2026-10-13): 카페리는 육지를 둘러 가고, 시간도 실제 뱃길 km로 ──
    private val sea by lazy { SeaGrid.parse(File("src/main/assets/geo/sea.json").readText()) }

    @Test fun `real map with sea lanes - Seoul to Tokyo ferry sails the lane and is timed by its km`() {
        val route = RouteFinder(world, sea).find(seoul, "KR", "JP")!!
        assertEquals(1, route.ferries)
        val ferry = route.legs.single { it.kind == LegKind.FERRY }
        assertTrue("turning points ${ferry.path.size}", ferry.path.size > 3)
        assertEquals(ferry.from, ferry.path.first()); assertEquals(ferry.to, ferry.path.last())
        val from = world.countries.values.single { it.port == ferry.from }.code
        val to = world.countries.values.single { it.port == ferry.to }.code
        assertEquals(sea.km(from, to)!!, ferry.km, 1e-9)
        assertEquals(ferry.km / RouteFinder.SEA_KMH + RouteFinder.BOARDING_HOURS, ferry.hours, 1e-9)
        // Off the port stubs the lane stays at sea; a few km on land is only a 0.5° grid-corner graze (see SeaGridTest).
        for ((a, b) in ferry.path.drop(1).dropLast(1).zipWithNext()) {
            val n = maxOf(2, (GeoMath.distanceKm(a, b) / 3.0).toInt())
            assertTrue("land between $a and $b", (0..n).count { !sea.isSea(GeoMath.interpolate(a, b, it.toDouble() / n)) } * 3.0 < 10.0)
        }
    }

    @Test fun `real map with sea lanes - every country is still reachable from Korea`() {
        val finder = RouteFinder(world, sea)
        val missing = world.countries.keys.filter { finder.find(seoul, "KR", it) == null }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `real map with sea lanes - land routes are untouched`() {
        val plain = RouteFinder(world).find(seoul, "KR", "NL")!!
        assertEquals(plain, RouteFinder(world, sea).find(seoul, "KR", "NL"))
    }
}
