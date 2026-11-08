package com.voyadio.automotive.geo

import java.util.PriorityQueue

enum class LegKind { LAND, FERRY }

/**
 * One leg of the drive. [path] is the drawn line: just the two ends, a ferry's sea lane (see [SeaGrid]),
 * or the roads a land stretch follows (see [RoadNet]).
 */
data class Leg(
    val kind: LegKind, val from: LatLon, val to: LatLon, val km: Double, val hours: Double,
    val path: List<LatLon> = listOf(from, to),
)

data class Route(val legs: List<Leg>, val directKm: Double) {
    /** Elapsed time: land legs follow [DriveSchedule] (breaks, overnight stops); ferry time is added as is. */
    val hours: Double get() = DriveSchedule.elapsedHours(legs.filter { it.kind == LegKind.LAND }.sumOf { it.hours }) +
        legs.filter { it.kind == LegKind.FERRY }.sumOf { it.hours }
    val ferries: Int get() = legs.count { it.kind == LegKind.FERRY }

    /** How far the drive is, by road and sea (the straight-line distance is [directKm]). */
    val km: Double get() = legs.sumOf { it.km }
}

/**
 * Virtual drive from a point to a country's capital.
 * Fewest ferry crossings first, then fastest — land is used whenever it exists, even if slower.
 * Nodes: "C:XX" capital, "P:XX" representative port. Without [sea], ferries run straight between any two ports
 * (distance × [SEA_DETOUR]); with it, only between ports whose seas connect, over the real sea-lane km, and the
 * chosen ferry legs carry their lane around the land (PO decision 2026-10-13). With [roads], each unbroken run of
 * land legs becomes one drive along the major roads (2026-10-15); the country-level search above still picks the
 * crossings, so roads only reshape the land between them.
 */
class RouteFinder(private val atlas: WorldAtlas, private val sea: SeaGrid? = null, private val roads: RoadNet? = null) {
    private data class Cost(val ferries: Int, val hours: Double) : Comparable<Cost> {
        override fun compareTo(other: Cost) = compareValuesBy(this, other, Cost::ferries, Cost::hours)
        operator fun plus(leg: Leg) = Cost(ferries + if (leg.kind == LegKind.FERRY) 1 else 0, hours + leg.hours)
    }

    private val ports = atlas.countries.values.filter { it.port != null && (sea == null || sea.hasPort(it.code)) }

    fun find(origin: LatLon, originCountry: String, destination: String): Route? {
        val start = atlas.countries[originCountry] ?: return null
        val goal = atlas.countries[destination] ?: return null
        val direct = GeoMath.distanceKm(origin, goal.capital)
        if (start.code == goal.code) return Route(onRoads(listOf(land(origin, goal.capital))), direct)
        val best = HashMap<String, Cost>()
        val via = HashMap<String, Pair<String, Leg>>()
        val queue = PriorityQueue<Pair<Cost, String>>(compareBy { it.first })
        val startKey = "C:${start.code}"
        val first = land(origin, start.capital)
        best[startKey] = Cost(0, 0.0) + first
        via[startKey] = ORIGIN to first
        queue += best.getValue(startKey) to startKey
        val goalKey = "C:${goal.code}"
        while (queue.isNotEmpty()) {
            val (cost, node) = queue.poll()!!
            if (cost > best.getValue(node)) continue
            if (node == goalKey) break
            for ((next, leg) in edges(node)) {
                val candidate = cost + leg
                val known = best[next]
                if (known != null && candidate >= known) continue
                best[next] = candidate
                via[next] = node to leg
                queue += candidate to next
            }
        }
        if (goalKey !in via) return null
        val legs = ArrayDeque<Leg>()
        var node = goalKey
        while (node != ORIGIN) {
            val (previous, leg) = via.getValue(node)
            legs.addFirst(if (leg.kind == LegKind.FERRY) withLane(leg, previous.substring(2), node.substring(2)) else leg)
            node = previous
        }
        return Route(onRoads(legs), direct)
    }

    /** Each run of land legs as one drive along the roads; the straight legs stay where no road joins the ends. */
    private fun onRoads(legs: List<Leg>): List<Leg> {
        val net = roads ?: return legs
        val out = mutableListOf<Leg>()
        val run = mutableListOf<Leg>()
        fun flush() {
            if (run.isEmpty()) return
            val drive = net.drive(run.first().from, run.last().to)
            if (drive == null) out += run
            else out += Leg(LegKind.LAND, run.first().from, run.last().to, drive.km, drive.hours, drive.path)
            run.clear()
        }
        for (leg in legs) if (leg.kind == LegKind.LAND) run += leg else { flush(); out += leg }
        flush()
        return out
    }

    private fun edges(node: String): List<Pair<String, Leg>> {
        val country = atlas.countries.getValue(node.substring(2))
        val port = country.port
        return if (node.startsWith("C:")) buildList {
            country.neighbours.mapNotNull(atlas.countries::get).forEach { add("C:${it.code}" to land(country.capital, it.capital)) }
            if (port != null) add("P:${country.code}" to land(country.capital, port))
        } else buildList {
            requireNotNull(port)
            add("C:${country.code}" to land(port, country.capital))
            for (other in ports) {
                if (other.code == country.code) continue
                val km = if (sea == null) GeoMath.distanceKm(port, other.port!!) * SEA_DETOUR else sea.km(country.code, other.code) ?: continue
                add("P:${other.code}" to ferry(port, other.port!!, km))
            }
        }
    }

    private fun land(a: LatLon, b: LatLon): Leg {
        val km = GeoMath.distanceKm(a, b) * LAND_DETOUR
        return Leg(LegKind.LAND, a, b, km, km / LAND_KMH)
    }

    private fun ferry(a: LatLon, b: LatLon, km: Double) = Leg(LegKind.FERRY, a, b, km, km / SEA_KMH + BOARDING_HOURS)

    /** Only for the ferries a route actually takes: the lane is a grid search, too slow for every candidate. */
    private fun withLane(leg: Leg, from: String, to: String): Leg =
        sea?.path(from, leg.from, to, leg.to)?.let { leg.copy(path = it) } ?: leg

    companion object {
        const val LAND_KMH = 70.0
        const val LAND_DETOUR = 1.3
        const val SEA_KMH = 37.0
        const val SEA_DETOUR = 1.2
        const val BOARDING_HOURS = 2.0
        private const val ORIGIN = "O"
    }
}
