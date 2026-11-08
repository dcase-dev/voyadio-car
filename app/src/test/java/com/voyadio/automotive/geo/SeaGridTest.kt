package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SeaGridTest {
    private val world by lazy { WorldAtlas.parse(File("src/main/assets/geo/world.json").readText()) }
    private val sea by lazy { SeaGrid.parse(File("src/main/assets/geo/sea.json").readText()) }
    private fun port(code: String) = world.countries.getValue(code).port!!
    private fun lane(from: String, to: String) = sea.path(from, port(from), to, port(to))!!
    private fun near(path: List<LatLon>, at: LatLon, km: Double) = path.zipWithNext().any { (a, b) ->
        (0..20).any { GeoMath.distanceKm(GeoMath.interpolate(a, b, it / 20.0), at) < km }
    }

    /**
     * Every lane segment except the two port stubs stays at sea: checked every ~3 km, a run of land samples may only
     * be a grid-corner graze (under 10 km), never a crossing through a land cell (a cell is about 55 km).
     */
    private fun assertAtSea(path: List<LatLon>) {
        for ((a, b) in path.drop(1).dropLast(1).zipWithNext()) {
            val n = maxOf(2, (GeoMath.distanceKm(a, b) / 3.0).toInt())
            var run = 0
            for (k in 0..n) {
                run = if (sea.isSea(GeoMath.interpolate(a, b, k.toDouble() / n))) 0 else run + 1
                assertTrue("land between $a and $b", run * 3.0 < 10.0)
            }
        }
    }

    @Test fun `Incheon to Tokyo sails around Korea and Kyushu, not across them`() {
        val path = lane("KR", "JP")
        assertTrue("turning points ${path.size}", path.size > 3)
        assertAtSea(path)
        assertOffTheCoast(path)
        // Rounds Korea's south-west tip; Kanmon (under 1 km wide) is too narrow for the 0.5° grid, so south of Kyushu.
        assertTrue("rounds the south-west tip", near(path, LatLon(34.3, 126.3), 100.0))
        assertTrue("km ${sea.km("KR", "JP")}", sea.km("KR", "JP")!! in 1_800.0..2_400.0)
    }

    @Test fun `canals are open - Europe to India by Suez, Spain to Peru by Panama`() {
        assertTrue(near(lane("NL", "IN"), LatLon(30.0, 32.55), 150.0))
        assertTrue("NL-IN ${sea.km("NL", "IN")}", sea.km("NL", "IN")!! < 14_000.0)
        assertTrue(near(lane("ES", "PE"), LatLon(9.0, -79.6), 150.0))
    }

    @Test fun `grid length plus port stubs matches the bundled table`() {
        for ((a, b) in listOf("KR" to "JP", "NL" to "IN", "GB" to "US", "AU" to "NZ")) {
            val cells = sea.cells(sea.dockCell(a)!!, sea.dockCell(b)!!)!!
            val stubs = GeoMath.distanceKm(port(a), sea.center(cells.first())) + GeoMath.distanceKm(port(b), sea.center(cells.last()))
            assertEquals("$a-$b", sea.km(a, b)!!, sea.lengthKm(cells) + stubs, sea.km(a, b)!! * 0.01)
        }
    }

    @Test fun `ports without a dock have no lane`() {
        assertNull(sea.km("PY", "AR"))
        assertFalse(sea.hasPort("PY"))
        assertNull(sea.path("PY", LatLon(-25.3, -57.7), "AR", port("AR")))
    }

    @Test fun `a toy grid finds no lane between two separate seas`() {
        // 2 rows × 4 cols of 90°: two sea cells split by land.
        val toy = SeaGrid(90.0, 4, 2, booleanArrayOf(true, false, true, false, false, false, false, false),
            mapOf("A" to SeaGrid.Dock(0, 0, 0.0), "B" to SeaGrid.Dock(1, 2, 0.0)), intArrayOf(0, -1, -1, 0))
        assertNull(toy.km("A", "B"))
        assertNull(toy.cells(0, 2))
    }

    private fun onLand(p: LatLon) = world.outlines.values.any { rings -> rings.any { CountryLocator.contains(it, p) } }

    /**
     * Off the port approaches (80 km — harbours sit in bays whose channels the coarse outline closes, e.g. Uraga for
     * Tokyo, opened on purpose), no stretch of the lane longer than [km] runs over a country's real outline.
     */
    private fun assertOffTheCoast(path: List<LatLon>, km: Double = 25.0) {
        for ((a, b) in path.zipWithNext()) {
            val n = maxOf(2, (GeoMath.distanceKm(a, b) / 2.0).toInt())
            var run = 0
            for (k in 0..n) {
                val p = GeoMath.interpolate(a, b, k.toDouble() / n)
                val approach = GeoMath.distanceKm(p, path.first()) < 80.0 || GeoMath.distanceKm(p, path.last()) < 80.0
                run = if (!approach && onLand(p)) run + 1 else 0
                assertTrue("over land near $p", run * 2.0 < km)
            }
        }
    }

    @Test fun `lanes stay off real islands and isthmuses (review 2026-10-13 - New Caledonia, Auckland, Fiji)`() {
        for ((a, b) in listOf("NC" to "FJ", "NZ" to "TO", "KR" to "JP", "FJ" to "VU")) assertOffTheCoast(lane(a, b))
    }

    @Test fun `a Pacific crossing over the date line stays at sea`() {
        val path = lane("NZ", "CL")
        assertTrue("crosses 180", path.zipWithNext().any { (a, b) -> a.lon * b.lon < 0 && kotlin.math.abs(a.lon) > 90 })
        assertAtSea(path)
        assertOffTheCoast(path)
    }

    @Test fun `a lane is planned once per port pair`() {
        assertSame(lane("KR", "JP").subList(1, 3)[0], lane("KR", "JP")[1])
    }
}
