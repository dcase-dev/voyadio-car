package com.voyadio.automotive.geo

/** Points along a route measured in road km — the same km [RouteFinder] gives each leg. */
object RouteTrace {
    /**
     * From the route start to [km] along it (clamped to the route end), a point about every [stepKm].
     * Within a leg the road km are spread evenly over its drawn [Leg.path] (a ferry follows its sea lane).
     */
    fun along(route: Route, km: Double, stepKm: Double = 2.0): List<LatLon> {
        require(stepKm > 0) { "stepKm must be positive" }
        val legs = route.legs
        if (legs.isEmpty()) return emptyList()
        val out = mutableListOf(legs.first().from)
        var left = km.coerceAtLeast(0.0)
        for (leg in legs) {
            if (left <= 0.0) break
            if (leg.km <= 0.0) continue
            val take = minOf(left, leg.km)
            val steps = maxOf(1, (take / stepKm).toInt())
            val lane = Lane(leg.path)
            for (i in 1..steps) out += lane.at(take / leg.km * i / steps)
            left -= take
        }
        return out
    }

    /** The single point [km] along the route, or null for an empty route. */
    fun pointAt(route: Route, km: Double): LatLon? = along(route, km, stepKm = Double.MAX_VALUE).lastOrNull()

    /** The point at [fraction] (0..1) of [path]'s length, along great circles between its points. */
    fun pointOn(path: List<LatLon>, fraction: Double): LatLon = Lane(path).at(fraction)

    /** A polyline with its part lengths measured once, for many points along it. */
    private class Lane(private val path: List<LatLon>) {
        private val parts = path.zipWithNext { a, b -> GeoMath.distanceKm(a, b) }
        private val total = parts.sum()

        init { require(path.isNotEmpty()) { "a lane needs at least one point" } }

        fun at(fraction: Double): LatLon {
            if (parts.isEmpty()) return path[0]
            var left = fraction.coerceIn(0.0, 1.0) * total
            for ((i, part) in parts.withIndex()) {
                if (left <= part || i == parts.lastIndex) {
                    return GeoMath.interpolate(path[i], path[i + 1], if (part > 0) (left / part).coerceIn(0.0, 1.0) else 1.0)
                }
                left -= part
            }
            return path.last()
        }
    }
}
