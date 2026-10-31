package com.abdulrahman.fakelocation.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.nav.NavIcons
import com.abdulrahman.fakelocation.nav.NavProgress
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.RoadInfo
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

/**
 * What the Navigate screen draws on its map: the green road ahead (shrinking as you drive), traffic, the white turn
 * arrow and lane bubble, speed cameras, stop numbers, the destination pin, and the car gliding between GPS fixes.
 */
class NavMapLayers(private val map: MapView) {

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

    // The car glides between GPS fixes instead of jumping once a second.
    private var shownAt: LatLon? = null
    private var shownBearing = 0f
    private var lastFixAt = 0L
    private var lastTarget: LatLon? = null
    private var carAnimator: ValueAnimator? = null
    /** Where the remaining (green) route starts: the car's position at the previous fix. */
    private var lineFromAlong = 0.0

    /** The route lines and turn arrow, at the bottom of the overlays. */
    fun attach() {
        map.overlays.addAll(listOf(routeCasing, routeLine, turnShaft, turnHead))
    }

    /** Route, cameras, turn arrow and the car, for the latest navigation state. */
    fun draw(s: NavSession.State) {
        drawRoute(s)
        drawMe(s)
    }

    /** Recentre: follow the car again and place it at the next fix without gliding from far away. */
    fun recenter() {
        follow = true
        lastTarget = null
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
        // Only the road still ahead is drawn: it starts where the car is and shrinks as you drive.
        val p = s.progress
        val from = if (r != null && s.navigating && p != null && p.offRouteMeters < 40) lineFromAlong.coerceAtMost(p.alongMeters) else 0.0
        if (r != null && s.navigating && p != null) lineFromAlong = p.alongMeters
        val ahead = r?.let { if (from > 0) it.slice(from, it.length) else it.points }.orEmpty().map { GeoPoint(it.lat, it.lon) }
        routeCasing.setPoints(ahead)
        routeLine.setPoints(ahead)
        drawTraffic(r, from)
        drawTurn(s, r, p)
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

    private fun drawMe(s: NavSession.State) {
        val here = s.real ?: return
        val r = s.route
        val p = s.progress
        val onRoute = s.navigating && r != null && p != null && p.offRouteMeters < 25
        val target = if (onRoute) p!!.snapped else here
        // On the route, point along the road ahead (steadier than the GPS heading).
        val bearing = if (onRoute) {
            com.abdulrahman.fakelocation.trip.TripTimeline.bearing(r!!.pointAt(p!!.alongMeters), r.pointAt(p.alongMeters + 20))
        } else s.bearing
        if (target == lastTarget) return
        lastTarget = target
        val now = System.currentTimeMillis()
        val duration = (now - lastFixAt).coerceIn(250, 1200)
        lastFixAt = now
        val startAt = shownAt ?: target
        val startBearing = shownBearing
        val turn = ((bearing - startBearing + 540) % 360) - 180
        carAnimator?.cancel()
        if (s.navigating && s.mode == TravelMode.CAR) adjustZoom(s.speedKmh)
        carAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = if (startAt == target) 0 else duration
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val pos = LatLon(startAt.lat + (target.lat - startAt.lat) * f, startAt.lon + (target.lon - startAt.lon) * f)
                placeCar(s, pos, (startBearing + turn * f + 360) % 360)
            }
            start()
        }
    }

    private fun placeCar(s: NavSession.State, pos: LatLon, bearing: Float) {
        shownAt = pos
        shownBearing = bearing
        me.position = GeoPoint(pos.lat, pos.lon)
        val map = map
        if (s.navigating && follow) {
            // Heading up, with the car low on the screen so you see more of the road ahead.
            map.setMapCenterOffset(0, (map.height * 0.24).toInt())
            map.mapOrientation = -bearing
            map.controller.setCenter(me.position)
        }
        val onScreen = bearing + map.mapOrientation
        val bitmap = if (s.navigating && s.mode == TravelMode.CAR) NavIcons.car(context, onScreen) else NavIcons.arrow(context, onScreen)
        me.icon = BitmapDrawable(context.resources, bitmap)
        map.overlays.remove(me)
        map.overlays.add(me)
        map.invalidate()
    }

    /** Closer in town, further out on fast roads, changing gently. */
    private fun adjustZoom(speedKmh: Int) {
        val target = when {
            speedKmh < 35 -> 17.8
            speedKmh < 70 -> 17.2
            speedKmh < 100 -> 16.6
            else -> 16.1
        }
        val zoom = map.zoomLevelDouble
        if (abs(target - zoom) > 0.05) map.controller.setZoom(zoom + (target - zoom).coerceIn(-0.2, 0.2))
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
}
