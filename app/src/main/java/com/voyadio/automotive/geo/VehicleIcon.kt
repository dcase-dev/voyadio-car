package com.voyadio.automotive.geo

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.PathParser
import androidx.core.graphics.withSave

/**
 * The small car and car ferry that ride the route (design 2026-10-13, Figma "경로 위 탈것"): side views drawn facing
 * west (the car's long hood and the ferry's bridge are on the left), mirrored when heading east. Drawn in the Figma icon units (car 32×18, ferry 40×22) at [UNIT_DP] dp per unit, so the
 * car is 48 dp and the ferry 60 dp wide. Cream body, dark outline and a soft gold glow keep them visible on city lights.
 */
internal class VehicleIcon(private val dp: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(140, 255, 196, 107) // #FFC46B 55%
        maskFilter = BlurMaskFilter(7 * dp, BlurMaskFilter.Blur.NORMAL)
    }

    private val carBody = path("M3 12.5V10Q3 8.2 4.8 7.9L8.8 7.3L12.6 3.3Q13.5 2.4 14.8 2.4H21.4Q22.7 2.4 23.6 3.3L27 7.1L28.9 7.5Q30.3 7.8 30.3 9.3V12.5Q30.3 13.6 29.2 13.6H4.1Q3 13.6 3 12.5Z")
    private val carWindows = path("M13.6 7.2L15.5 4.4H18.3V7.2Z M19.6 7.2V4.4H21.6L24.1 7.2Z")
    private val carTail = path("M28.6 9H30V10.6H28.6Z")
    private val funnel = path("M24.6 2.2H27.2V7.8H24.6Z")
    private val bridge = path("M12.5 7.6V4.6Q12.5 3.8 13.3 3.8H21.5Q22.4 3.8 22.7 4.6L23.6 7.6Z")
    private val deck = path("M8 13V8.6Q8 7.6 9 7.6H27.4Q28.6 7.6 29.3 8.6L32 13Z")
    private val hull = path("M2 13H38L34.2 19.2Q33.6 20.2 32.4 20.2H7.6Q6.4 20.2 5.8 19.2Z")

    /**
     * Draws the vehicle with its anchor at ([x], [y]): the car's wheel axle or the ferry's waterline sits on the
     * route there. A parked car stands [PARKED_DROP_DP] below the point, clear of the destination star.
     */
    fun draw(canvas: Canvas, x: Float, y: Float, ferry: Boolean, east: Boolean, parked: Boolean) {
        val unit = UNIT_DP * dp
        val (w, h, ax, ay) = if (ferry) FERRY else CAR
        val top = y + (if (parked) PARKED_DROP_DP * dp else 0f)
        canvas.drawOval(x - w / 2 * unit, top - ay * unit, x + w / 2 * unit, top + (h - ay) * unit, glow)
        canvas.withSave {
            translate(x, top)
            scale(if (east) -unit else unit, unit)
            translate(-ax, -ay)
            if (ferry) ferry(this) else car(this)
        }
    }

    private fun car(canvas: Canvas) {
        paint(canvas, carBody, CREAM, 1.3f)
        paint(canvas, carWindows, DEEP)
        paint(canvas, carTail, TAIL)
        for (cx in floatArrayOf(9f, 24.5f)) {
            fill.color = DEEP; canvas.drawCircle(cx, 13.6f, 2.9f, fill)
            fill.color = GOLD; canvas.drawCircle(cx, 13.6f, 1.2f, fill)
        }
    }

    private fun ferry(canvas: Canvas) {
        paint(canvas, funnel, GOLD, 1.1f)
        paint(canvas, bridge, CREAM, 1.3f)
        paint(canvas, deck, CREAM, 1.3f)
        paint(canvas, hull, CREAM, 1.3f)
        line.color = BAND; line.strokeWidth = 1.6f
        canvas.drawLine(5.6f, 16.3f, 34.6f, 16.3f, line)
        fill.color = DEEP
        for (i in 0 until 6) canvas.drawCircle(11f + i * 3f, 10.3f, 0.9f, fill)
    }

    /** Fills [shape] with [color] and, when [outline] > 0, outlines it in the dark ink (icon units). */
    private fun paint(canvas: Canvas, shape: android.graphics.Path, color: Int, outline: Float = 0f) {
        fill.color = color
        canvas.drawPath(shape, fill)
        if (outline > 0f) {
            line.color = DEEP; line.strokeWidth = outline
            canvas.drawPath(shape, line)
        }
    }

    private fun path(data: String) = PathParser.createPathFromPathData(data)

    /** Icon box in units: width, height, anchor x, anchor y. */
    private data class Box(val w: Float, val h: Float, val ax: Float, val ay: Float)

    companion object {
        const val UNIT_DP = 1.5f
        const val PARKED_DROP_DP = 24f
        private val CAR = Box(32f, 18f, 16f, 13.6f)
        private val FERRY = Box(40f, 22f, 20f, 16.3f)
        private val CREAM = Color.rgb(244, 232, 208) // #F4E8D0
        private val DEEP = Color.rgb(9, 24, 28) // #09181C
        private val GOLD = Color.rgb(232, 195, 135) // #E8C387
        private val TAIL = Color.rgb(240, 110, 80) // tail light at the back (right end of the west-facing car)
        private val BAND = Color.rgb(91, 184, 255) // #5BB8FF
    }
}
