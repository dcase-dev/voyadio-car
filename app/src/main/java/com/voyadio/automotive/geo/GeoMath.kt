package com.voyadio.automotive.geo

import kotlin.math.*

data class LatLon(val lat: Double, val lon: Double)

/** Orthographic projection on a unit disc. y grows to the north. */
data class Projected(val x: Double, val y: Double, val visible: Boolean)

object GeoMath {
    private const val EARTH_KM = 6371.0

    fun distanceKm(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_KM * asin(min(1.0, sqrt(h)))
    }

    /** Points along the great circle from [a] to [b], both inclusive. */
    fun arc(a: LatLon, b: LatLon, segments: Int): List<LatLon> {
        val va = vector(a); val vb = vector(b)
        val d = acos((va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1.0, 1.0))
        if (d < 1e-9) return listOf(a, b)
        return (0..segments).map { i ->
            when (i) {
                0 -> a
                segments -> b
                else -> {
                    val f = i.toDouble() / segments
                    val wa = sin((1 - f) * d) / sin(d); val wb = sin(f * d) / sin(d)
                    val x = wa * va[0] + wb * vb[0]; val y = wa * va[1] + wb * vb[1]; val z = wa * va[2] + wb * vb[2]
                    LatLon(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
                }
            }
        }
    }

    fun project(p: LatLon, center: LatLon): Projected {
        val lat = Math.toRadians(p.lat); val lat0 = Math.toRadians(center.lat)
        val dl = Math.toRadians(p.lon - center.lon)
        val cosc = sin(lat0) * sin(lat) + cos(lat0) * cos(lat) * cos(dl)
        val x = cos(lat) * sin(dl)
        val y = cos(lat0) * sin(lat) - sin(lat0) * cos(lat) * cos(dl)
        return Projected(x, y, cosc >= 0)
    }

    fun normalizeLon(lon: Double): Double {
        val r = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (r == -180.0) 180.0 else r
    }

    /** Signed difference a - b in degrees, wrapped to (-180, 180]. */
    fun angleDiff(a: Double, b: Double) = normalizeLon(a - b)

    /** Point at fraction [f] (0..1) of the great circle from [a] to [b]. */
    fun interpolate(a: LatLon, b: LatLon, f: Double): LatLon {
        if (f <= 0.0) return a
        if (f >= 1.0) return b
        val va = vector(a); val vb = vector(b)
        val d = acos((va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1.0, 1.0))
        if (d < 1e-9) return a
        val wa = sin((1 - f) * d) / sin(d); val wb = sin(f * d) / sin(d)
        val x = wa * va[0] + wb * vb[0]; val y = wa * va[1] + wb * vb[1]; val z = wa * va[2] + wb * vb[2]
        return LatLon(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
    }

    /** [points] points on the circle [radiusKm] around [center] (great-circle distance), first repeated at the end. */
    fun circle(center: LatLon, radiusKm: Double, points: Int): List<LatLon> {
        val lat1 = Math.toRadians(center.lat); val lon1 = Math.toRadians(center.lon)
        val d = radiusKm / EARTH_KM
        val ring = (0 until points).map { i ->
            val bearing = 2 * PI * i / points
            val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(bearing))
            val lon2 = lon1 + atan2(sin(bearing) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
            LatLon(Math.toDegrees(lat2), normalizeLon(Math.toDegrees(lon2)))
        }
        return ring + ring.first()
    }

    private fun vector(p: LatLon): DoubleArray {
        val lat = Math.toRadians(p.lat); val lon = Math.toRadians(p.lon)
        return doubleArrayOf(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
    }
}
