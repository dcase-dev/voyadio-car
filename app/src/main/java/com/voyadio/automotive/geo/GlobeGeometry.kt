package com.voyadio.automotive.geo

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One outline ring (or route arc) with its trigonometry worked out once, so drawing a frame
 * needs only multiplies and adds. The cap is the smallest-ish circle on the sphere holding every
 * point: unit-vector center (capX, capY, capZ) and angular radius in radians.
 */
class Outline(
    val code: String,
    val sinLat: DoubleArray, val cosLat: DoubleArray,
    val sinLon: DoubleArray, val cosLon: DoubleArray,
    val capX: Double, val capY: Double, val capZ: Double, val capRadius: Double,
) {
    val size get() = sinLat.size
    internal val sinCapRadius = sin(capRadius)
}

class GlobeGeometry(val outlines: List<Outline>) {
    companion object {
        /** Every ring of every country, largest first so enclaves are drawn over their hosts. */
        fun of(rings: Map<String, List<DoubleArray>>) =
            GlobeGeometry(rings.flatMap { (code, list) -> list.map { outline(it, code) } }.sortedByDescending { it.capRadius })

        /** An open line or point set, e.g. a route arc or city lights. */
        fun outline(points: List<LatLon>, code: String = "") =
            outline(DoubleArray(points.size * 2) { if (it % 2 == 0) points[it / 2].lat else points[it / 2].lon }, code)

        /** [flat] is [lat, lon, lat, lon, …] in degrees. */
        fun outline(flat: DoubleArray, code: String = ""): Outline {
            val n = flat.size / 2
            val sinLat = DoubleArray(n); val cosLat = DoubleArray(n); val sinLon = DoubleArray(n); val cosLon = DoubleArray(n)
            // A closed ring repeats its first point at the end; count it once so the center isn't pulled.
            val closed = n > 1 && flat[0] == flat[n * 2 - 2] && flat[1] == flat[n * 2 - 1]
            var mx = 0.0; var my = 0.0; var mz = 0.0
            for (i in 0 until n) {
                val lat = Math.toRadians(flat[i * 2]); val lon = Math.toRadians(flat[i * 2 + 1])
                sinLat[i] = sin(lat); cosLat[i] = cos(lat); sinLon[i] = sin(lon); cosLon[i] = cos(lon)
                if (closed && i == n - 1) continue
                mx += cosLat[i] * cosLon[i]; my += cosLat[i] * sinLon[i]; mz += sinLat[i]
            }
            val norm = sqrt(mx * mx + my * my + mz * mz)
            if (norm < 1e-9) return Outline(code, sinLat, cosLat, sinLon, cosLon, 1.0, 0.0, 0.0, PI) // never culled
            mx /= norm; my /= norm; mz /= norm
            var minDot = 1.0
            for (i in 0 until n) {
                val dot = cosLat[i] * cosLon[i] * mx + cosLat[i] * sinLon[i] * my + sinLat[i] * mz
                if (dot < minDot) minDot = dot
            }
            return Outline(code, sinLat, cosLat, sinLon, cosLon, mx, my, mz, acos(minDot.coerceIn(-1.0, 1.0)))
        }
    }
}

/**
 * Orthographic view from [center] for one frame — same result as [GeoMath.project].
 * [project] leaves the point in [x], [y] (unit disc, y north) and [depth] (cos of the angle from
 * the center; the point faces the viewer when it is ≥ 0) to avoid allocating per point.
 * [aim] re-points the same frame so a view can reuse it every frame.
 */
class GlobeFrame(center: LatLon = LatLon(0.0, 0.0)) {
    private var sinLat0 = 0.0
    private var cosLat0 = 1.0
    private var sinLon0 = 0.0
    private var cosLon0 = 1.0
    private var viewX = 1.0
    private var viewY = 0.0
    private var viewZ = 0.0
    private var capNorth = 0.0
    private var capToward = 1.0
    private var capRadius = PI
    private var capX = 1.0
    private var capY = 0.0
    private var capZ = 0.0

    init { aim(center.lat, center.lon) }

    fun aim(lat: Double, lon: Double) {
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        sinLat0 = sin(la); cosLat0 = cos(la); sinLon0 = sin(lo); cosLon0 = cos(lo)
        viewX = cosLat0 * cosLon0; viewY = cosLat0 * sinLon0; viewZ = sinLat0
        placeCap()
    }

    /**
     * Limits [isOffScreen] to a cap straight north of the view center, given in view-local axes ([north],
     * [toward] the viewer; a unit vector) with angular [radius] in radians. It follows the frame through [aim].
     */
    fun screenCap(north: Double, toward: Double, radius: Double) {
        capNorth = north; capToward = toward; capRadius = radius
        placeCap()
    }

    private fun placeCap() {
        // north = (-sinLat0 cosLon0, -sinLat0 sinLon0, cosLat0), toward = view
        capX = -capNorth * sinLat0 * cosLon0 + capToward * viewX
        capY = -capNorth * sinLat0 * sinLon0 + capToward * viewY
        capZ = capNorth * cosLat0 + capToward * viewZ
    }

    /** True when no point of [o] can lie inside the [screenCap]. */
    fun isOffScreen(o: Outline): Boolean {
        val limit = capRadius + o.capRadius
        if (limit >= PI) return false
        val dot = o.capX * capX + o.capY * capY + o.capZ * capZ
        return dot < cos(limit) - 1e-9
    }

    var x = 0.0; private set
    var y = 0.0; private set
    var depth = 0.0; private set

    fun project(o: Outline, i: Int): Boolean {
        val cosDl = o.cosLon[i] * cosLon0 + o.sinLon[i] * sinLon0
        val sinDl = o.sinLon[i] * cosLon0 - o.cosLon[i] * sinLon0
        val cosLat = o.cosLat[i]; val sinLat = o.sinLat[i]
        x = cosLat * sinDl
        y = cosLat0 * sinLat - sinLat0 * cosLat * cosDl
        depth = sinLat0 * sinLat + cosLat0 * cosLat * cosDl
        return depth >= 0
    }

    /** Moves a point on the far side onto the rim in the same direction, for filling cut shapes. */
    fun pushToRim() {
        if (depth >= 0) return
        val length = sqrt(x * x + y * y)
        if (length > 1e-12) { x /= length; y /= length }
    }

    /**
     * True when no point of [o] can face the viewer: the cap center lies more than
     * 90° + capRadius from the view center, i.e. cos(gap) < cos(90° + r) = −sin r.
     */
    fun isHidden(o: Outline): Boolean {
        if (o.capRadius >= PI / 2) return false
        val dot = o.capX * viewX + o.capY * viewY + o.capZ * viewZ
        return dot < -o.sinCapRadius - 1e-9
    }

    /** Zoomed view: true when no point of [o] can lie within [viewRadius] radians of the view center. */
    fun isOutsideView(o: Outline, viewRadius: Double): Boolean {
        val limit = viewRadius + o.capRadius
        if (limit >= PI) return false
        val dot = o.capX * viewX + o.capY * viewY + o.capZ * viewZ
        return dot < cos(limit) - 1e-9
    }
}
