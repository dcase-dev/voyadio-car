package com.voyadio.automotive.geo

import kotlin.math.PI
import kotlin.math.sin

/**
 * The camp globe's slow sway (PO 2026-10-21: a dead-still globe read as a frozen app). Around the held point the
 * camera drifts in a lazy figure-eight — [LON_DEG] east–west, [LAT_DEG] north–south, once per [PERIOD_MS] — so the
 * station's country stays in view. It starts exactly on the held point, so entering the camp screen never jumps,
 * and the swing narrows as the globe zooms in (a zoomed-in country would otherwise slide off screen).
 */
object CampSway {
    const val PERIOD_MS = 120_000L
    const val LON_DEG = 8.0
    const val LAT_DEG = 3.0

    fun at(base: LatLon, elapsedMs: Long, zoom: Double): LatLon {
        val phase = 2 * PI * (elapsedMs % PERIOD_MS) / PERIOD_MS
        val narrow = 1.0 / zoom.coerceAtLeast(1.0)
        return LatLon(
            (base.lat + LAT_DEG * narrow * sin(2 * phase)).coerceIn(-85.0, 85.0),
            GeoMath.normalizeLon(base.lon + LON_DEG * narrow * sin(phase)),
        )
    }
}
