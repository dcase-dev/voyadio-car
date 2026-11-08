package com.voyadio.automotive.geo

import java.util.Random

/** Decorative night-globe data derived from the bundled map: city lights and a capital-to-capital web. */
object NightLights {
    private const val LAT_SIGMA = 3.2
    private const val LON_SIGMA = 4.2
    private const val MAX_LAT = 89.9

    /** Gaussian scatter around each capital, kept only if it falls inside that country's outline. Deterministic. */
    fun lights(atlas: WorldAtlas, perCountry: Int = 24, seed: Long = 7L): List<LatLon> {
        val out = ArrayList<LatLon>()
        for (code in atlas.countries.keys.sorted()) {
            val rings = atlas.outlines[code]
            if (rings.isNullOrEmpty()) continue
            val capital = atlas.countries.getValue(code).capital
            val rnd = Random(seed xor code.hashCode().toLong())
            var kept = 0
            var tries = 0
            while (kept < perCountry && tries < perCountry * 4) {
                tries++
                val lat = (capital.lat + rnd.nextGaussian() * LAT_SIGMA).coerceIn(-MAX_LAT, MAX_LAT)
                val lon = GeoMath.normalizeLon(capital.lon + rnd.nextGaussian() * LON_SIGMA)
                if (rings.any { inside(lat, lon, it) }) {
                    out.add(LatLon(lat, lon))
                    kept++
                }
            }
        }
        return out
    }

    /** Great-circle arcs between neighbouring capitals closer than maxKm; each pair once (a < b). */
    fun web(atlas: WorldAtlas, maxKm: Double = 1_800.0): List<List<LatLon>> {
        val out = ArrayList<List<LatLon>>()
        for (code in atlas.countries.keys.sorted()) {
            val a = atlas.countries.getValue(code)
            for (other in a.neighbours.sorted()) {
                if (code >= other) continue
                val b = atlas.countries[other] ?: continue
                if (GeoMath.distanceKm(a.capital, b.capital) < maxKm) {
                    out.add(GeoMath.arc(a.capital, b.capital, 6))
                }
            }
        }
        return out
    }

    /** Ray casting over a flat [lat, lon, lat, lon, …] ring. Antimeridian rings are handled naively. */
    fun inside(lat: Double, lon: Double, ring: DoubleArray): Boolean {
        val n = ring.size / 2
        if (n < 3) return false
        var hit = false
        var j = n - 1
        for (i in 0 until n) {
            val latI = ring[2 * i]
            val lonI = ring[2 * i + 1]
            val latJ = ring[2 * j]
            val lonJ = ring[2 * j + 1]
            if ((latI > lat) != (latJ > lat) &&
                lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI
            ) hit = !hit
            j = i
        }
        return hit
    }
}
