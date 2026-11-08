package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

class GlobeGeometryTest {
    private val world by lazy { WorldAtlas.parse(File("src/main/assets/geo/world.json").readText()) }

    private val centers = buildList {
        for (lat in listOf(-30.0, 0.0, 20.0, 35.0)) for (lon in -180 until 180 step 30) add(LatLon(lat, lon.toDouble()))
    }

    @Test fun `fast projection matches GeoMath project`() {
        val samples = buildList {
            for (lat in -90..90 step 15) for (lon in -180..180 step 20) add(LatLon(lat.toDouble(), lon.toDouble()))
            add(LatLon(89.999, 179.999)); add(LatLon(-89.999, -179.999))
            add(LatLon(10.0, 179.9)); add(LatLon(10.0, -179.9)); add(LatLon(37.5, 127.0))
        }
        val outline = GlobeGeometry.outline(DoubleArray(samples.size * 2) { if (it % 2 == 0) samples[it / 2].lat else samples[it / 2].lon })
        val views = centers + listOf(LatLon(90.0, 0.0), LatLon(-90.0, 45.0), LatLon(0.0, 180.0), LatLon(12.0, -179.5))
        for (center in views) {
            val frame = GlobeFrame(center)
            samples.forEachIndexed { i, p ->
                val expected = GeoMath.project(p, center)
                val visible = frame.project(outline, i)
                assertEquals("x $p from $center", expected.x, frame.x, 1e-9)
                assertEquals("y $p from $center", expected.y, frame.y, 1e-9)
                if (abs(frame.depth) > 1e-9) assertEquals("visible $p from $center", expected.visible, visible)
            }
        }
    }

    @Test fun `hidden points are pushed onto the rim`() {
        val outline = GlobeGeometry.outline(doubleArrayOf(10.0, 200.0 - 360.0, 0.0, 0.0))
        val frame = GlobeFrame(LatLon(0.0, 0.0))
        assertFalse(frame.project(outline, 0))
        frame.pushToRim()
        assertEquals(1.0, hypot(frame.x, frame.y), 1e-9)
        assertTrue(frame.x < 0)
        assertTrue(frame.project(outline, 1))
        frame.pushToRim() // visible points stay where they are
        assertEquals(0.0, frame.x, 1e-12)
        assertEquals(0.0, frame.y, 1e-12)
    }

    @Test fun `culling never hides a ring with a visible point and does hide some`() {
        val geometry = GlobeGeometry.of(world.outlines)
        assertEquals(world.outlines.values.sumOf { it.size }, geometry.outlines.size)
        for (center in centers) {
            val frame = GlobeFrame(center)
            var hidden = 0
            for (outline in geometry.outlines) {
                val anyVisible = (0 until outline.size).any { frame.project(outline, it) }
                if (frame.isHidden(outline)) {
                    hidden++
                    assertFalse("${outline.code} culled from $center but has a visible point", anyVisible)
                }
            }
            assertTrue("nothing culled from $center", hidden > 0)
        }
    }

    @Test fun `cap of a small square is centered with a small radius`() {
        val square = GlobeGeometry.outline(doubleArrayOf(-1.0, -1.0, -1.0, 1.0, 1.0, 1.0, 1.0, -1.0, -1.0, -1.0))
        assertEquals(1.0, square.capX, 1e-6)
        assertEquals(0.0, square.capY, 1e-9)
        assertEquals(0.0, square.capZ, 1e-9)
        assertEquals(Math.toRadians(1.4142), square.capRadius, Math.toRadians(0.01))
        val far = GlobeGeometry.outline(doubleArrayOf(-1.0, 179.0, -1.0, -179.0, 1.0, -179.0, 1.0, 179.0, -1.0, 179.0))
        assertEquals(-1.0, far.capX, 1e-6)
        assertTrue(far.capRadius < Math.toRadians(1.5))
        assertTrue(GlobeFrame(LatLon(0.0, 0.0)).isHidden(far))
        assertFalse(GlobeFrame(LatLon(0.0, 0.0)).isHidden(square))
        assertFalse(GlobeFrame(LatLon(0.0, 90.0)).isHidden(far)) // touches the limb within its radius
    }

    @Test fun `larger outlines come first so enclaves draw on top`() {
        val geometry = GlobeGeometry.of(world.outlines)
        val za = geometry.outlines.indexOfFirst { it.code == "ZA" }
        val ls = geometry.outlines.indexOfFirst { it.code == "LS" }
        assertTrue(za in 0 until ls)
    }

    @Test fun `zoomed culling never hides a ring that reaches into the view`() {
        val world = WorldAtlas.parse(java.io.File("src/main/assets/geo/world.json").readText())
        val seoul = LatLon(37.5665, 126.978)
        for (center in listOf(seoul, LatLon(48.85, 2.35), LatLon(0.0, 0.0))) {
            val frame = GlobeFrame(center)
            for (radius in listOf(0.02, 0.2)) {
                var hidden = 0
                for ((code, rings) in world.outlines) for (ring in rings) {
                    if (!frame.isOutsideView(GlobeGeometry.outline(ring, code), radius)) continue
                    hidden++
                    for (i in 0 until ring.size / 2) {
                        val km = GeoMath.distanceKm(center, LatLon(ring[i * 2], ring[i * 2 + 1]))
                        assertTrue("$code within view", km / 6371.0 > radius - 1e-6)
                    }
                }
                assertTrue("something culled at $center r=$radius", hidden > 0)
            }
        }
        val korea = world.outlines.getValue("KR").map { GlobeGeometry.outline(it, "KR") }
        assertTrue(korea.any { !GlobeFrame(seoul).isOutsideView(it, 0.02) })
    }
}
