package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LifecycleCoroutineScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.geo.google.GoogleApi
import com.abdulrahman.fakelocation.geo.google.GoogleTiles
import com.abdulrahman.fakelocation.nav.NavIcons
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.Traffic
import com.abdulrahman.fakelocation.nav.ZoomPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs

/**
 * The Navigate map drawn from vector data with MapLibre (OpenFreeMap's free map, no key): it turns and zooms smoothly
 * on the graphics chip, street names stay upright, the view tilts while driving, and there's a proper night map.
 * Everything the screen shows on it is a GeoJSON source and a layer; the car is moved on every screen frame.
 *
 * [onFailed] is called if the map can't load (no connection on first use, a graphics problem), so the screen can go
 * back to the classic map.
 */
class VectorNavMap(
    context: Context,
    private val scope: LifecycleCoroutineScope,
    start: LatLon,
    savedState: Bundle?,
    private val onFailed: (String) -> Unit,
) : NavMap, Choreographer.FrameCallback {

    private val context: Context = context
    private val density = context.resources.displayMetrics.density
    private val mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var night = false
    /** The night setting of the style loaded (or loading) now; null before the first. */
    private var styleNight: Boolean? = null
    private var trafficOn = false
    private var trafficUrl: String? = null
    private var trafficJob: Job? = null
    private var state: NavSession.State? = null
    private var stops: List<Place> = emptyList()
    private val car = CarTracker()
    private var running = false
    private var frameQueued = false
    private var lastFrameAt = 0L
    private var lastLineAt = 0L
    private var laneKey: Any? = null
    private var fittedRoute: NavRoute? = null
    private var navigatingShown = false

    override var follow = true

    init {
        MapLibre.getInstance(context.applicationContext)
        mapView = MapView(context)
        mapView.onCreate(savedState)
        mapView.addOnDidFailLoadingMapListener { message ->
            Log.w(TAG, "vector map failed: $message")
            onFailed(message)
        }
        // The same room on the phone as the classic map's saved areas.
        OfflineManager.getInstance(context.applicationContext).setMaximumAmbientCacheSize(
            Prefs(context).mapCacheMb * 1024L * 1024L,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) {
                    Log.w(TAG, "cache size: $message")
                }
            },
        )
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(15.0).build()
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
            // Before starting: tap a grey line to take that way.
            m.addOnMapClickListener { point ->
                val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(point), ALTERNATIVES).firstOrNull()
                val index = hit?.getNumberProperty("i")?.toInt()
                if (index != null) NavSession.chooseRoute(index)
                index != null
            }
            loadStyle()
        }
    }

    override val view: View get() = mapView

    // region Style and layers

    private fun loadStyle() {
        val m = map ?: return
        style = null
        styleNight = night
        m.setStyle(Style.Builder().fromUri(if (night) NIGHT_STYLE else DAY_STYLE)) { loaded ->
            style = loaded
            addLayers(loaded)
            drawAll()
        }
    }

    /** Our sources and layers, under the map's own labels so street names stay readable over the route. */
    private fun addLayers(st: Style) {
        st.addImage(IMG_CAR, NavIcons.car(context, 0f))
        st.addImage(IMG_ARROW, NavIcons.arrow(context, 0f))
        ContextCompat.getDrawable(context, R.drawable.ic_camera)?.mutate()?.let {
            it.setTint(ContextCompat.getColor(context, R.color.limit_red))
            st.addImage(IMG_CAMERA, it.toBitmap((22 * density).toInt(), (22 * density).toInt()))
        }
        ContextCompat.getDrawable(context, R.drawable.marker_fake)?.mutate()?.let {
            it.setTint(ContextCompat.getColor(context, R.color.ink))
            st.addImage(IMG_DESTINATION, it.toBitmap())
        }
        for (id in listOf(ALTERNATIVES, TRAFFIC_SLOW, TRAFFIC_JAM, ROUTE, TURN, CAMERAS, STOPS, DESTINATION, LANES, ME)) {
            st.addSource(GeoJsonSource(id))
        }
        val labels = st.layers.firstOrNull { it is SymbolLayer }?.id
        fun below(layer: Layer) = if (labels != null) st.addLayerBelow(layer, labels) else st.addLayer(layer)
        below(line(ALTERNATIVES, ALTERNATIVES, R.color.muted_light, 8f))
        below(line(ROUTE_CASING, ROUTE, R.color.route_green_dark, 13f))
        below(line(ROUTE, ROUTE, R.color.route_green, 9f))
        below(line(TRAFFIC_SLOW, TRAFFIC_SLOW, R.color.traffic_slow, 9f))
        below(line(TRAFFIC_JAM, TRAFFIC_JAM, R.color.traffic_jam, 9f))
        below(line(TURN, TURN, R.color.white, 6f))
        // Markers on top of everything, flat on the tilted map like the road itself.
        st.addLayer(symbol(CAMERAS, Expression.literal(IMG_CAMERA)))
        st.addLayer(symbol(STOPS, Expression.get("icon")))
        st.addLayer(symbol(DESTINATION, Expression.literal(IMG_DESTINATION)).withProperties(PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)))
        st.addLayer(symbol(LANES, Expression.literal(IMG_LANES)).withProperties(PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)))
        st.addLayer(
            symbol(ME, Expression.get("icon")).withProperties(
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            )
        )
        laneKey = null
        trafficUrl?.let { addTraffic(st, it) }
    }

    private fun line(id: String, source: String, color: Int, widthDp: Float) = LineLayer(id, source).withProperties(
        PropertyFactory.lineColor(ContextCompat.getColor(context, color)),
        PropertyFactory.lineWidth(widthDp),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )

    private fun symbol(id: String, image: Expression) = SymbolLayer(id, id).withProperties(
        PropertyFactory.iconImage(image),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    )

    private fun source(id: String): GeoJsonSource? = style?.getSourceAs(id)

    private fun lineOf(points: List<LatLon>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })

    private fun pointOf(p: LatLon) = Point.fromLngLat(p.lon, p.lat)

    // endregion

    // region Drawing

    override fun draw(s: NavSession.State) {
        state = s
        if (s.navigating != navigatingShown) navigationChanged(s.navigating, s.mode)
        drawAll()
        car.takeFix(s, SystemClock.uptimeMillis())
        queueFrame()
    }

    private fun drawAll() {
        val s = state ?: return
        if (style == null) return
        val r = s.route
        // Other ways (before starting), each with its number for tapping.
        val options = if (s.navigating) emptyList() else s.routes
        source(ALTERNATIVES)?.setGeoJson(FeatureCollection.fromFeatures(options.withIndex().filter { it.value !== r && options.size > 1 }.map { (i, route) ->
            Feature.fromGeometry(lineOf(route.points)).apply { addNumberProperty("i", i) }
        }))
        val p = s.progress
        if (!s.navigating || r == null || p == null || p.offRouteMeters >= 40) drawAhead(r, 0.0)
        // Cameras, the destination and stops.
        val cameras = if (r != null) s.roadInfo?.camerasAlong.orEmpty().map { Feature.fromGeometry(pointOf(r.pointAt(it))) } else emptyList()
        source(CAMERAS)?.setGeoJson(FeatureCollection.fromFeatures(cameras))
        source(DESTINATION)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(r?.points?.lastOrNull()?.let { Feature.fromGeometry(pointOf(it)) })))
        drawStops()
        // The white arrow at the next turn and the lane bubble.
        val arrow = NavShapes.turnArrow(s)
        source(TURN)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(arrow?.first, arrow?.second).filter { it.size >= 2 }.map { Feature.fromGeometry(lineOf(it)) }))
        val bubble = NavShapes.laneBubble(s)
        if (bubble == null) {
            source(LANES)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            laneKey = null
        } else {
            val key = bubble.step to bubble.lanes
            if (key != laneKey) {
                laneKey = key
                style?.addImage(IMG_LANES, NavIcons.laneBubble(context, bubble.lanes, bubble.step.maneuver))
            }
            source(LANES)?.setGeoJson(Feature.fromGeometry(pointOf(bubble.at)))
        }
        // Before starting, show the whole route once it's in.
        if (!s.navigating && r != null && r !== fittedRoute) {
            fittedRoute = r
            fit(r)
        }
    }

    /** The green line and traffic from [from] metres along the route to its end. */
    private fun drawAhead(r: NavRoute?, from: Double) {
        val ahead = r?.let { if (from > 0) it.slice(from, it.length) else it.points }.orEmpty()
        source(ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(ahead.takeIf { it.size >= 2 }?.let { Feature.fromGeometry(lineOf(it)) })))
        val spans = r?.traffic.orEmpty().filter { it.toAlong > from }
        fun spansOf(level: Traffic) = FeatureCollection.fromFeatures(spans.filter { it.level == level }.mapNotNull { span ->
            r!!.slice(maxOf(span.fromAlong, from), span.toAlong).takeIf { it.size >= 2 }?.let { Feature.fromGeometry(lineOf(it)) }
        })
        source(TRAFFIC_SLOW)?.setGeoJson(spansOf(Traffic.SLOW))
        source(TRAFFIC_JAM)?.setGeoJson(spansOf(Traffic.JAM))
    }

    override fun setStops(stops: List<Place>) {
        this.stops = stops
        drawStops()
    }

    private fun drawStops() {
        val st = style ?: return
        val features = stops.mapIndexed { i, place ->
            val image = "stop-${i + 1}"
            if (st.getImage(image) == null) st.addImage(image, NavIcons.numberDot(context, i + 1))
            Feature.fromGeometry(pointOf(place.position)).apply { addStringProperty("icon", image) }
        }
        source(STOPS)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun fit(r: NavRoute) {
        val m = map ?: return
        if (r.points.size < 2) return
        val bounds = LatLngBounds.Builder().includes(r.points.map { LatLng(it.lat, it.lon) }).build()
        val pad = (64 * density).toInt()
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, pad * 3, pad, pad * 4), 600)
    }

    // endregion

    // region The car, on every frame

    private fun queueFrame() {
        if (!running || frameQueued) return
        frameQueued = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameQueued = false
        if (!running) return
        val s = state ?: return
        val m = map ?: return
        if (style == null) {
            queueFrame()
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 16L else (now - lastFrameAt).coerceIn(0L, 200L)
        lastFrameAt = now
        val frame = car.frame(s, now) ?: return
        val carIcon = s.navigating && s.mode == TravelMode.CAR
        source(ME)?.setGeoJson(Feature.fromGeometry(pointOf(frame.position)).apply {
            addStringProperty("icon", if (carIcon) IMG_CAR else IMG_ARROW)
            addNumberProperty("bearing", frame.heading)
        })
        var zooming = false
        if (s.navigating && follow) {
            val zoom = m.cameraPosition.zoom
            val target = if (s.mode == TravelMode.CAR) {
                // The planner thinks in the classic map's zoom levels, which are one more than MapLibre's.
                val metersPerDp = m.projection.getMetersPerPixelAtLatitude(frame.position.lat)
                val aheadDp = mapView.height / density * (0.5 + CAR_LOW - 0.22)
                ZoomPlanner.target(s.speedKmh.toDouble(), ZoomPlanner.metersToTurn(s.progress), zoom + 1, 1 / metersPerDp, aheadDp) - 1
            } else WALK_ZOOM
            zooming = abs(target - zoom) > 0.01
            val glided = if (zooming) ZoomPlanner.ease(zoom, target, dt) else zoom
            // Heading up and tilted, with the car low on the screen so more of the road ahead shows.
            m.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(frame.position.lat, frame.position.lon))
                    .bearing(frame.heading.toDouble())
                    .tilt(if (s.mode == TravelMode.CAR) TILT else 0.0)
                    .zoom(glided)
                    .padding(0.0, mapView.height * CAR_LOW * 2, 0.0, 0.0)
                    .build()
            ))
        }
        val r = s.route
        val along = frame.along
        // The green line starts under the car. A long route is a lot to send to the map, so a few times a second:
        // at driving zoom the car covers more road than it moves in that time.
        if (s.navigating && r != null && along != null && now - lastLineAt > 400) {
            lastLineAt = now
            drawAhead(r, along)
        }
        if (frame.moving || zooming) queueFrame()
    }

    // endregion

    override fun recenter(s: NavSession.State) {
        follow = true
        if (s.navigating) {
            queueFrame()
            return
        }
        val route = s.route
        val real = s.real
        val m = map ?: return
        if (route != null) fit(route) else if (real != null) m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(real.lat, real.lon)), 500)
    }

    override fun navigationChanged(navigating: Boolean, mode: TravelMode) {
        navigatingShown = navigating
        val m = map ?: return
        if (navigating) {
            follow = true
            m.moveCamera(CameraUpdateFactory.zoomTo(if (mode == TravelMode.CAR) CAR_ZOOM else WALK_ZOOM))
            queueFrame()
        } else {
            fittedRoute = null
            m.easeCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
            ), 400)
        }
    }

    override fun setNight(night: Boolean) {
        this.night = night
        if (map != null && night != styleNight) loadStyle()
    }

    /** Google's traffic tiles over the vector map (they refresh themselves when Google says they're out of date). */
    override fun setTraffic(on: Boolean) {
        trafficOn = on
        if (!on) {
            trafficJob?.cancel()
            trafficUrl = null
            style?.let { st ->
                st.removeLayer(TRAFFIC_TILES)
                st.removeSource(TRAFFIC_TILES)
            }
            return
        }
        if (trafficUrl != null || trafficJob?.isActive == true) return
        val app = context.applicationContext
        trafficJob = scope.launch {
            val url = runCatching {
                withContext(Dispatchers.IO) {
                    val api = GoogleApi(app)
                    "https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session=${GoogleTiles.trafficSession(app, api)}&key=${api.key}"
                }
            }.onFailure { Log.w(TAG, "traffic layer didn't start", it) }.getOrNull() ?: return@launch
            if (!trafficOn) return@launch
            trafficUrl = url
            style?.let { addTraffic(it, url) }
        }
    }

    private fun addTraffic(st: Style, url: String) {
        if (st.getSource(TRAFFIC_TILES) != null) return
        st.addSource(RasterSource(TRAFFIC_TILES, TileSet("2.1.0", url), 256))
        // Over the streets, under our route.
        st.addLayerBelow(RasterLayer(TRAFFIC_TILES, TRAFFIC_TILES), ALTERNATIVES)
    }

    // region Lifecycle

    override fun onStart() = mapView.onStart()

    override fun onResume() {
        mapView.onResume()
        running = true
        lastFrameAt = 0L
        queueFrame()
    }

    override fun onPause() {
        running = false
        frameQueued = false
        Choreographer.getInstance().removeFrameCallback(this)
        mapView.onPause()
    }

    override fun onStop() = mapView.onStop()

    override fun onDestroy() {
        trafficJob?.cancel()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) = mapView.onSaveInstanceState(out)

    override fun onLowMemory() = mapView.onLowMemory()

    // endregion

    private companion object {
        const val TAG = "VectorNavMap"
        const val DAY_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        const val NIGHT_STYLE = "https://tiles.openfreemap.org/styles/dark"
        const val ALTERNATIVES = "fl-alternatives"
        const val ROUTE = "fl-route"
        const val ROUTE_CASING = "fl-route-casing"
        const val TRAFFIC_SLOW = "fl-traffic-slow"
        const val TRAFFIC_JAM = "fl-traffic-jam"
        const val TRAFFIC_TILES = "fl-traffic-tiles"
        const val TURN = "fl-turn"
        const val CAMERAS = "fl-cameras"
        const val STOPS = "fl-stops"
        const val DESTINATION = "fl-destination"
        const val LANES = "fl-lanes"
        const val ME = "fl-me"
        const val IMG_CAR = "fl-car"
        const val IMG_ARROW = "fl-arrow"
        const val IMG_CAMERA = "fl-camera"
        const val IMG_DESTINATION = "fl-destination"
        const val IMG_LANES = "fl-lanes"
        /** How far below the middle the car sits (share of the map's height), as on the classic map. */
        const val CAR_LOW = 0.24
        const val TILT = 45.0
        /** MapLibre zoom levels: one less than the classic map's for the same view. */
        const val CAR_ZOOM = 16.5
        const val WALK_ZOOM = 17.5
    }
}
