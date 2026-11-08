package com.voyadio.automotive.geo

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.withTranslation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen night globe: horizon glow, dark land with golden borders, city lights and the capital web,
 * favorite glow and a glowing journey route. The camera follows [GlobeDirector] (about 15 frames a second).
 * A short route is held still and zoomed in until it fills the screen ([GlobeZoom], 2026-10-15).
 */
class GlobeView(context: Context) : View(context) {
    var atlas: WorldAtlas? = null
        set(value) { field = value; geometry = value?.let { GlobeGeometry.of(it.outlines) }; invalidate() }
    var glow: Map<String, Int> = emptyMap()
        set(value) { if (field != value) { field = value; invalidate() } }

    private class LegShape(val ferry: Boolean, val arc: Outline)

    private val director = GlobeDirector()
    private var playing = false
    private var aimedPath: List<LatLon>? = null
    private var aim: GlobeDirector.Aim? = null // what the last glide aimed at: small moves are measured from here
    private var geometry: GlobeGeometry? = null
    private var origin: Outline? = null
    // Camp screen (2026-10-15): a still globe on the station's country — drawn once per change, never animated.
    private var still = false
    private var fromAt: LatLon? = null
    private var toAt: LatLon? = null
    /**
     * The part of this view (pixels) that no text or button covers. A zoomed route is fitted into it and centred
     * there instead of on the whole screen, so its ends never hide behind the menu. Null = the whole view.
     */
    var clear: RectF? = null
        set(value) {
            if (field == value) return
            field = value
            zoomTowards(targetZoom())
            invalidate()
        }

    /** The station's country, glowing gold on the still camp globe. */
    var highlight: String? = null
        set(value) { if (field != value) { field = value; if (still) invalidate() } }
    private var destination: Outline? = null

    /** The navigation's destination (the road's radio, 2026-10-21): a small sea-blue flag beside the gold star. */
    var tripEnd: LatLon? = null
        set(value) { if (field != value) { field = value; tripFlag = value?.let(::point); invalidate() } }
    private var tripFlag: Outline? = null

    /** Today's voyage (2026-10-22): the capitals of the countries met on the road — small gold stars once parked. */
    var voyage: List<LatLon> = emptyList()
        set(value) { if (field != value) { field = value; voyageStars = value.map(::point); invalidate() } }
    private var voyageStars: List<Outline> = emptyList()
    private var legs: List<LegShape> = emptyList()
    private var lastFrame = 0L
    private var layout: NightLayout? = null
    private var shiftY = Float.NaN // where the ocean gradient was last placed (globe center height, zoom)
    private var shiftX = Float.NaN
    private var shiftZoom = 1.0
    private val oceanShift = Matrix()
    private var hold: LatLon? = null // short route: the camera stays here and the globe zooms ([GlobeZoom])
    private var holdPoints: List<LatLon> = emptyList()
    private var zoomFrom = 1.0
    private var zoomTo = 1.0
    private var zoomNow = 1.0
    private var zoomMs = GlobeDirector.TRANSITION_MS

