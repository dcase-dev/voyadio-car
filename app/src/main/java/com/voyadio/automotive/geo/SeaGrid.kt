package com.voyadio.automotive.geo

import org.json.JSONObject
import java.util.Base64
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Sea lanes for the car ferry (PO decision 2026-10-13): ferries go around land, never across it.
 * The bundled `geo/sea.json` (from `scripts/build-car-sea.py`) holds a 0.5° grid of sea cells, the sea cell each
 * port docks at, and the sea km between every two ports (-1 = the seas do not connect). [path] finds the same
 * shortest lane on the grid (8 directions, cost = km between cell centres) and straightens it for drawing.
 * A diagonal step between two land cells is kept only where its corner is real sea (a strait); the generator turns
 * the ones across islands and isthmuses into land (build-car-sea.py block_land_diagonals).
 */
class SeaGrid internal constructor(
    private val res: Double,
    private val cols: Int,
    private val rows: Int,
    private val sea: BooleanArray,
    private val docks: Map<String, Dock>,
    private val table: IntArray,
) {
    /** Where a port joins the grid: its sea [cell] and the [stubKm] from the port to that cell's centre. */
    internal class Dock(val index: Int, val cell: Int, val stubKm: Double)

    private val size = docks.size
    /** Per row, the km to each of the 8 neighbours — the same for every cell of a row. */
    private val steps = Array(rows) { r -> DoubleArray(8) { d -> stepKm(r, d) } }
    /** Turning points per port pair: a lane depends only on its two ports, and the start moving re-plans routes often. */
    private val lanes = ConcurrentHashMap<String, List<LatLon>>()

    /** Sea km from [from]'s port to [to]'s port, or null when either has no dock or the seas do not connect. */
    fun km(from: String, to: String): Double? {
        val a = docks[from] ?: return null
        val b = docks[to] ?: return null
        return table[a.index * size + b.index].takeIf { it >= 0 }?.toDouble()
    }

    /** Whether [code]'s port can take a ferry at all. */
    fun hasPort(code: String) = code in docks

    fun isSea(p: LatLon) = sea[cellOf(p)]

    internal fun dockCell(code: String) = docks[code]?.cell

    /**
     * The ferry lane from [fromAt] ([from]'s port) to [toAt] ([to]'s port): port, a few sea turning points, port.
     * Only the two port stubs may touch land (ports sit just inside the coarse coastline). Null when not connected.
     */
    fun path(from: String, fromAt: LatLon, to: String, toAt: LatLon): List<LatLon>? {
        val a = docks[from] ?: return null
        val b = docks[to] ?: return null
        if (km(from, to) == null) return null
        val turns = lanes.getOrPut("$from>$to") { straighten(cells(a.cell, b.cell) ?: return null).map(::center) }
        return listOf(fromAt) + turns + toAt
    }

    /** Grid cells of the shortest lane (A*, admissible great-circle estimate). */
    internal fun cells(start: Int, goal: Int): List<Int>? {
        val total = rows * cols
        val cost = DoubleArray(total) { Double.POSITIVE_INFINITY }
        val parent = IntArray(total) { -1 }
        val done = BooleanArray(total)
        val target = center(goal)
        val open = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        cost[start] = 0.0
        open += GeoMath.distanceKm(center(start), target) to start
        while (open.isNotEmpty()) {
            val cell = open.poll()!!.second
            if (done[cell]) continue
            if (cell == goal) break
            done[cell] = true
            val r = cell / cols
            val c = cell % cols
            for (d in 0 until 8) {
                val rr = r + DR[d]
                if (rr !in 0 until rows) continue
                val next = rr * cols + Math.floorMod(c + DC[d], cols)
                if (!sea[next] || done[next]) continue
                val g = cost[cell] + steps[r][d]
                if (g < cost[next]) {
                    cost[next] = g; parent[next] = cell
                    open += (g + GeoMath.distanceKm(center(next), target)) to next
                }
            }
        }
        if (start != goal && parent[goal] < 0) return null
        val out = ArrayDeque<Int>()
        var cell = goal
        while (cell != -1) { out.addFirst(cell); cell = if (cell == start) -1 else parent[cell] }
        return out.toList()
    }

    /** Grid km of [cells] (centre to centre) — what the bundled table adds the two port stubs to. */
    internal fun lengthKm(cells: List<Int>) = cells.zipWithNext { a, b -> GeoMath.distanceKm(center(a), center(b)) }.sum()

    /** Keeps only the turning points: from each kept cell, jump to the farthest later cell still in open sea. */
    private fun straighten(cells: List<Int>): List<Int> {
        if (cells.size <= 2) return cells
        val kept = mutableListOf(cells.first())
        var i = 0
        while (i < cells.lastIndex) {
            var j = i + 1
            while (j < cells.lastIndex && clear(center(cells[i]), center(cells[j + 1]))) j++
            kept += cells[j]
            i = j
        }
        return kept
    }

    /** Whether the great-circle line from [a] to [b] stays on sea cells (checked every eighth of a cell). */
    private fun clear(a: LatLon, b: LatLon): Boolean {
        val n = max(1, (GeoMath.distanceKm(a, b) / (res * 111.0 / 8)).toInt())
        for (k in 1 until n) if (!isSea(GeoMath.interpolate(a, b, k.toDouble() / n))) return false
        return true
    }

    internal fun center(cell: Int) = LatLon(90.0 - (cell / cols + 0.5) * res, -180.0 + (cell % cols + 0.5) * res)

    private fun cellOf(p: LatLon): Int {
        val r = ((90.0 - p.lat) / res).toInt().coerceIn(0, rows - 1)
        val c = Math.floorMod(((p.lon + 180.0) / res).toInt(), cols)
        return r * cols + c
    }

    private fun stepKm(r: Int, d: Int): Double {
        val rr = r + DR[d]
        if (rr !in 0 until rows) return Double.POSITIVE_INFINITY
        return GeoMath.distanceKm(center(r * cols + 1), center(rr * cols + 1 + DC[d]))
    }

    companion object {
        private val DR = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        private val DC = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)

        fun parse(text: String): SeaGrid {
            val root = JSONObject(text)
            val cols = root.getInt("cols")
            val rows = root.getInt("rows")
            val bits = Base64.getDecoder().decode(root.getString("sea"))
            val sea = BooleanArray(rows * cols) { (bits[it ushr 3].toInt() and (0x80 ushr (it and 7))) != 0 }
            val list = root.getJSONArray("ports")
            val docks = (0 until list.length()).associate { i ->
                val p = list.getJSONArray(i)
                p.getString(0) to Dock(i, p.getInt(1), p.getDouble(2))
            }
            val km = root.getJSONArray("km")
            return SeaGrid(root.getDouble("res"), cols, rows, sea, docks, IntArray(km.length()) { km.getInt(it) })
        }
    }
}
