package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.Choreographer
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.nav.CarMotion
import com.abdulrahman.fakelocation.nav.HeadingSmoother
import com.abdulrahman.fakelocation.nav.NavIcons
import com.abdulrahman.fakelocation.nav.NavProgress
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.RoadInfo
import com.abdulrahman.fakelocation.nav.ZoomPlanner
import com.abdulrahman.fakelocation.trip.TripTimeline
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

/**
 * What the Navigate screen draws on its map: the green road ahead (shrinking as you drive), traffic, the white turn
 * arrow and lane bubble, speed cameras, stop numbers, the destination pin, and the car.
 *
 * The car is drawn on every screen frame while the screen is visible ([start] / [stop]): it carries on along the road
 * at your speed between GPS fixes and blends each new fix in ([CarMotion]), turns smoothly ([HeadingSmoother]), and
 * the zoom glides with speed and upcoming turns ([ZoomPlanner]).
 */
class NavMapLayers(private val map: MapView) : Choreographer.FrameCallback {

    private val context: Context = map.context

    /** The map follows the car (heading up) until it is dragged; recentring turns it back on. */
    var follow = true

    private var drawnRoute: NavRoute? = null
    private var drawnRoadInfo: RoadInfo? = null
    private val cameraMarkers = mutableListOf<Marker>()
    private val stopMarkers = mutableListOf<Marker>()

