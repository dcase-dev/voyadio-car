package com.voyadio.automotive.geo

class CountryLocator(private val atlas: WorldAtlas) {
    fun locate(p: LatLon): String? {
        for ((code, rings) in atlas.outlines) {
            if (code in atlas.countries && rings.any { contains(it, p) }) return code
        }
        return atlas.countries.values.minByOrNull { GeoMath.distanceKm(p, it.capital) }?.code
    }

    companion object {
        /** Even-odd ray casting on a flat [lat, lon, …] ring. */
        fun contains(ring: DoubleArray, p: LatLon): Boolean {
            var inside = false
            var j = ring.size - 2
            var i = 0
            while (i < ring.size) {
                val yi = ring[i]; val xi = ring[i + 1]; val yj = ring[j]; val xj = ring[j + 1]
                if ((yi > p.lat) != (yj > p.lat) && p.lon < (xj - xi) * (p.lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
                i += 2
            }
            return inside
        }
    }
}
