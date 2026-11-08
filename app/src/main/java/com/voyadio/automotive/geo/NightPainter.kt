package com.voyadio.automotive.geo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * City lights and the capital web turned into [Outline]s, ready to draw. Pure: build it off the main thread
 * with [of]. Every fourth light is a bright one; the rest are small.
 */
class NightData internal constructor(internal val small: Outline?, internal val bright: Outline?, internal val web: List<Outline>) {
    val smallCount get() = small?.size ?: 0
    val brightCount get() = bright?.size ?: 0
    val arcCount get() = web.size

    companion object {
        fun of(lights: List<LatLon>, arcs: List<List<LatLon>>): NightData {
            val dim = lights.filterIndexed { i, _ -> i % 4 != 0 }
            val lit = lights.filterIndexed { i, _ -> i % 4 == 0 }
            return NightData(
                dim.takeIf { it.isNotEmpty() }?.let { GlobeGeometry.outline(it) },
                lit.takeIf { it.isNotEmpty() }?.let { GlobeGeometry.outline(it) },
                arcs.filter { it.size >= 2 }.map { GlobeGeometry.outline(it) },
            )
        }
    }
}

/** Night-globe decoration: draws [NightData], only projecting into reused buffers. */
internal class NightPainter(dp: Float) {
    private var small: Outline? = null
    private var bright: Outline? = null
    private var web: List<Outline> = emptyList()
    private var points = FloatArray(0)
    private val webPath = Path()

    private val webPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * dp; color = Color.argb(89, 232, 168, 90) // #E8A85A 35%
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeWidth = 2 * dp; color = Color.argb(140, 255, 196, 107) // #FFC46B 55%
    }
    private val brightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeWidth = 3.5f * dp; color = Color.argb(230, 255, 213, 138) // #FFD58A 90%
    }

    fun set(data: NightData) {
        small = data.small; bright = data.bright; web = data.web
        points = FloatArray(maxOf(data.smallCount, data.brightCount) * 2)
    }

    /** [cull] uses the frame's screen cap; turn it off when the globe is not at its usual height. */
    fun draw(canvas: Canvas, frame: GlobeFrame, cx: Float, cy: Float, r: Float, width: Int, height: Int, cull: Boolean) {
        webPath.reset()
        for (arc in web) {
            if (frame.isHidden(arc) || (cull && frame.isOffScreen(arc))) continue
            var pen = false
            for (i in 0 until arc.size) {
                if (!frame.project(arc, i)) { pen = false; continue }
                val x = cx + (frame.x * r).toFloat(); val y = cy - (frame.y * r).toFloat()
                if (pen) webPath.lineTo(x, y) else { webPath.moveTo(x, y); pen = true }
            }
        }
        canvas.drawPath(webPath, webPaint)
        small?.let { lights(canvas, it, frame, cx, cy, r, width, height, smallPaint) }
        bright?.let { lights(canvas, it, frame, cx, cy, r, width, height, brightPaint) }
    }

    private fun lights(canvas: Canvas, o: Outline, frame: GlobeFrame, cx: Float, cy: Float, r: Float, width: Int, height: Int, paint: Paint) {
        val edge = paint.strokeWidth
        var n = 0
        for (i in 0 until o.size) {
            if (!frame.project(o, i)) continue
            val x = cx + (frame.x * r).toFloat(); val y = cy - (frame.y * r).toFloat()
            if (x < -edge || y < -edge || x > width + edge || y > height + edge) continue
            points[n++] = x; points[n++] = y
        }
        if (n > 0) canvas.drawPoints(points, 0, n, paint)
    }
}
