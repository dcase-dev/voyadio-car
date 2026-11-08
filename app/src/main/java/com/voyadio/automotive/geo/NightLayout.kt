package com.voyadio.automotive.geo

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the full-screen night globe sits on a [width]×[height] screen (pixels). The globe is much larger
 * than the screen: its top edge (the horizon) is near the top and the camera point sits at
 * ([width]/2, [CAMERA_Y]·[height]), north up. Pure math so it can be unit tested.
 */
class NightLayout private constructor(val width: Double, val height: Double, margin: Double) {
    val radius = max(1.5 * height, 0.62 * width)
    val cx = width / 2
    val cy = LIMB_TOP * height + radius

    /** How far north of the projection center the camera point sits, in degrees. */
    val tiltDeg = Math.toDegrees(asin(((cy - CAMERA_Y * height) / radius).coerceIn(0.0, 0.99)))

    /** Latitude to aim the projection at so a camera at [cameraLat] lands on the camera point. */
    fun viewLat(cameraLat: Double) = (cameraLat - tiltDeg).coerceIn(-MAX_VIEW_LAT, MAX_VIEW_LAT)

    /**
     * Globe center height for a camera at [cameraLat]. Usually [cy]; far south the view latitude stops at the
     * pole, so the camera sits closer to the center than [tiltDeg] and the globe moves up (the horizon may leave
     * the top of the screen) to keep the camera point at [CAMERA_Y].
     */
    fun centerY(cameraLat: Double): Double {
        if (cameraLat - tiltDeg >= -MAX_VIEW_LAT) return cy
        return CAMERA_Y * height + radius * sin(Math.toRadians(cameraLat - viewLat(cameraLat)))
    }

    // The part of the globe that can reach the screen (plus [margin]) while the center is at [cy], as a cap
    // straight north of the view center: [capNorth] and [capToward] are its view-local unit vector.
    val capNorth: Double
    val capToward: Double
    val capRadius: Double

    init {
        val left = -(cx + margin) / radius; val right = (width - cx + margin) / radius
        val bottom = (cy - height - margin) / radius; val top = min(1.0, (cy + margin) / radius)
        capNorth = ((bottom.coerceAtLeast(-1.0) + top) / 2).coerceIn(-1.0, 1.0)
        capToward = sqrt(1 - capNorth * capNorth)
        var widest = 0.0
        fun reach(x: Double, y: Double) {
            var px = x; var py = y
            val length = hypot(px, py)
            if (length > 1) { px /= length; py /= length } // beyond the disc: the nearest rim point
            val pz = sqrt((1 - px * px - py * py).coerceAtLeast(0.0))
            widest = max(widest, acos((py * capNorth + pz * capToward).coerceIn(-1.0, 1.0)))
        }
        for (i in 0..GRID) for (j in 0..GRID) reach(left + (right - left) * i / GRID, bottom + (top - bottom) * j / GRID)
        for (k in 0 until 720) {
            val a = k * PI / 360
            val x = cos(a); val y = sin(a)
            if (x in left..right && y in bottom..top) reach(x, y)
        }
        capRadius = (widest + SAMPLE_SLACK).coerceAtMost(PI)
    }

    companion object {
        /** Null while the view has no size yet. */
        fun of(width: Double, height: Double, margin: Double = 0.0) =
            if (width > 0 && height > 0) NightLayout(width, height, margin) else null

        const val LIMB_TOP = 0.11
        private const val MAX_VIEW_LAT = 89.9
        const val CAMERA_Y = 0.45
        private const val GRID = 48
        private const val SAMPLE_SLACK = 0.03 // radians, covers the gaps between samples
    }
}
