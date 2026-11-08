package com.voyadio.automotive.geo

import org.json.JSONObject
import java.util.PriorityQueue

/**
 * The world's major roads as a graph (2026-10-15): roads.json from scripts/build-car-roads.py, made from Natural
 * Earth ne_10m_roads (public domain). The land part of a drive follows them instead of a straight line, and its
 * km and hours are the road's. Nodes are junctions and road ends; each edge keeps the bends between them.
 */
class RoadNet private constructor(
    private val lat: DoubleArray, private val lon: DoubleArray,
    private val edgeFrom: IntArray, private val edgeTo: IntArray, private val edgeKm: DoubleArray, private val fast: BooleanArray,
    private val shapeStart: IntArray, private val shapeLat: DoubleArray, private val shapeLon: DoubleArray,
) {
    /** A drive along the roads: the drawn [path] from start to end, its length and driving time (no breaks). */
    data class Drive(val path: List<LatLon>, val km: Double, val hours: Double)

    private val nodes = lat.size

    // Edges of node i: around[first[i] until first[i + 1]] (edge numbers).
    private val first = IntArray(nodes + 1)
    private val around: IntArray

    init {
        for (e in edgeFrom.indices) { first[edgeFrom[e] + 1]++; first[edgeTo[e] + 1]++ }
        for (i in 0 until nodes) first[i + 1] += first[i]
        around = IntArray(first[nodes])
        val fill = first.copyOf()
        for (e in edgeFrom.indices) { around[fill[edgeFrom[e]]++] = e; around[fill[edgeTo[e]]++] = e }
    }

    /**
     * Road route from [from] to [to]: a short straight link to the nearest road at each end, the roads between.
     * Null when either end is more than [MAX_LINK_KM] from a road, both ends meet the road at the same junction,
     * or the roads there do not join (another island) — the caller keeps its straight leg then.
     */
    fun drive(from: LatLon, to: LatLon): Drive? {
        val start = nearest(from) ?: return null
        val goal = nearest(to) ?: return null
        if (start == goal) return null
        val goalAt = LatLon(lat[goal], lon[goal])
        val hours = DoubleArray(nodes) { Double.POSITIVE_INFINITY }
        val via = IntArray(nodes) { -1 }
        val done = BooleanArray(nodes)
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        hours[start] = 0.0
        queue += 0.0 to start
        while (queue.isNotEmpty()) {
            val node = queue.poll()!!.second
            if (done[node]) continue
            done[node] = true
            if (node == goal) break
            for (k in first[node] until first[node + 1]) {
                val e = around[k]
                val next = if (edgeFrom[e] == node) edgeTo[e] else edgeFrom[e]
                val candidate = hours[node] + edgeKm[e] / speed(e)
                if (candidate >= hours[next]) continue
                hours[next] = candidate
                via[next] = e
                queue += (candidate + GeoMath.distanceKm(LatLon(lat[next], lon[next]), goalAt) / FAST_KMH) to next
            }
        }
        if (!done[goal]) return null
        // Walk back from the goal, then turn the path around.
        val back = mutableListOf(goalAt)
        var km = 0.0
        var node = goal
        while (node != start) {
            val e = via[node]
            val forward = edgeTo[e] == node // walked edgeFrom → edgeTo
            val bends = (shapeStart[e] until shapeStart[e + 1]).map { LatLon(shapeLat[it], shapeLon[it]) }
            back += if (forward) bends.asReversed() else bends
            node = if (forward) edgeFrom[e] else edgeTo[e]
            back += LatLon(lat[node], lon[node])
            km += edgeKm[e]
        }
        val roads = back.asReversed()
        val linkKm = (GeoMath.distanceKm(from, roads.first()) + GeoMath.distanceKm(roads.last(), to)) * RouteFinder.LAND_DETOUR
        return Drive(listOf(from) + roads + to, km + linkKm, hours[goal] + linkKm / LINK_KMH)
    }

    private fun speed(e: Int) = if (fast[e]) FAST_KMH else ROAD_KMH

    /** The closest junction within [MAX_LINK_KM], by a quick flat-earth measure (exact enough to pick one). */
    private fun nearest(p: LatLon): Int? {
        val shrink = kotlin.math.cos(Math.toRadians(p.lat))
        var best = -1
        var bestScore = Double.POSITIVE_INFINITY
        for (i in 0 until nodes) {
            val dLat = lat[i] - p.lat
            val dLon = GeoMath.angleDiff(lon[i], p.lon) * shrink
            val score = dLat * dLat + dLon * dLon
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best < 0 || GeoMath.distanceKm(p, LatLon(lat[best], lon[best])) > MAX_LINK_KM) return null
        return best
    }

    companion object {
        const val FAST_KMH = 90.0 // motorways (Natural Earth "Major Highway", "Beltway" or expressway)
        const val ROAD_KMH = 70.0 // other main roads, as the straight-line estimate before (RouteFinder.LAND_KMH)
        const val LINK_KMH = 50.0 // from the car or a capital to the nearest main road
        const val MAX_LINK_KM = 150.0

        fun parse(text: String): RoadNet {
            val root = JSONObject(text)
            val unit = root.getDouble("unitsPerDegree")
            val nodes = root.getJSONArray("nodes")
            val n = nodes.length() / 2
            val lat = DoubleArray(n) { nodes.getInt(it * 2) / unit }
            val lon = DoubleArray(n) { nodes.getInt(it * 2 + 1) / unit }
            val edges = root.getJSONArray("edges")
            val m = edges.length() / 5
            val from = IntArray(m); val to = IntArray(m); val km = DoubleArray(m); val fast = BooleanArray(m)
            val shapeStart = IntArray(m + 1)
            for (e in 0 until m) {
                from[e] = edges.getInt(e * 5); to[e] = edges.getInt(e * 5 + 1); fast[e] = edges.getInt(e * 5 + 2) == 1
                shapeStart[e + 1] = shapeStart[e] + edges.getInt(e * 5 + 3)
                km[e] = edges.getInt(e * 5 + 4) / 10.0
            }
            val shape = root.getJSONArray("shape")
            val bends = shape.length() / 2
            return RoadNet(lat, lon, from, to, km, fast, shapeStart,
                DoubleArray(bends) { shape.getInt(it * 2) / unit }, DoubleArray(bends) { shape.getInt(it * 2 + 1) / unit })
        }
    }
}
