package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.PI

class NightLayoutTest {
    private val world by lazy { WorldAtlas.parse(File("src/main/assets/geo/world.json").readText()) }
    private val screens = listOf(1920.0 to 720.0, 1280.0 to 720.0, 1024.0 to 768.0, 1080.0 to 1920.0, 2560.0 to 600.0)

    @Test fun `horizon sits near the top and the camera point at 45 percent height, center width`() {
        for ((w, h) in screens) {
            val layout = NightLayout.of(w, h)!!
            assertEquals(0.11 * h, layout.cy - layout.radius, 1e-6)
            for (camera in listOf(LatLon(37.57, 126.98), LatLon(0.0, -60.0), LatLon(55.75, 37.62), LatLon(-20.0, 150.0))) {
                val p = GeoMath.project(camera, LatLon(layout.viewLat(camera.lat), camera.lon))
                assertTrue(p.visible)
                assertEquals("x on $w×$h", w / 2, layout.cx + p.x * layout.radius, 0.5)
                assertEquals("y on $w×$h", 0.45 * h, layout.centerY(camera.lat) - p.y * layout.radius, 0.5)
            }
        }
    }

    @Test fun `far south the globe moves up so the camera point stays at 45 percent`() {
        for ((w, h) in listOf(1920.0 to 720.0, 2560.0 to 600.0)) {
            val layout = NightLayout.of(w, h)!!
            for (lat in listOf(-45.0, -55.0)) {
                val camera = LatLon(lat, 20.0)
                assertTrue("the view latitude is clamped at $lat", layout.centerY(lat) < layout.cy)
                val p = GeoMath.project(camera, LatLon(layout.viewLat(lat), camera.lon))
                assertTrue(p.visible)
                assertEquals("x at $lat on $w×$h", w / 2, layout.cx + p.x * layout.radius, 1.0)
                assertEquals("y at $lat on $w×$h", 0.45 * h, layout.centerY(lat) - p.y * layout.radius, 1.0)
            }
        }
    }

    @Test fun `no layout without a size`() {
        assertNull(NightLayout.of(0.0, 720.0))
        assertNull(NightLayout.of(1920.0, 0.0))
    }

    @Test fun `screen culling never drops land that reaches the screen, and does drop some`() {
        val geometry = GlobeGeometry.of(world.outlines)
        val frame = GlobeFrame()
        for ((w, h) in screens) {
            val layout = NightLayout.of(w, h)!!
            assertTrue("cap must be smaller than the hemisphere on $w×$h", layout.capRadius < PI / 2)
            frame.screenCap(layout.capNorth, layout.capToward, layout.capRadius)
            var culled = 0
            for (lat in -60..60 step 20) for (lon in -180 until 180 step 40) {
                if (layout.centerY(lat.toDouble()) != layout.cy) continue // moved globe: the view skips this cull
                frame.aim(layout.viewLat(lat.toDouble()), lon.toDouble())
                for (o in geometry.outlines) {
                    if (frame.isHidden(o) || !frame.isOffScreen(o)) continue
                    culled++
                    for (i in 0 until o.size) {
                        if (!frame.project(o, i)) continue
                        val x = layout.cx + frame.x * layout.radius; val y = layout.cy - frame.y * layout.radius
                        assertFalse("${o.code} reaches the $w×$h screen at ($x, $y) but was culled", x in 0.0..w && y in 0.0..h)
                    }
                }
            }
            assertTrue(culled > 0)
        }
    }
}
