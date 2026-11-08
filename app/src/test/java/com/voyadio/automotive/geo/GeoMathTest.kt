package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test

class GeoMathTest {
    private val seoul = LatLon(37.5665, 126.978)
    private val amsterdam = LatLon(52.3676, 4.9041)

    @Test fun `great circle distance matches known city pair`() {
        val km = GeoMath.distanceKm(seoul, amsterdam)
        assertTrue("got $km", km in 8_450.0..8_700.0)
        assertEquals(0.0, GeoMath.distanceKm(seoul, seoul), 1e-6)
    }

    @Test fun `arc keeps endpoints and halves the distance at the midpoint`() {
        val arc = GeoMath.arc(seoul, amsterdam, 10)
        assertEquals(11, arc.size)
        assertEquals(seoul.lat, arc.first().lat, 1e-6); assertEquals(seoul.lon, arc.first().lon, 1e-6)
        assertEquals(amsterdam.lat, arc.last().lat, 1e-6); assertEquals(amsterdam.lon, arc.last().lon, 1e-6)
        val total = GeoMath.distanceKm(seoul, amsterdam)
        assertEquals(total / 2, GeoMath.distanceKm(seoul, arc[5]), 1.0)
    }

    @Test fun `projection puts the center in the middle and hides the far side`() {
        val center = LatLon(20.0, 127.0)
        val mid = GeoMath.project(center, center)
        assertTrue(mid.visible); assertEquals(0.0, mid.x, 1e-9); assertEquals(0.0, mid.y, 1e-9)
        assertFalse(GeoMath.project(LatLon(-20.0, -53.0), center).visible)
        val east = GeoMath.project(LatLon(20.0, 157.0), center)
        assertTrue(east.visible); assertTrue(east.x > 0)
        val north = GeoMath.project(LatLon(50.0, 127.0), center)
        assertTrue(north.y > 0)
    }

    @Test fun `longitudes wrap into the -180 to 180 range`() {
        assertEquals(-170.0, GeoMath.normalizeLon(190.0), 1e-9)
        assertEquals(180.0, GeoMath.normalizeLon(-180.0), 1e-9)
        assertEquals(-20.0, GeoMath.angleDiff(170.0, -170.0), 1e-9)
    }

    @Test fun `circle points all sit the radius away and close the ring`() {
        val center = LatLon(37.5, 127.0)
        val ring = GeoMath.circle(center, 80.0, 72)
        assertEquals(73, ring.size)
        assertEquals(ring.first().lat, ring.last().lat, 1e-9); assertEquals(ring.first().lon, ring.last().lon, 1e-9)
        for (p in ring) assertEquals(80.0, GeoMath.distanceKm(center, p), 0.01)
        assertTrue(ring.any { it.lat > center.lat + 0.7 } && ring.any { it.lat < center.lat - 0.7 })
    }
}
