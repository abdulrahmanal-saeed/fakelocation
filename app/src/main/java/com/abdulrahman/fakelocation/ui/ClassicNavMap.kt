package com.abdulrahman.fakelocation.ui

import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.NightMap
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/** The Navigate map drawn from map pictures (OpenStreetMap or Google), with osmdroid. */
class ClassicNavMap(private val map: MapView, scope: LifecycleCoroutineScope, start: LatLon) : NavMap {

    private val context = map.context
    private val density = context.resources.displayMetrics.density
    private val layers = NavMapLayers(map)
    private val traffic = TrafficLayer(map, scope)
    private val alternatives = mutableListOf<Polyline>()
    private var shownAlternatives: Pair<List<NavRoute>, NavRoute?>? = null

    init {
        MapStyle.apply(map, scope)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(start.lat, start.lon))
        layers.attach()
    }

    override val view get() = map

    override var follow: Boolean
        get() = layers.follow
        set(value) {
            layers.follow = value
        }

    override fun draw(s: NavSession.State) {
        drawAlternatives(s)
        layers.draw(s)
        map.invalidate()
    }

    /** Before starting: the other ways as grey lines under the chosen one; tap one to take it. */
    private fun drawAlternatives(s: NavSession.State) {
        val options = if (s.navigating) emptyList() else s.routes
        val key = options to s.route
        if (key == shownAlternatives) return
        shownAlternatives = key
        alternatives.forEach { map.overlays.remove(it) }
        alternatives.clear()
        if (options.size < 2) return
        options.forEachIndexed { i, route ->
            if (route === s.route) return@forEachIndexed
            alternatives += Polyline(map).apply {
                outlinePaint.color = ContextCompat.getColor(context, R.color.muted_light)
                outlinePaint.strokeWidth = 8 * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                setPoints(route.points.map { GeoPoint(it.lat, it.lon) })
                setOnClickListener { _, _, _ -> NavSession.chooseRoute(i); true }
            }
        }
        map.overlays.addAll(0, alternatives)
    }

    override fun setStops(stops: List<Place>) = layers.setStops(stops)

    override fun recenter(s: NavSession.State) {
        layers.recenter()
        if (s.navigating) return
        val route = s.route
        val real = s.real
        if (route != null) layers.fit(route) else if (real != null) map.controller.animateTo(GeoPoint(real.lat, real.lon))
    }

    override fun navigationChanged(navigating: Boolean, mode: TravelMode) {
        if (navigating) {
            map.controller.setZoom(if (mode == TravelMode.CAR) 17.5 else 18.5)
            layers.follow = true
        } else {
            map.mapOrientation = 0f
            map.setMapCenterOffset(0, 0)
        }
    }

    override fun setNight(night: Boolean) {
        map.overlayManager.tilesOverlay.setColorFilter(if (night) NightMap.filter else null)
        map.overlayManager.tilesOverlay.loadingBackgroundColor = ContextCompat.getColor(context, if (night) R.color.ink else R.color.ground)
        map.invalidate()
    }

    override fun setTraffic(on: Boolean) = traffic.show(on)

    override fun onResume() {
        map.onResume()
        layers.start()
    }

    override fun onPause() {
        layers.stop()
        traffic.stop()
        map.onPause()
    }
}