    private val routeCasing by lazy { line(R.color.route_green_dark, 13f) }
    private val routeLine by lazy { line(R.color.route_green, 9f) }
    private val trafficLines = mutableListOf<Polyline>()
    private val turnShaft by lazy { line(R.color.white, 6f) }
    private val turnHead by lazy { line(R.color.white, 6f).apply { outlinePaint.strokeCap = Paint.Cap.ROUND } }
    private val laneBubble by lazy { marker(Marker.ANCHOR_BOTTOM) }
    private var laneBubbleKey: Any? = null
    private val me by lazy { marker(Marker.ANCHOR_CENTER) }
    private val destination by lazy {
        marker(Marker.ANCHOR_BOTTOM).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.marker_fake)?.mutate()
                ?.apply { setTint(ContextCompat.getColor(context, R.color.ink)) }
        }
    }

    private val motion = CarMotion()
    private val heading = HeadingSmoother()
    private var state: NavSession.State? = null
    private var lastFixId = -1L
    private var running = false
    private var frameQueued = false
    private var lastFrameAt = 0L
    private var lastLineAt = 0L
    private var shownHeading = 0f
    private val iconCache = HashMap<Int, BitmapDrawable>()
    /** Where the remaining (green) route starts: where the car is drawn. */
    private var lineFromAlong = 0.0

    /** The route lines and turn arrow, at the bottom of the overlays. */
    fun attach() {
        map.overlays.addAll(listOf(routeCasing, routeLine, turnShaft, turnHead))
    }

    /** Route, cameras, turn arrow and the car, for the latest navigation state. */
    fun draw(s: NavSession.State) {
        state = s
        drawRoute(s)
        takeFix(s)
        queueFrame()
    }

    /** Recentre: follow the car again. */
    fun recenter() {
        follow = true
        queueFrame()
    }

    /** The screen is visible: draw frames. */
    fun start() {
        running = true
        lastFrameAt = 0L
        queueFrame()
    }

    /** The screen is hidden: no frames (saves battery; navigation itself carries on). */
    fun stop() {
        running = false
        frameQueued = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Numbered dots for the stops on the way. */
    fun setStops(stops: List<Place>) {
        stopMarkers.forEach { map.overlays.remove(it) }
        stopMarkers.clear()
        stops.forEachIndexed { i, place ->
            stopMarkers += marker(Marker.ANCHOR_CENTER).apply {
                position = GeoPoint(place.position.lat, place.position.lon)
                icon = BitmapDrawable(context.resources, NavIcons.numberDot(context, i + 1))
            }
        }
        map.overlays.addAll(stopMarkers)
    }

    private fun drawRoute(s: NavSession.State) {
        val r = s.route
        if (r !== drawnRoute) {
            drawnRoute = r
            lineFromAlong = 0.0
            laneBubbleKey = null
            map.overlays.remove(destination)
            if (r != null) {
                destination.position = GeoPoint(r.points.last().lat, r.points.last().lon)
                map.overlays.add(destination)
                if (!s.navigating) fit(r)
            }
        }
        if (s.roadInfo !== drawnRoadInfo) {
            drawnRoadInfo = s.roadInfo
            cameraMarkers.forEach { map.overlays.remove(it) }
            cameraMarkers.clear()
            if (r != null) s.roadInfo?.camerasAlong?.forEach { along ->
                val p = r.pointAt(along)
                cameraMarkers += marker(Marker.ANCHOR_CENTER).apply {
                    position = GeoPoint(p.lat, p.lon)
                    icon = ContextCompat.getDrawable(context, R.drawable.ic_camera)?.mutate()
                        ?.apply { setTint(ContextCompat.getColor(context, R.color.limit_red)) }
                }
            }
            map.overlays.addAll(cameraMarkers)
        }
        // Only the road still ahead is drawn: while driving it starts under the car (see [doFrame]).
        val p = s.progress
        if (!s.navigating || r == null || p == null || p.offRouteMeters >= 40) lineFromAlong = 0.0
        drawAhead(r, lineFromAlong)
        drawTurn(s, r, p)
    }

    /** The green line and traffic from [from] metres along the route to its end. */
    private fun drawAhead(r: NavRoute?, from: Double) {
        val ahead = r?.let { if (from > 0) it.slice(from, it.length) else it.points }.orEmpty().map { GeoPoint(it.lat, it.lon) }
        routeCasing.setPoints(ahead)
        routeLine.setPoints(ahead)
        drawTraffic(r, from)
    }

    /** Yellow / red over the green where Google reports slow traffic or a jam. */
    private fun drawTraffic(r: NavRoute?, from: Double) {
        val spans = r?.traffic.orEmpty().filter { it.toAlong > from }
        while (trafficLines.size < spans.size) {
            trafficLines += line(R.color.traffic_slow, 9f).also { map.overlays.add(map.overlays.indexOf(routeLine) + 1, it) }
        }
        trafficLines.forEachIndexed { i, line ->
            val span = spans.getOrNull(i)
            if (span == null || r == null) {
                line.setPoints(emptyList())
            } else {
                line.outlinePaint.color = ContextCompat.getColor(context, if (span.level == com.abdulrahman.fakelocation.nav.Traffic.JAM) R.color.traffic_jam else R.color.traffic_slow)
                line.setPoints(r.slice(maxOf(span.fromAlong, from), span.toAlong).map { GeoPoint(it.lat, it.lon) })
            }
        }
    }

    /** A white arrow painted on the road at the next turn, and the lanes to take in a bubble above it. */
    private fun drawTurn(s: NavSession.State, r: NavRoute?, p: NavProgress?) {
        val step = p?.nextStep
        val show = r != null && p != null && step != null && s.navigating && !p.arrived && p.metersToNextStep < 450 &&
            step.maneuver != com.abdulrahman.fakelocation.nav.Maneuver.ARRIVE && step.maneuver != com.abdulrahman.fakelocation.nav.Maneuver.DEPART
        if (!show) {
            turnShaft.setPoints(emptyList())
            turnHead.setPoints(emptyList())
        } else {
            val at = r!!.along[step!!.pointIndex]
            val start = maxOf(at - 30, p!!.alongMeters)
            val end = minOf(at + 22, r.length)
            turnShaft.setPoints(r.slice(start, end).map { GeoPoint(it.lat, it.lon) })
            val tip = r.pointAt(end)
            val back = r.pointAt(end - 6)
            turnHead.setPoints(arrowHead(back, tip).map { GeoPoint(it.lat, it.lon) })
        }
        val lanes = if (show) s.lanesFor(step) else emptyList()
        val wantBubble = lanes.size >= 2 && p != null && p.metersToNextStep < 700
        if (!wantBubble) {
            if (laneBubbleKey != null) map.overlays.remove(laneBubble)
            laneBubbleKey = null
            return
        }
        val key = step to lanes
        if (key != laneBubbleKey) {
            laneBubbleKey = key
            val at = r!!.pointAt(r.along[step!!.pointIndex] - 12)
            laneBubble.position = GeoPoint(at.lat, at.lon)
            laneBubble.icon = BitmapDrawable(context.resources, NavIcons.laneBubble(context, lanes, step.maneuver))
            map.overlays.remove(laneBubble)
            map.overlays.add(laneBubble)
        }
    }

    /** The two short strokes of an arrow head at [tip], pointing away from [back]. */
    private fun arrowHead(back: LatLon, tip: LatLon): List<LatLon> {
        val k = Math.cos(Math.toRadians(tip.lat))
        val dx = (tip.lon - back.lon) * 111_320 * k
        val dy = (tip.lat - back.lat) * 110_540
        val len = Math.hypot(dx, dy).takeIf { it > 0 } ?: return emptyList()
        val ux = dx / len
        val uy = dy / len
        fun at(along: Double, side: Double) = LatLon(
            tip.lat + (uy * along + ux * side) / 110_540,
            tip.lon + (ux * along - uy * side) / (111_320 * k),
        )
        return listOf(at(-9.0, 7.0), tip, at(-9.0, -7.0))
    }

    /** A new position from the GPS (or estimated in a tunnel): hand it to the motion. */
    private fun takeFix(s: NavSession.State) {
        if (s.fixId == lastFixId) return
        lastFixId = s.fixId
        val here = s.real ?: return
        val r = s.route
        val p = s.progress
        val onRoute = s.navigating && r != null && p != null && p.offRouteMeters < 25
        val now = SystemClock.uptimeMillis()
        if (onRoute) motion.onFix(p!!.snapped, r, p.alongMeters, s.speedKmh / 3.6, s.bearing, now)
        else motion.onFix(here, null, null, s.speedKmh / 3.6, s.bearing, now)
    }

    private fun queueFrame() {
        if (!running || frameQueued) return
        frameQueued = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameQueued = false
        if (!running) return
        val s = state ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 16L else (now - lastFrameAt).coerceIn(0L, 200L)
        lastFrameAt = now
        val pose = motion.poseAt(now) ?: return
        val r = s.route
        val along = pose.along
        // On the route, point along the road ahead (steadier than the GPS heading); elsewhere the GPS heading.
        val target = if (along != null && r != null) TripTimeline.bearing(r.pointAt(along - 5), r.pointAt(along + 15)) else s.bearing
        val before = shownHeading
        shownHeading = heading.update(target, now)
        val zoomed = if (s.navigating && follow && s.mode == TravelMode.CAR) glideZoom(s, pose.position, dt) else false
        placeCar(s, pose.position, shownHeading)
        // The green line starts under the car; ten times a second is plenty for that.
        if (s.navigating && r != null && along != null && now - lastLineAt > 100) {
            lastLineAt = now
            lineFromAlong = along
            drawAhead(r, along)
        }
        map.invalidate()
        val turning = abs(HeadingSmoother.difference(before.toDouble(), target.toDouble())) > 0.3
        if (motion.moving(now) || turning || zoomed) queueFrame()
    }

    private fun placeCar(s: NavSession.State, pos: LatLon, bearing: Float) {
        me.position = GeoPoint(pos.lat, pos.lon)
        val map = map
        if (s.navigating && follow) {
            // Heading up, with the car low on the screen so you see more of the road ahead.
            map.setMapCenterOffset(0, (map.height * CAR_LOW).toInt())
            map.mapOrientation = -bearing
            map.controller.setCenter(me.position)
        }
        val onScreen = bearing + map.mapOrientation
        val car = s.navigating && s.mode == TravelMode.CAR
        val key = (if (car) 1000 else 0) + ((((onScreen % 360) + 360) % 360) / 2).toInt()
        val icon = iconCache.getOrPut(key) {
            val bitmap = if (car) NavIcons.car(context, onScreen) else NavIcons.arrow(context, onScreen)
            BitmapDrawable(context.resources, bitmap)
        }
        if (me.icon !== icon) me.icon = icon
        if (map.overlays.lastOrNull() !== me) {
            map.overlays.remove(me)
            map.overlays.add(me)
        }
    }

    /** Glides the zoom toward what the speed and the next turn call for; true while it is still moving. */
    private fun glideZoom(s: NavSession.State, at: LatLon, dt: Long): Boolean {
        val zoom = map.zoomLevelDouble
        val p = s.progress
        val step = p?.nextStep
        val turnAhead = step != null && step.maneuver != com.abdulrahman.fakelocation.nav.Maneuver.DEPART &&
            step.maneuver != com.abdulrahman.fakelocation.nav.Maneuver.STRAIGHT && !p.arrived
        val pixelsPerMeter = 1.0 / TileSystem.GroundResolution(at.lat, zoom)
        // Room above the car: it sits low on the screen, and the turn card covers the top.
        val ahead = map.height * (0.5 + CAR_LOW - 0.22)
        val target = ZoomPlanner.target(s.speedKmh.toDouble(), if (turnAhead) p!!.metersToNextStep else null, zoom, pixelsPerMeter, ahead)
        if (abs(target - zoom) < 0.01) return false
        map.controller.setZoom(ZoomPlanner.ease(zoom, target, dt))
        return true
    }

    fun fit(r: NavRoute) {
        map.post {
            val box = BoundingBox.fromGeoPoints(r.points.map { GeoPoint(it.lat, it.lon) })
            map.mapOrientation = 0f
            map.zoomToBoundingBox(box, true, (64 * context.resources.displayMetrics.density).toInt())
        }
    }

    private fun line(color: Int, widthDp: Float) = Polyline(map).apply {
        outlinePaint.color = ContextCompat.getColor(context, color)
        outlinePaint.strokeWidth = widthDp * context.resources.displayMetrics.density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        setOnClickListener { _, _, _ -> false }
    }

    private fun marker(anchorY: Float) = Marker(map).apply {
        setAnchor(Marker.ANCHOR_CENTER, anchorY)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> true }
    }

    private companion object {
        /** How far below the middle of the map the car sits while driving (share of the map's height). */
        const val CAR_LOW = 0.24
    }
}
