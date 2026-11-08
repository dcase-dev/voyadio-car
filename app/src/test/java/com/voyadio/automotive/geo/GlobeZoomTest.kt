package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test

class GlobeZoomTest {
    private val gyeongju = LatLon(35.86, 129.22)
    private val seoul = LatLon(37.57, 126.98)
    private val tokyo = LatLon(35.68, 139.69)
    private val madrid = LatLon(40.42, -3.70)
    private val gangnam = LatLon(37.50, 127.03)

    // Galaxy Tab S8 Ultra landscape and its usual globe radius (NightLayout: max(1.5 h, 0.62 w)).
    private val width = 2960.0
    private val height = 1848.0
    private val layout = NightLayout.of(width, height)!!
    private val radius = layout.radius
    private val tilt = layout.tiltDeg

    private fun shownWidth(focus: LatLon, points: List<LatLon>, zoom: Double): Double {
        val xs = points.map { GeoMath.project(it, focus).x * radius * zoom }
        return xs.max() - xs.min()
    }

    @Test fun `a drive inside Korea is held and fills about 60 percent of the width`() {
        val path = GeoMath.arc(gyeongju, seoul, 4)
        val focus = GlobeZoom.hold(gyeongju, seoul, path)!!
        val zoom = GlobeZoom.zoom(focus, path, width, height, radius, tilt)
        assertTrue("zoom $zoom", zoom > 5)
        val share = shownWidth(focus, path, zoom) / width
        assertTrue("share $share", share in 0.45..0.65)
    }

    @Test fun `Seoul to Tokyo is held but closer to the whole Earth`() {
        val path = GeoMath.arc(seoul, tokyo, 8)
        val focus = GlobeZoom.hold(seoul, tokyo, path)!!
        val near = GlobeZoom.zoom(focus, GeoMath.arc(gyeongju, seoul, 4), width, height, radius, tilt)
        val zoom = GlobeZoom.zoom(focus, path, width, height, radius, tilt)
        assertTrue("zoom $zoom", zoom > 1.2 && zoom < near)
    }

    @Test fun `crossing continents keeps the whole-globe lap`() {
        assertNull(GlobeZoom.hold(seoul, madrid, GeoMath.arc(seoul, madrid, 40)))
    }

    @Test fun `a road bending far from the straight line can lose the hold`() {
        val bend = LatLon(55.0, 100.0) // a detour far into Siberia
        assertNull(GlobeZoom.hold(gyeongju, seoul, listOf(gyeongju, bend, seoul)))
    }

    @Test fun `a station across town is local and stops at the closest zoom`() {
        assertTrue(GlobeZoom.isLocal(seoul, gangnam))
        assertFalse(GlobeZoom.isLocal(gyeongju, seoul))
        val focus = GlobeZoom.hold(seoul, gangnam, listOf(seoul, gangnam))!!
        val zoom = GlobeZoom.zoom(focus, listOf(seoul, gangnam), width, height, radius, tilt)
        val halfKm = kotlin.math.asin(width / 2 / (radius * zoom)) * 6371.0 // half the screen, on the ground
        assertTrue("half width $halfKm km", halfKm >= GlobeZoom.MIN_VIEW_KM * 0.95)
    }

    @Test fun `never zooms out below the usual globe`() {
        assertEquals(1.0, GlobeZoom.zoom(seoul, listOf(seoul, LatLon(0.0, 0.0)), width, height, radius, tilt), 0.0)
        assertEquals(1.0, GlobeZoom.zoom(seoul, listOf(seoul), 0.0, height, radius), 0.0)
    }
}
