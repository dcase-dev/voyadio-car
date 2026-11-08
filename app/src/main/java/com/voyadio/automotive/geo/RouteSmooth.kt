package com.voyadio.automotive.geo

import kotlin.math.asin
import kotlin.math.sqrt

/**
 * A road path as the globe draws and the car drives it (PO 2026-10-21: "smooth it so the car glides").
 * Road-graph paths zigzag at every junction and turn on a sharp corner, so the car jerked from heading to heading.
 * Two passes, display only — the km and hours stay those of the real roads:
 *  1. wiggles narrower than [toleranceKm] are straightened (Douglas–Peucker on the sphere);
 *  2. each remaining corner is rounded (Chaikin corner cutting along great circles).
 * The two ends never move, so a land leg still meets its ferry leg and the pins.
 */
object RouteSmooth {
    private const val EARTH_KM = 6371.0
    private const val ROUNDS = 3

    /** Wiggle width for a road leg of [km]: 1 % of it, between 3 km (a local drive) and 120 km (across a continent). */
    fun toleranceFor(km: Double) = (km * 0.01).coerceIn(3.0, 120.0)

    fun smooth(path: List<LatLon>, toleranceKm: Double): List<LatLon> {
        if (path.size <= 2) return path
        var line = simplify(path, toleranceKm)
        repeat(ROUNDS) { line = cut(line) }
        return line
    }

    /** Douglas–Peucker: keeps only the points that stand out more than [toleranceKm] from the line between kept ones. */
    fun simplify(path: List<LatLon>, toleranceKm: Double): List<LatLon> {
        if (path.size <= 2) return path
        val keep = BooleanArray(path.size).also { it[0] = true; it[path.lastIndex] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to path.lastIndex) }
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            var far = -1; var farKm = toleranceKm
            for (i in lo + 1 until hi) {
                val d = offsetKm(path[i], path[lo], path[hi])
                if (d > farKm) { far = i; farKm = d }
            }
            if (far < 0) continue
            keep[far] = true
            stack.add(lo to far); stack.add(far to hi)
        }
        return path.filterIndexed { i, _ -> keep[i] }
    }

    /** One round of corner cutting: every inner corner becomes two points a quarter in from each side. */
    private fun cut(path: List<LatLon>): List<LatLon> {
        if (path.size <= 2) return path
        val out = ArrayList<LatLon>(path.size * 2)
        out += path.first()
        for (i in 0 until path.lastIndex) {
            val a = path[i]; val b = path[i + 1]
            if (i > 0) out += GeoMath.interpolate(a, b, 0.25)
            if (i < path.lastIndex - 1) out += GeoMath.interpolate(a, b, 0.75)
        }
        out += path.last()
        return out
    }

    /** Distance from [p] to the great-circle segment [a]–[b]: off the line if it falls beside it, else to the nearer end. */
    private fun offsetKm(p: LatLon, a: LatLon, b: LatLon): Double {
        val va = vector(a); val vb = vector(b); val vp = vector(p)
        val n = cross(va, vb)
        val len = sqrt(dot(n, n))
        if (len < 1e-12) return GeoMath.distanceKm(p, a)
        val unit = doubleArrayOf(n[0] / len, n[1] / len, n[2] / len)
        // Beside the segment when p lies between a and b as seen along the great circle.
        val between = dot(cross(va, vp), n) >= 0 && dot(cross(vp, vb), n) >= 0
        return if (between) EARTH_KM * kotlin.math.abs(asin(dot(vp, unit).coerceIn(-1.0, 1.0)))
            else minOf(GeoMath.distanceKm(p, a), GeoMath.distanceKm(p, b))
    }

    private fun vector(p: LatLon): DoubleArray {
        val lat = Math.toRadians(p.lat); val lon = Math.toRadians(p.lon)
        return doubleArrayOf(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.cos(lat) * kotlin.math.sin(lon), kotlin.math.sin(lat))
    }
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
