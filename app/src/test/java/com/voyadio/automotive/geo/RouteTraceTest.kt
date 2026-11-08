package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test

class RouteTraceTest {
    private val a = LatLon(0.0, 0.0)
    private val b = LatLon(0.0, 1.0)
    private val c = LatLon(1.0, 1.0)
    private val route = Route(listOf(
        Leg(LegKind.LAND, a, a, 0.0, 0.0),          // 출발점 = 자국 수도인 0km 구간
        Leg(LegKind.LAND, a, b, 100.0, 1.0),
        Leg(LegKind.LAND, b, c, 100.0, 1.0),
    ), 0.0)

    private fun near(expected: LatLon, actual: LatLon?) {
        assertNotNull(actual)
        assertEquals(expected.lat, actual!!.lat, 1e-6); assertEquals(expected.lon, actual.lon, 1e-6)
    }

    @Test fun `interpolate returns the fraction point on the great circle`() {
        near(a, GeoMath.interpolate(a, b, 0.0))
        near(b, GeoMath.interpolate(a, b, 1.0))
        val half = GeoMath.interpolate(a, b, 0.5)
        assertEquals(GeoMath.distanceKm(a, b) / 2, GeoMath.distanceKm(a, half), 1e-6)
    }

    @Test fun `walks road km along legs and clamps at the end`() {
        assertEquals(listOf(a), RouteTrace.along(route, 0.0))
        near(GeoMath.interpolate(a, b, 0.5), RouteTrace.along(route, 50.0).last())
        near(GeoMath.interpolate(b, c, 0.5), RouteTrace.along(route, 150.0).last())
        near(c, RouteTrace.along(route, 1_000.0).last())
        near(b, RouteTrace.pointAt(route, 100.0))
    }

    @Test fun `steps every stepKm of road`() {
        assertEquals(11, RouteTrace.along(route, 100.0, stepKm = 10.0).size)
        assertNull(RouteTrace.pointAt(Route(emptyList(), 0.0), 10.0))
    }

    @Test fun `rejects a non-positive step`() {
        assertThrows(IllegalArgumentException::class.java) { RouteTrace.along(route, 50.0, stepKm = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { RouteTrace.along(route, 50.0, stepKm = -1.0) }
    }

    @Test fun `a ferry leg spreads its km over the sea lane it draws`() {
        val d = LatLon(0.0, 2.0)
        // a→(0,1)→(1,1)→(0,2): the lane turns north and back, 300 km of sea.
        val ferry = Route(listOf(Leg(LegKind.FERRY, a, d, 300.0, 10.0, listOf(a, b, c, d))), 0.0)
        near(c, RouteTrace.pointAt(ferry, 300.0 * (GeoMath.distanceKm(a, b) + GeoMath.distanceKm(b, c)) /
            (GeoMath.distanceKm(a, b) + GeoMath.distanceKm(b, c) + GeoMath.distanceKm(c, d))))
        near(d, RouteTrace.pointAt(ferry, 300.0))
        near(b, RouteTrace.pointOn(listOf(a, b, c, d), GeoMath.distanceKm(a, b) /
            (GeoMath.distanceKm(a, b) + GeoMath.distanceKm(b, c) + GeoMath.distanceKm(c, d))))
    }
}