    private val dp = resources.displayMetrics.density
    private val night = NightPainter(dp)
    private val nightFrame = GlobeFrame()
    private val tick = Runnable { invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val ocean = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val air = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = pen(Color.argb(217, 159, 214, 255), 1.5f) // #9FD6FF 85%
    private val borderPaint = pen(Color.argb(153, 232, 168, 90), 1f) // #E8A85A 60%
    private val routeHalo = pen(Color.argb(46, 255, 181, 77), 12f) // #FFB54D 18%
    private val routeMid = pen(Color.argb(115, 255, 196, 107), 5f) // #FFC46B 45%
    private val routeCore = pen(Color.rgb(255, 243, 214), 2f) // #FFF3D6
    private val shape = Path()
    private val borders = Path()
    private val landRoute = Path()
    private val ferryRoute = Path()
    private val dash = DashPathEffect(floatArrayOf(14 * dp, 10 * dp), 0f)
    private val ferryPaint = pen(SEA, 3f).apply { pathEffect = dash }
    private val campRoute = pen(Color.rgb(232, 195, 135), 2.5f).apply { pathEffect = DashPathEffect(floatArrayOf(10 * dp, 9 * dp), 0f) }
    private val campPath = Path()
    private val star = starPath(13 * dp)
    private val little = starPath(6 * dp)
    private val pennant = pennantPath(dp)
    private val pole = pen(SEA, 2f)
    private val vehicle = VehicleIcon(dp)
    private var ox = 0f
    private var oy = 0f
    private var scale = 0f

    private val airAlpha = IntArray(AIR_RINGS) { (255 * 0.42 / (it + 1)).toInt() }
    private val glowAlpha = intArrayOf(0, 55, 100, 160, 220)
    private val space = Color.rgb(1, 4, 6) // #010406
    private val land = Color.rgb(26, 37, 38) // #1A2526
    private val gold = Color.rgb(232, 195, 135)
    private val cream = Color.rgb(244, 232, 208)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /**
     * The journey from [from] (the car) to [to] on [journey]. A new [channel] always glides to it in 2 seconds,
     * even within the same country; for the same channel, the car moving under 100 km keeps the lap going.
     */
    fun showJourney(from: LatLon?, to: LatLon?, journey: Route?, channel: String? = null) {
        fromAt = from; toAt = to
        origin = from?.let(::point)
        destination = to?.let(::point)
        val arcs = journey?.legs.orEmpty().map { leg -> leg to lane(leg) }
        legs = arcs.map { (leg, arc) -> LegShape(leg.kind == LegKind.FERRY, GlobeGeometry.outline(arc)) }
        // One flag per segment of the joined path, so the camera's vehicle knows car from ferry.
        val (points, ferry) = GlobeDirector.join(arcs.map { (leg, arc) -> (leg.kind == LegKind.FERRY) to arc })
        val path = points.takeIf { it.isNotEmpty() }
        hold = if (from != null && to != null) GlobeZoom.hold(from, to, points) else null
        holdPoints = listOfNotNull(from, to) + points
        // Right around here: two pins, no drive.
        val drive = if (from != null && to != null && GlobeZoom.isLocal(from, to)) null else path
        val next = GlobeDirector.Aim(from, to, path != null, channel)
        if (GlobeDirector.needsRetarget(aim, next)) {
            director.retarget(drive, from ?: director.center, ferry, hold)
            aim = next
        } else if (path != aimedPath) director.replace(drive, from ?: director.center, ferry, hold)
        aimedPath = path
        zoomTowards(targetZoom())
        invalidate()
    }

    /** How close the current route wants the globe; 1 = the whole globe. */
    private fun targetZoom(): Double {
        val l = layout ?: return 1.0
        val at = hold ?: return 1.0
        val area = fitArea()
        if (area != null) return GlobeZoom.zoom(at, holdPoints, area.width().toDouble(), area.height().toDouble(),
            l.radius, l.tiltDeg, GlobeZoom.CLEAR_SHARE, GlobeZoom.CLEAR_SHARE)
        // Camp screen: the route stays in the dark band around STILL_Y, clear of the header row above.
        val heightShare = if (still) 2 * (STILL_Y - STILL_TOP) else GlobeZoom.HEIGHT_SHARE
        return GlobeZoom.zoom(at, holdPoints, width.toDouble(), height.toDouble(), l.radius, l.tiltDeg,
            GlobeZoom.WIDTH_SHARE, heightShare)
    }

    /** The free area a zoomed route goes into; the camp screen keeps its own layout (STILL_Y). */
    private fun fitArea(): RectF? = if (still) null else clear

    /** Starts a glide to [zoom] (same 2 s as the camera) unless it is already the target. */
    private fun zoomTowards(zoom: Double) {
        if (abs(ln(zoom / zoomTo)) < 0.01) return
        zoomFrom = zoomNow; zoomTo = zoom; zoomMs = 0L
    }

    /**
     * Camp screen: stop turning and hold the station's country (or the campsite with nothing playing) a little
     * above the middle, in the empty space between the clock and the station name. Off resumes the turning globe.
     */
    fun still(on: Boolean) {
        if (still == on) return
        still = on; lastFrame = 0L
        zoomTowards(targetZoom())
        invalidate()
    }

    /**
     * Camp screen only: the held globe sways slowly ([CampSway]) at a low frame rate so the screen reads as alive.
     * Off under the screen-off cover and while driving (then nothing is drawn at all).
     */
    var sway = false
        set(value) { if (field == value) return; field = value; lastFrame = 0L; invalidate() }
    private var swayMs = 0L

    /** Whether a station is playing: the camera laps in 60 s while playing, 3 min while paused. */
    fun setPlaying(playing: Boolean) { this.playing = playing }

    /** City lights and the capital web; build [data] off the main thread with [NightData.of]. */
    fun setNight(data: NightData) {
        night.set(data)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val l = NightLayout.of(w.toDouble(), h.toDouble(), margin = 16.0 * dp)
        layout = l
        if (l == null) return
        shiftY = Float.NaN; shiftX = Float.NaN
        zoomTo = targetZoom(); zoomFrom = zoomTo; zoomNow = zoomTo; zoomMs = GlobeDirector.TRANSITION_MS
        val r = l.radius.toFloat()
        ocean.shader = RadialGradient(l.cx.toFloat(), (l.cy - l.radius * .55).toFloat(), max(r * 1.1f, 1f),
            Color.rgb(10, 26, 32), Color.rgb(2, 7, 10), Shader.TileMode.CLAMP) // #0A1A20 → #02070A
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate() else removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        removeCallbacks(tick)
        // The vsync time of this frame, so the next one can be booked against the display beat.
        val now = drawingTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0L else (now - lastFrame).coerceIn(0L, MAX_STEP_MS)
        // The sway runs on real time at its own ~7 fps; a long gap (screen hidden) resumes without a jump.
        val swayStep = if (lastFrame == 0L) 0L else (now - lastFrame).coerceIn(0L, SWAY_MAX_STEP_MS)
        lastFrame = now
        val held = hold ?: toAt ?: fromAt ?: director.center
        if (still && sway) swayMs += swayStep
        val camera = when {
            !still -> director.advance(dt, playing)
            sway -> CampSway.at(held, swayMs, zoomNow)
            else -> held
        }
        val l = layout ?: return
        zoomMs = (zoomMs + dt).coerceAtMost(GlobeDirector.TRANSITION_MS)
        zoomNow = if (still || zoomMs >= GlobeDirector.TRANSITION_MS) zoomTo else {
            val t = zoomMs.toDouble() / GlobeDirector.TRANSITION_MS
            exp(ln(zoomFrom) + (ln(zoomTo) - ln(zoomFrom)) * t * t * (3 - 2 * t))
        }
        // Still camp globe: the camera point sits higher (STILL_Y) so the station's star clears the text below.
        val raise = if (still) ((NightLayout.CAMERA_Y - STILL_Y) * height).toFloat() else 0f
        val lift = (l.centerY(camera.lat) - l.cy).toFloat() - raise
        // Zoom about the camera point: it stays where it is on screen while the globe grows around it.
        val viewLat = l.viewLat(camera.lat)
        val off = Math.toRadians(camera.lat - viewLat) // how far north of the projection center the camera is
        val baseY = l.cy + lift
        var cameraX = l.cx
        var cameraY = baseY - l.radius * sin(off)
        // The car rides above the free area's middle even on the whole globe (2026-10-21: the road ahead hid behind
        // the menu); zoomed in, the camera point also slides down to the area's centre, fully past 1.25×.
        val area = fitArea()
        if (area != null) {
            val f = ((zoomNow - 1) * 4).coerceIn(0.0, 1.0)
            cameraX = area.centerX().toDouble(); cameraY += f * (area.centerY() - cameraY)
        }
        // The globe centre sits radius·sin(off) below the camera point, growing with the zoom.
        ox = cameraX.toFloat(); oy = (cameraY + zoomNow * l.radius * sin(off)).toFloat(); scale = (zoomNow * l.radius).toFloat()
        if (oy != shiftY || ox != shiftX || zoomNow != shiftZoom) {
            shiftY = oy; shiftX = ox; shiftZoom = zoomNow
            oceanShift.setScale(zoomNow.toFloat(), zoomNow.toFloat(), l.cx.toFloat(), l.cy.toFloat())
            oceanShift.postTranslate(ox - l.cx.toFloat(), oy - l.cy.toFloat())
            ocean.shader?.setLocalMatrix(oceanShift)
        }
        val frame = nightFrame
        frame.aim(viewLat, camera.lon)
        // Skip what cannot reach the screen: the layout's cap at the usual size and height, a small cap
        // around the camera point when zoomed; neither holds for an unzoomed globe moved up or down.
        // A globe slid sideways into the free area measures its reach from the camera point too.
        val zoomed = zoomNow > 1.001
        val shifted = cameraX != l.cx
        if (zoomed || shifted) frame.screenCap(sin(off), cos(off), zoomedReach(cameraX, cameraY, off))
        else frame.screenCap(l.capNorth, l.capToward, l.capRadius)
        val cull = zoomed || shifted || (lift == 0f && raise == 0f)
        canvas.drawColor(space)
        for (i in 0 until AIR_RINGS) {
            air.color = Color.argb(airAlpha[i], 91, 184, 255) // #5BB8FF
            air.strokeWidth = (2 + i * 3) * dp
            canvas.drawCircle(ox, oy, scale + i * 3 * dp, air)
        }
        canvas.drawCircle(ox, oy, scale, ocean)
        geometry?.let { drawLand(canvas, it, frame, cull) }
        night.draw(canvas, frame, ox, oy, scale, width, height, cull)
        canvas.drawCircle(ox, oy, scale, rim)
        if (still) drawCampRoute(canvas, frame) else drawRoute(canvas, frame)
        for (at in voyageStars) if (frame.project(at, 0)) littleStar(canvas, sx(frame), sy(frame))
        origin?.let { if (frame.project(it, 0)) dot(canvas, sx(frame), sy(frame), cream, 6 * dp) }
        destination?.let { if (frame.project(it, 0)) destinationStar(canvas, sx(frame), sy(frame)) }
        tripFlag?.let { if (frame.project(it, 0)) flag(canvas, sx(frame), sy(frame)) }
        if (!still) director.vehicle()?.let { v ->
            if (frame.project(point(v.at), 0)) vehicle.draw(canvas, sx(frame), sy(frame), v.ferry, v.east, v.parked)
        }
        // Book the next frame 58 ms after this vsync: the redraw then lands on the 4th 60 Hz beat (66.7 ms),
        // not the 5th, even after a slow draw.
        // The swaying camp globe books about 7 frames a second (battery): its drift is under 0.1° a frame.
        val book = when { !still -> FRAME_BOOK_MS; sway -> SWAY_FRAME_MS; else -> return }
        if (isShown) postDelayed(tick, (book - (SystemClock.uptimeMillis() - now)).coerceAtLeast(1L))
    }

    /**
     * Angular radius (radians) around the camera point that can reach the screen at the current [scale].
     * North–south distances near the camera shrink by cos([off]) on screen, so divide by it to stay generous.
     */
    private fun zoomedReach(cameraX: Double, cameraY: Double, off: Double): Double {
        val margin = 16 * dp
        val far = hypot(max(cameraX, width - cameraX) + margin, max(cameraY, height - cameraY) + margin)
        val ratio = far / scale / max(cos(off), 0.3)
        return if (ratio >= 1) PI else (asin(ratio) + REACH_SLACK).coerceAtMost(PI)
    }

    /** Pass 1 dark land, pass 2 glowing countries (so enclaves still glow), then every border in one path. */
    private fun drawLand(canvas: Canvas, world: GlobeGeometry, frame: GlobeFrame, cullScreen: Boolean) {
        borders.reset()
        for (pass in 0..1) {
            val glowing = pass == 1
            for (outline in world.outlines) {
                // Zoomed in, the station's whole country fills the camp screen: keep its glow calm.
                val level = (if (still && outline.code == highlight) (if (zoomNow > 1.001) 2 else 4) else glow[outline.code] ?: 0).coerceIn(0, 4)
                if ((level > 0) != glowing || frame.isHidden(outline)) continue
                if (cullScreen && frame.isOffScreen(outline)) continue
                if (!traceRing(outline, frame)) continue
                fill.color = if (level == 0) land else Color.argb(glowAlpha[level], 232, 195, 135)
                canvas.drawPath(shape, fill)
            }
        }
        canvas.drawPath(borders, borderPaint)
    }

    /**
     * Fills [shape] with the ring, far-side points pushed onto the rim so a country cut by the edge
     * still fills (and stays inside the disc); adds the visible stretches to [borders], pen lifted on the far side.
     */
    private fun traceRing(outline: Outline, frame: GlobeFrame): Boolean {
        shape.reset()
        var visible = 0; var pen = false
        for (i in 0 until outline.size) {
            val front = frame.project(outline, i)
            if (front) {
                visible++
                val x = sx(frame); val y = sy(frame)
                if (pen) borders.lineTo(x, y) else { borders.moveTo(x, y); pen = true }
            } else pen = false
            frame.pushToRim()
            if (i == 0) shape.moveTo(sx(frame), sy(frame)) else shape.lineTo(sx(frame), sy(frame))
        }
        shape.close()
        return visible > 0
    }

    /** Adds the front-facing stretches of [outline] to [path], pen lifted on the far side. */
    private fun trace(outline: Outline, frame: GlobeFrame, path: Path) {
        var pen = false
        for (i in 0 until outline.size) {
            if (!frame.project(outline, i)) { pen = false; continue }
            if (pen) path.lineTo(sx(frame), sy(frame)) else { path.moveTo(sx(frame), sy(frame)); pen = true }
        }
    }

    /** Land legs glow in three passes (wide amber, gold, cream core); ferries are dashed (the moving ferry shows the boat). */
    private fun drawRoute(canvas: Canvas, frame: GlobeFrame) {
        if (legs.isEmpty()) return
        landRoute.reset(); ferryRoute.reset()
        for (leg in legs) trace(leg.arc, frame, if (leg.ferry) ferryRoute else landRoute)
        canvas.drawPath(landRoute, routeHalo)
        canvas.drawPath(landRoute, routeMid)
        canvas.drawPath(landRoute, routeCore)
        canvas.drawPath(ferryRoute, ferryPaint)
    }

    /** Camp screen: the whole road from the campsite to the station's capital as one calm gold dotted line. */
    private fun drawCampRoute(canvas: Canvas, frame: GlobeFrame) {
        if (legs.isEmpty()) return
        campPath.reset()
        for (leg in legs) trace(leg.arc, frame, campPath)
        canvas.drawPath(campPath, campRoute)
    }

    /**
     * The leg as great-circle arcs through its [Leg.path] — a ferry's sea lane turns around the land.
     * A road path is smoothed first ([RouteSmooth]) so the line, the car and the camera glide round its corners.
     */
    private fun lane(leg: Leg): List<LatLon> {
        if (leg.path.size <= 2) return GeoMath.arc(leg.from, leg.to, max(8, (leg.km / 150).toInt()))
        val path = if (leg.kind == LegKind.LAND) RouteSmooth.smooth(leg.path, RouteSmooth.toleranceFor(leg.km)) else leg.path
        return path.zipWithNext().flatMapIndexed { i, (a, b) ->
            val arc = GeoMath.arc(a, b, max(2, (GeoMath.distanceKm(a, b) / 150).toInt()))
            if (i == 0) arc else arc.drop(1)
        }
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        fill.color = halo(color)
        canvas.drawCircle(x, y, size * 2.2f, fill)
        fill.color = color
        canvas.drawCircle(x, y, size, fill)
    }

    private fun destinationStar(canvas: Canvas, x: Float, y: Float) {
        fill.color = halo(gold)
        canvas.drawCircle(x, y, 11 * dp * 2.2f, fill)
        fill.color = gold
        canvas.withTranslation(x, y) { drawPath(star, fill) }
    }

    /** A country met on today's voyage: a small gold star in a soft halo. */
    private fun littleStar(canvas: Canvas, x: Float, y: Float) {
        fill.color = halo(gold)
        canvas.drawCircle(x, y, 9 * dp, fill)
        fill.color = gold
        canvas.withTranslation(x, y) { drawPath(little, fill) }
    }

    /** A pennant on a pole, its foot on the spot. */
    private fun flag(canvas: Canvas, x: Float, y: Float) {
        fill.color = halo(SEA)
        canvas.drawCircle(x, y, 6 * dp, fill)
        canvas.drawLine(x, y, x, y - 24 * dp, pole)
        fill.color = SEA
        canvas.withTranslation(x, y) { drawPath(pennant, fill) }
    }

    /** A round-capped stroke paint [width] dp wide. */
    private fun pen(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = width * dp; this.color = color
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun halo(color: Int) = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
    private fun sx(frame: GlobeFrame) = ox + (frame.x * scale).toFloat()
    private fun sy(frame: GlobeFrame) = oy - (frame.y * scale).toFloat()

    companion object {
        /** Where the still camp globe holds the station (fraction of the screen height from the top). */
        private const val STILL_Y = 0.30
        private const val STILL_TOP = 0.14 // the camp header row ends about here (fraction of the height)
        private const val REACH_SLACK = 0.03 // radians
        private const val FRAME_BOOK_MS = 58L // ~15 fps on a 60 Hz display
        private const val MAX_STEP_MS = 100L
        private const val SWAY_FRAME_MS = 141L // ~7 fps: lands on the 9th 60 Hz beat
        private const val SWAY_MAX_STEP_MS = 1_000L
        private const val AIR_RINGS = 9
        private val SEA = Color.rgb(159, 214, 255) // #9FD6FF

        private fun point(p: LatLon) = GlobeGeometry.outline(doubleArrayOf(p.lat, p.lon))

        /** The flag's cloth: a triangle off the pole's top, the pole's foot at (0, 0). */
        private fun pennantPath(dp: Float) = Path().apply {
            moveTo(0f, -24 * dp); lineTo(16 * dp, -19 * dp); lineTo(0f, -14 * dp); close()
        }

        /** Five-point star around (0, 0), point up. */
        private fun starPath(outer: Float) = Path().apply {
            for (i in 0 until 10) {
                val radius = if (i % 2 == 0) outer else outer * .42f
                val angle = -PI / 2 + i * PI / 5
                val x = (radius * cos(angle)).toFloat(); val y = (radius * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
}
