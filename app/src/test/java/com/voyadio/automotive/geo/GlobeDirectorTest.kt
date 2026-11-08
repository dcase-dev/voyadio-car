package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlobeDirectorTest {
    private val seoul = LatLon(37.57, 126.98)
    private val moscow = LatLon(55.75, 37.62)
    private val amsterdam = LatLon(52.37, 4.90)
    private val tokyo = LatLon(35.68, 139.69)
    private val westward = listOf(seoul, moscow, amsterdam)
    private fun close(a: LatLon, b: LatLon, km: Double = 30.0) = GeoMath.distanceKm(a, b) < km

    @Test fun `loop starts at the start, passes the destination and comes back`() {
        val loop = GlobeDirector.Loop.of(westward)!!
        assertTrue(close(loop.at(0.0), seoul))
        assertTrue(close(loop.at(loop.routeShare), amsterdam))
        assertTrue(close(loop.at(1.0), seoul))
    }

    @Test fun `after the destination it keeps turning the same way (west)`() {
        val loop = GlobeDirector.Loop.of(westward)!!
        assertEquals(-1.0, loop.direction, 0.0)
        var prev = loop.at(loop.routeShare).lon
        var step = loop.routeShare
        while (step < 0.999) {
            step += 0.01
            val lon = loop.at(step).lon
            assertTrue("lon must not go east: $prev -> $lon", GeoMath.angleDiff(lon, prev) <= 1e-6)
            prev = lon
        }
    }

    @Test fun `an eastward route keeps turning east`() {
        assertEquals(1.0, GlobeDirector.Loop.of(listOf(seoul, tokyo))!!.direction, 0.0)
    }

    @Test fun `constant speed - equal time covers equal arc`() {
        val loop = GlobeDirector.Loop.of(westward)!!
        val a = GeoMath.distanceKm(loop.at(0.10), loop.at(0.15))
        val b = GeoMath.distanceKm(loop.at(0.70), loop.at(0.75))
        assertEquals(a, b, a * 0.08)
    }

    @Test fun `a too short route spins in place`() {
        assertNull(GlobeDirector.Loop.of(listOf(seoul, LatLon(37.6, 127.1))))
        val d = GlobeDirector()
        d.retarget(listOf(seoul, LatLon(37.6, 127.1)), seoul)
        val before = d.center
        d.advance(30_000, playing = true) // idle spin: one lap per 3 minutes, westward
        assertEquals(before.lat, d.center.lat, 1e-6)
        assertEquals(-60.0, GeoMath.angleDiff(d.center.lon, before.lon), 0.5)
    }

    @Test fun `playing laps in 60 s, paused takes three times as long`() {
        val d = GlobeDirector()
        d.retarget(westward, seoul)
        assertTrue(close(d.center, seoul))
        d.advance(30_000, playing = true)
        val half = d.center
        d.advance(30_000, playing = true)
        assertTrue(close(d.center, seoul))
        assertFalse(close(half, seoul, 500.0))
        val p = GlobeDirector()
        p.retarget(westward, seoul)
        p.advance(30_000, playing = false)
        val loop = GlobeDirector.Loop.of(westward)!!
        assertTrue(close(p.center, loop.at(30_000.0 / GlobeDirector.PAUSED_LAP_MS)))
    }

    @Test fun `a new route glides there in two seconds without advancing the loop`() {
        val d = GlobeDirector()
        d.retarget(westward, seoul)
        d.advance(20_000, playing = true)
        val from = d.center
        d.retarget(listOf(tokyo, LatLon(37.77, -122.42)), tokyo)
        d.advance(1_000, playing = true)
        val mid = d.center
        assertFalse(close(mid, from)); assertFalse(close(mid, tokyo))
        d.advance(1_000, playing = true)
        assertTrue(close(d.center, tokyo))
    }

    @Test fun `big time steps are safe and the loop wraps`() {
        val d = GlobeDirector()
        d.retarget(westward, seoul)
        d.advance(10 * GlobeDirector.LAP_MS, playing = true)
        assertTrue(close(d.center, seoul))
        assertTrue(abs(d.center.lat) <= 90.0)
    }

    private fun aim(from: LatLon?, to: LatLon?, route: Boolean = true, channel: String? = "kbs") = GlobeDirector.Aim(from, to, route, channel)

    @Test fun `same channel and destination with a 50 km move keeps the lap`() {
        val nearby = LatLon(37.57, 127.55) // about 50 km east of Seoul
        assertTrue(GeoMath.distanceKm(seoul, nearby) in 40.0..60.0)
        assertFalse(GlobeDirector.needsRetarget(aim(seoul, amsterdam), aim(nearby, amsterdam)))
        assertFalse(GlobeDirector.needsRetarget(aim(null, null, false, null), aim(null, null, false, null)))
    }

    @Test fun `a 150 km move, a new destination, a route change or the first aim glides again`() {
        val farther = LatLon(37.57, 128.68) // about 150 km east of Seoul
        assertTrue(GeoMath.distanceKm(seoul, farther) in 140.0..160.0)
        assertTrue(GlobeDirector.needsRetarget(aim(seoul, amsterdam), aim(farther, amsterdam)))
        assertTrue(GlobeDirector.needsRetarget(aim(seoul, amsterdam), aim(seoul, moscow)))
        assertTrue(GlobeDirector.needsRetarget(aim(seoul, amsterdam, false), aim(seoul, amsterdam, true)))
        assertTrue(GlobeDirector.needsRetarget(aim(null, amsterdam), aim(seoul, amsterdam)))
        assertTrue(GlobeDirector.needsRetarget(null, aim(null, null, false, null)))
    }

    @Test fun `a new channel glides even to the same country from the same place`() {
        assertTrue(GlobeDirector.needsRetarget(aim(seoul, amsterdam, channel = "a"), aim(seoul, amsterdam, channel = "b")))
        assertTrue(GlobeDirector.needsRetarget(aim(seoul, null, false, "a"), aim(seoul, null, false, null)))
    }

    @Test fun `the first aim starts right there, later aims glide`() {
        val d = GlobeDirector()
        d.retarget(westward, seoul)
        assertTrue(close(d.advance(0, playing = true), seoul, 1.0))
        val s = GlobeDirector()
        s.retarget(null, tokyo)
        assertTrue(close(s.advance(0, playing = true), tokyo, 1.0))
        s.retarget(westward, seoul)
        assertTrue(close(s.advance(0, playing = true), tokyo, 1.0)) // second aim: the glide has not moved yet
    }

    @Test fun `replace glides when the lap would turn the other way`() {
        val northEast = listOf(LatLon(10.0, 100.0), LatLon(50.0, 100.5)) // nets 0.5 deg east
        val northWest = listOf(LatLon(10.0, 100.9), LatLon(50.0, 100.5)) // nets 0.4 deg west
        assertEquals(1.0, GlobeDirector.Loop.of(northEast)!!.direction, 0.0)
        assertEquals(-1.0, GlobeDirector.Loop.of(northWest)!!.direction, 0.0)
        val d = GlobeDirector()
        d.retarget(northEast, northEast[0])
        d.advance(20_000, playing = true)
        val before = d.center
        d.replace(northWest, northWest[0])
        assertTrue(close(d.advance(0, playing = true), before, 1.0)) // a glide starts where the camera is
        d.advance(GlobeDirector.TRANSITION_MS, playing = true)
        assertTrue(close(d.center, northWest[0], 1.0)) // and ends at the new start, lap restarted
    }

    @Test fun `a loop across the date line stays continuous and the return latitude moves one way`() {
        val anchorage = LatLon(61.22, -149.90)
        val loop = GlobeDirector.Loop.of(GeoMath.arc(tokyo, anchorage, 20))!!
        assertEquals(1.0, loop.direction, 0.0)
        val steps = 1_000
        val total = (0 until steps).sumOf { GeoMath.distanceKm(loop.at(it.toDouble() / steps), loop.at((it + 1.0) / steps)) }
        var prev = loop.at(0.0)
        for (k in 1..steps) {
            val p = loop.at(k.toDouble() / steps)
            assertTrue("jump at $k: $prev -> $p", GeoMath.distanceKm(prev, p) < total / steps * 3)
            prev = p
        }
        assertTrue(close(loop.at(loop.routeShare), anchorage))
        var lat = loop.at(loop.routeShare).lat
        var f = loop.routeShare
        while (f + 0.005 <= 1.0) { // stop at 1.0: beyond it the lap wraps onto the route again
            f += 0.005
            val next = loop.at(f)
            assertTrue("return leg must head back south: $lat -> ${next.lat}", next.lat <= lat + 1e-6)
            assertTrue("return leg must keep turning east", GeoMath.angleDiff(next.lon, loop.at(f - 0.005).lon) >= -1e-6)
            lat = next.lat
        }
    }

    @Test fun `replace keeps the lap going without a glide`() {
        val d = GlobeDirector()
        d.retarget(westward, seoul)
        d.advance(20_000, playing = true)
        val before = d.center
        val nearby = LatLon(37.57, 127.55)
        d.replace(listOf(nearby, moscow, amsterdam), nearby)
        d.advance(16, playing = true) // a glide would barely have started from [before]; the lap is far from the start
        assertTrue(close(d.center, before, 150.0))
        assertFalse(close(d.center, nearby, 1_000.0))
        val expected = GlobeDirector.Loop.of(listOf(nearby, moscow, amsterdam))!!.at((20_000.0 + 16) / GlobeDirector.LAP_MS)
        assertTrue(close(d.center, expected, 1.0))
    }

    // ── 경로 위 탈것(2026-10-13): 육로는 자동차, 카페리 구간은 카페리, 도착 후엔 목적지에 세워 둔다 ──
    private val busan = LatLon(35.10, 129.04)
    private val fukuoka = LatLon(33.60, 130.40)
    // 서울→부산(육로), 부산→후쿠오카(카페리), 후쿠오카→도쿄(육로): 구간마다 표시 하나.
    private val eastward = listOf(seoul, busan, fukuoka, tokyo)
    private val kinds = listOf(false, true, false)

    @Test fun `vehicle is a car on land legs and a ferry on the sea leg, at the camera point`() {
        val loop = GlobeDirector.Loop.of(eastward, kinds)!!
        val total = (0 until 3).sumOf { GeoMath.distanceKm(eastward[it], eastward[it + 1]) }
        fun at(km: Double) = loop.vehicle(km / total * loop.routeShare)
        val onLand = at(GeoMath.distanceKm(seoul, busan) / 2)
        assertFalse(onLand.ferry); assertFalse(onLand.parked)
        assertTrue(close(onLand.at, loop.at(GeoMath.distanceKm(seoul, busan) / 2 / total * loop.routeShare), 1.0))
        val atSea = at(GeoMath.distanceKm(seoul, busan) + GeoMath.distanceKm(busan, fukuoka) / 2)
        assertTrue(atSea.ferry)
        assertTrue("faces east", atSea.east)
        assertFalse(at(total - 50).ferry)
    }

    @Test fun `after the destination the car waits there facing the way it came`() {
        val loop = GlobeDirector.Loop.of(westward, listOf(false, false))!!
        val parked = loop.vehicle(loop.routeShare + (1 - loop.routeShare) / 2)
        assertTrue(parked.parked); assertFalse(parked.ferry)
        assertTrue(close(parked.at, amsterdam, 1.0))
        assertFalse("faces west", parked.east)
        assertFalse(loop.vehicle(0.01).parked)
    }

    @Test fun `no kinds means all land, and the director shows no vehicle while gliding or spinning`() {
        assertFalse(GlobeDirector.Loop.of(westward)!!.vehicle(0.1).ferry)
        val director = GlobeDirector()
        director.retarget(null, seoul)
        director.advance(1_000, playing = true)
        assertNull(director.vehicle())
        director.retarget(eastward, null, kinds)
        assertNull("gliding", director.vehicle())
        director.advance(GlobeDirector.TRANSITION_MS, playing = true)
        director.advance(100, playing = true)
        assertNotNull(director.vehicle())
    }

    @Test fun `joined legs repeat their joint points, and the ferry flag still lands on the sea leg`() {
        val (points, flags) = GlobeDirector.join(listOf(false to listOf(seoul, busan), true to listOf(busan, fukuoka), false to listOf(fukuoka, tokyo)))
        assertEquals(listOf(seoul, busan, busan, fukuoka, fukuoka, tokyo), points)
        assertEquals(listOf(false, true, true, false, false), flags)
        val loop = GlobeDirector.Loop.of(points, flags)!!
        val total = GeoMath.distanceKm(seoul, busan) + GeoMath.distanceKm(busan, fukuoka) + GeoMath.distanceKm(fukuoka, tokyo)
        fun ferryAt(km: Double) = loop.vehicle(km / total * loop.routeShare).ferry
        assertFalse(ferryAt(GeoMath.distanceKm(seoul, busan) - 5))
        assertTrue(ferryAt(GeoMath.distanceKm(seoul, busan) + 5))
        assertFalse(ferryAt(GeoMath.distanceKm(seoul, busan) + GeoMath.distanceKm(busan, fukuoka) + 5))
    }

    private val gyeongju = LatLon(35.86, 129.22)

    @Test fun `a held camera stays put while the car drives a short route, then parks`() {
        val path = GeoMath.arc(gyeongju, seoul, 4)
        val focus = GeoMath.interpolate(gyeongju, seoul, 0.5)
        val d = GlobeDirector()
        d.retarget(path, gyeongju, hold = focus)
        assertTrue(close(d.advance(0, true), focus, 0.1))
        assertTrue(close(d.vehicle()!!.at, gyeongju, 1.0))
        // Half of the drive share: halfway along the route, camera still at the middle.
        assertTrue(close(d.advance((GlobeDirector.LAP_MS * GlobeDirector.DRIVE_SHARE / 2).toLong(), true), focus, 0.1))
        val half = d.vehicle()!!
        assertFalse(half.parked)
        assertTrue("halfway ${half.at}", close(half.at, focus, 20.0))
        d.advance((GlobeDirector.LAP_MS * 0.45).toLong(), true)
        val end = d.vehicle()!!
        assertTrue(end.parked)
        assertTrue(close(end.at, seoul, 1.0))
    }

    @Test fun `a route shorter than the lap minimum still drives when held`() {
        val near = LatLon(37.0, 127.5) // about 80 km from Seoul
        assertNull(GlobeDirector.Loop.of(listOf(near, seoul)))
        assertNotNull(GlobeDirector.Loop.route(listOf(near, seoul)))
    }

    @Test fun `holding with no path shows no vehicle, and leaving the hold glides back to the lap`() {
        val d = GlobeDirector()
        d.retarget(null, seoul, hold = seoul)
        d.advance(5_000, true)
        assertNull(d.vehicle())
        assertTrue(close(d.center, seoul, 0.1))
        d.replace(westward, seoul) // the hold is gone: a fresh glide to the lap
        d.advance(GlobeDirector.TRANSITION_MS, true)
        assertTrue(close(d.center, seoul))
        assertNotNull(d.vehicle())
    }
}
