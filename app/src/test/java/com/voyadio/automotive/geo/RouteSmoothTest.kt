package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class RouteSmoothTest {
    /** Heading change (degrees) at each inner point, on a flat lat/lon view — fine for these small test paths. */
    private fun turns(path: List<LatLon>): List<Double> = (1 until path.lastIndex).map { i ->
        val h1 = Math.toDegrees(atan2(path[i].lat - path[i - 1].lat, path[i].lon - path[i - 1].lon))
        val h2 = Math.toDegrees(atan2(path[i + 1].lat - path[i].lat, path[i + 1].lon - path[i].lon))
        abs(((h2 - h1 + 540) % 360) - 180)
    }

    @Test fun `the two ends never move`() {
        val path = listOf(LatLon(0.0, 0.0), LatLon(1.0, 1.0), LatLon(0.0, 2.0), LatLon(1.0, 3.0))
        val smooth = RouteSmooth.smooth(path, 3.0)
        assertEquals(path.first(), smooth.first())
        assertEquals(path.last(), smooth.last())
    }

    @Test fun `junction wiggles narrower than the tolerance are straightened`() {
        // A 1000 km eastward road that jogs 0.05° (≈ 5.5 km) north and south at every junction.
        val path = (0..90).map { i -> LatLon(if (i % 2 == 0) 0.0 else 0.05, i * 0.1) }
        val simple = RouteSmooth.simplify(path, 10.0)
        assertEquals(listOf(path.first(), path.last()), simple)
    }

    @Test fun `a sharp corner is rounded into many gentle turns`() {
        // East 5°, then a right-angle turn north 5°.
        val corner = listOf(LatLon(0.0, 0.0), LatLon(0.0, 5.0), LatLon(5.0, 5.0))
        assertEquals(90.0, turns(corner).single(), 0.5)
        val smooth = RouteSmooth.smooth(corner, 3.0)
        assertTrue("more points", smooth.size > 6)
        assertTrue("no sharp turn left: ${turns(smooth).max()}", turns(smooth).max() < 25.0)
    }

    @Test fun `the rounded line stays near the road`() {
        val corner = listOf(LatLon(0.0, 0.0), LatLon(0.0, 5.0), LatLon(5.0, 5.0))
        val smooth = RouteSmooth.smooth(corner, 3.0)
        // Corner cutting pulls the bend in by at most a quarter of the shorter side (here 1.25° ≈ 140 km).
        val cut = smooth.minOf { GeoMath.distanceKm(it, LatLon(0.0, 5.0)) }
        assertTrue("bend pulled in by $cut km", cut in 50.0..200.0)
    }

    @Test fun `crossing the date line does not jump around the globe`() {
        val path = listOf(LatLon(60.0, 178.0), LatLon(61.0, 179.5), LatLon(60.5, -179.0), LatLon(61.5, -177.0))
        val smooth = RouteSmooth.smooth(path, 3.0)
        val longest = smooth.zipWithNext { a, b -> GeoMath.distanceKm(a, b) }.max()
        assertTrue("longest step $longest km", longest < 200.0)
    }

    @Test fun `tolerance grows with the drive but stays bounded`() {
        assertEquals(3.0, RouteSmooth.toleranceFor(50.0), 1e-9)
        assertEquals(50.0, RouteSmooth.toleranceFor(5_000.0), 1e-9)
        assertEquals(120.0, RouteSmooth.toleranceFor(15_000.0), 1e-9)
    }

    @Test fun `two points stay as they are`() {
        val line = listOf(LatLon(0.0, 0.0), LatLon(1.0, 1.0))
        assertSame(line, RouteSmooth.smooth(line, 3.0))
    }
}
