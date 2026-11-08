package com.voyadio.automotive.geo

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Where the globe camera looks. It follows the route from start to destination, keeps turning the same way
 * around the globe back to the start, and loops — one lap every [LAP_MS] at a constant speed. Paused playback
 * runs the same loop at one lap per [PAUSED_LAP_MS]. A new route is reached by a [TRANSITION_MS] glide.
 * Without a usable route the camera spins slowly westward along one latitude.
 */
class GlobeDirector {
    /**
     * What rides the route (2026-10-13): a car on land, a car ferry on the sea legs, facing [east] or west.
     * Past the destination it is [parked] there while the globe turns on back to the start.
     */
    data class Vehicle(val at: LatLon, val ferry: Boolean, val east: Boolean, val parked: Boolean)

    /**
     * A closed loop: the route, then a return leg continuing in the route's longitude [direction].
     * [ferry] marks each route segment (points[i] → points[i + 1]) sailed by car ferry.
     */
    class Loop private constructor(
        private val points: List<LatLon>, private val cumulative: DoubleArray, private val ferry: BooleanArray,
        private val routeEnd: Int, val routeShare: Double, val direction: Double,
    ) {
        private val total = cumulative.last()

        /** Position at [fraction] (0..1) of the lap, constant speed along the arc. */
        fun at(fraction: Double): LatLon {
            val d = wrap(fraction) * total
            val lo = segment(d)
            val span = cumulative[lo + 1] - cumulative[lo]
            return if (span <= 0.0) points[lo] else GeoMath.interpolate(points[lo], points[lo + 1], (d - cumulative[lo]) / span)
        }

        /** Parked at the destination, facing the way it arrived. */
        fun parked() = Vehicle(points[routeEnd], ferry = false, east = east(routeEnd - 1), parked = true)

        /** The vehicle at [fraction]: on the route it is at the camera point ([at]); past the destination, parked. */
        fun vehicle(fraction: Double): Vehicle {
            val d = wrap(fraction) * total
            if (d >= cumulative[routeEnd]) return parked()
            val i = segment(d)
            return Vehicle(at(fraction), ferry[i], east(i), parked = false)
        }

        private fun wrap(fraction: Double) = ((fraction % 1.0) + 1.0) % 1.0

        /** Index of the segment holding distance [d]. */
        private fun segment(d: Double): Int {
            var lo = 0; var hi = cumulative.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumulative[mid] <= d) lo = mid else hi = mid }
            return lo
        }

        /** Whether segment [i] heads east; a due north/south segment keeps the lap's direction. */
        private fun east(i: Int): Boolean {
            val step = GeoMath.angleDiff(points[i + 1].lon, points[i].lon)
            return if (abs(step) > 1e-9) step > 0 else direction > 0
        }

        companion object {
            private const val MIN_ROUTE_KM = 220.0 // about 2°: shorter routes spin in place
            private const val RETURN_STEP_DEG = 2.0

            /** [ferry] has one flag per segment of [path] (null = all land). */
            fun of(path: List<LatLon>, ferry: List<Boolean>? = null): Loop? = build(path, ferry, aroundTheWorld = true)

            /** Just the route, any length, no way back: for a held camera the vehicle drives it and parks. */
            fun route(path: List<LatLon>, ferry: List<Boolean>? = null): Loop? = build(path, ferry, aroundTheWorld = false)

            private fun build(path: List<LatLon>, ferry: List<Boolean>?, aroundTheWorld: Boolean): Loop? {
                require(ferry == null || ferry.size == path.size - 1) { "one ferry flag per segment" }
                // Drop repeated points (leg joints); a dropped point takes its zero-length segment with it.
                val route = mutableListOf<LatLon>()
                val sea = mutableListOf<Boolean>()
                for ((i, p) in path.withIndex()) {
                    if (route.isNotEmpty() && GeoMath.distanceKm(route.last(), p) <= 1e-3) continue
                    if (route.isNotEmpty()) sea += ferry?.get(i - 1) ?: false
                    route += p
                }
                if (route.size < 2) return null
                val routeKm = route.zipWithNext { a, b -> GeoMath.distanceKm(a, b) }.sum()
                if (aroundTheWorld && routeKm < MIN_ROUTE_KM) return null
                val net = route.zipWithNext { a, b -> GeoMath.angleDiff(b.lon, a.lon) }.sum()
                val direction = if (net > 0) 1.0 else -1.0
                val start = route.first(); val end = route.last()
                val remaining = (360.0 - abs(net)).coerceAtLeast(0.0)
                val steps = maxOf(1, ceil(remaining / RETURN_STEP_DEG).toInt())
                val back = if (!aroundTheWorld) emptyList() else (1..steps).map { k ->
                    val t = k.toDouble() / steps
                    if (k == steps) start
                    else LatLon(end.lat + (start.lat - end.lat) * t, GeoMath.normalizeLon(end.lon + direction * remaining * t))
                }
                val points = route + back
                val cumulative = DoubleArray(points.size)
                for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + GeoMath.distanceKm(points[i - 1], points[i])
                if (cumulative.last() <= 0.0) return null
                val flags = BooleanArray(points.size - 1) { it < sea.size && sea[it] }
                return Loop(points, cumulative, flags, route.lastIndex, routeKm / cumulative.last(), direction)
            }
        }
    }

    /** What the camera was last pointed at: start, destination, whether a route exists, and the channel. */
    data class Aim(val from: LatLon?, val to: LatLon?, val hasRoute: Boolean, val channel: String?)

    private var loop: Loop? = null
    private var hold: LatLon? = null
    private var progress = 0.0
    private var direction = -1.0
    private var spinLat = 20.0
    private var spinLon = 127.0
    private var glideFrom: LatLon? = null
    private var glideMs = 0L
    private var aimed = false

    var center: LatLon = LatLon(20.0, 127.0)
        private set

    /**
     * [path] runs from the start to the destination; null or too short spins in place at [fallback].
     * With [hold] (a short route, see [GlobeZoom.hold]) the camera stays there instead and the vehicle drives
     * [path] in [DRIVE_SHARE] of each lap, then parks; a null [path] with [hold] just holds (two pins).
     * The first aim of a new director starts right there; later ones glide over [TRANSITION_MS].
     */
    fun retarget(path: List<LatLon>?, fallback: LatLon?, ferry: List<Boolean>? = null, hold: LatLon? = null) {
        val next = path?.let { if (hold != null) Loop.route(it, ferry) else Loop.of(it, ferry) }
        loop = next; progress = 0.0; this.hold = hold
        if (next != null) direction = next.direction
        else if (hold == null) {
            val at = fallback ?: center
            spinLat = at.lat.coerceIn(-60.0, 60.0); spinLon = at.lon; direction = -1.0
        }
        if (aimed) { glideFrom = center; glideMs = 0L }
        else { glideFrom = null; center = target() }
        aimed = true
    }

    /** Where a glide ends: the held point, the start of the lap, or the spin. */
    private fun target() = hold ?: loop?.at(0.0) ?: LatLon(spinLat, spinLon)

    /**
     * Swaps in a slightly different [path] (e.g. the start moved a little) without a glide: the lap carries on
     * at the same progress. If the route turns usable or unusable, or the lap would turn the other way,
     * this falls back to [retarget].
     */
    fun replace(path: List<LatLon>?, fallback: LatLon?, ferry: List<Boolean>? = null, hold: LatLon? = null) {
        val held = this.hold
        if (hold != null || held != null) {
            // A held camera only stays put when it holds (almost) the same place.
            if (hold == null || held == null || GeoMath.distanceKm(hold, held) > 1.0) retarget(path, fallback, ferry, hold)
            else loop = path?.let { Loop.route(it, ferry) }
            return
        }
        val next = path?.let { Loop.of(it, ferry) }
        val current = loop
        if ((next == null) != (current == null) || (next != null && next.direction != current?.direction)) {
            retarget(path, fallback, ferry); return
        }
        if (next != null) loop = next
    }

    /** What rides the route now; none while gliding to a new route or spinning without one. */
    fun vehicle(): Vehicle? {
        if (glideFrom != null) return null
        val current = loop ?: return null
        if (hold == null) return current.vehicle(progress)
        return if (progress < DRIVE_SHARE) current.vehicle(progress / DRIVE_SHARE) else current.parked()
    }

    /** Moves the camera on by [dtMs] (any size) and returns the new [center]. */
    fun advance(dtMs: Long, playing: Boolean): LatLon {
        val dt = dtMs.coerceAtLeast(0L)
        val current = loop
        val from = glideFrom
        if (from != null) {
            glideMs += dt
            val t = (glideMs.toDouble() / TRANSITION_MS).coerceIn(0.0, 1.0)
            val eased = t * t * (3 - 2 * t)
            center = GeoMath.interpolate(from, target(), eased)
            if (t < 1.0) return center
            glideFrom = null
            return center
        }
        val held = hold
        if (held != null) {
            val lap = if (playing) LAP_MS else PAUSED_LAP_MS
            progress = (progress + dt.toDouble() / lap) % 1.0
            center = held
        } else if (current != null) {
            val lap = if (playing) LAP_MS else PAUSED_LAP_MS
            progress = (progress + dt.toDouble() / lap) % 1.0
            center = current.at(progress)
        } else {
            spinLon = GeoMath.normalizeLon(spinLon + direction * 360.0 * dt / PAUSED_LAP_MS)
            center = LatLon(spinLat, spinLon)
        }
        return center
    }

    companion object {
        const val LAP_MS = 60_000L
        const val PAUSED_LAP_MS = 180_000L
        const val TRANSITION_MS = 2_000L
        const val RETARGET_KM = 100.0

        /** A held camera: the share of each lap the vehicle spends driving; the rest it waits at the destination. */
        const val DRIVE_SHARE = 0.8

        /**
         * Joins the legs' drawn arcs ([Pair.first] = ferry) into one camera path with one ferry flag per segment.
         * Each leg starts where the last one ended, so joints repeat a point; [Loop.of] drops it with its flag.
         */
        fun join(arcs: List<Pair<Boolean, List<LatLon>>>): Pair<List<LatLon>, List<Boolean>> {
            val points = mutableListOf<LatLon>()
            val ferry = mutableListOf<Boolean>()
            for ((sea, arc) in arcs) {
                if (arc.isEmpty()) continue
                if (points.isNotEmpty()) ferry += sea
                points += arc
                repeat(arc.size - 1) { ferry += sea }
            }
            return points to ferry
        }

        /**
         * A fresh 2-second glide happens on the first aim, a new channel, a new destination, a route appearing
         * or going, or the start (the car) moving [RETARGET_KM] or more; smaller moves keep the lap (see [replace]).
         */
        fun needsRetarget(old: Aim?, new: Aim): Boolean {
            if (old == null) return true
            if (old.channel != new.channel || old.to != new.to || old.hasRoute != new.hasRoute) return true
            if (old.from == null || new.from == null) return old.from != new.from
            return GeoMath.distanceKm(old.from, new.from) >= RETARGET_KM
        }
    }
}
