package com.voyadio.automotive.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How close the globe comes for a route (2026-10-15). On the whole Earth a 270 km drive is a dot the camera
 * passes in under a second, so a short route is held still and zoomed until it fills about [WIDTH_SHARE] of
 * the screen width; the car then drives it. Long routes keep the whole-globe lap.
 */
object GlobeZoom {
    /** Routes whose farthest point lies within this of their middle are held still and zoomed. */
    const val HOLD_KM = 1_500.0

    /** A station this close is "right around here": two pins, no drive. */
    const val LOCAL_KM = 30.0

    /** Half the screen width never shows less than this: the world map is too coarse to come closer. */
    const val MIN_VIEW_KM = 150.0

    const val WIDTH_SHARE = 0.6
    const val HEIGHT_SHARE = 0.55

    /** Fitting into an area already free of text and buttons ([GlobeView.clear]): fill most of it. */
    const val CLEAR_SHARE = 0.8
    private const val EARTH_KM = 6371.0

    /** Where the camera holds for a route through [points] from [from] to [to], or null to keep the lap. */
    fun hold(from: LatLon, to: LatLon, points: List<LatLon>): LatLon? {
        val middle = GeoMath.interpolate(from, to, 0.5)
        val reach = (points + from + to).maxOf { GeoMath.distanceKm(middle, it) }
        return if (reach <= HOLD_KM) middle else null
    }

    fun isLocal(from: LatLon, to: LatLon) = GeoMath.distanceKm(from, to) <= LOCAL_KM

    /**
     * How many times larger than its usual [radius] (pixels) the globe is drawn so [points] seen from [focus]
     * fill [WIDTH_SHARE] of [width] (or [HEIGHT_SHARE] of [height], whichever is tighter). Never below 1,
     * never closer than [MIN_VIEW_KM] across half the width. The night globe is seen tilted ([tiltDeg],
     * see [NightLayout.tiltDeg]), so north–south distances look shorter on screen by its cosine.
     */
    fun zoom(
        focus: LatLon, points: List<LatLon>, width: Double, height: Double, radius: Double, tiltDeg: Double = 0.0,
        widthShare: Double = WIDTH_SHARE, heightShare: Double = HEIGHT_SHARE,
    ): Double {
        if (width <= 0 || height <= 0 || radius <= 0) return 1.0
        var halfX = 0.0; var halfY = 0.0
        for (p in points) {
            val at = GeoMath.project(p, focus)
            halfX = max(halfX, abs(at.x)); halfY = max(halfY, abs(at.y))
        }
        val closest = width / 2 / (radius * sin(MIN_VIEW_KM / EARTH_KM))
        val byWidth = if (halfX > 0) widthShare * width / 2 / (radius * halfX) else closest
        halfY *= cos(Math.toRadians(tiltDeg))
        val byHeight = if (halfY > 0) heightShare * height / 2 / (radius * halfY) else closest
        return max(1.0, min(min(byWidth, byHeight), closest))
    }
}
