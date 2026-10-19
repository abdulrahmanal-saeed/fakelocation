package com.abdulrahman.fakelocation.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.geo.google.GoogleTiles
import com.abdulrahman.fakelocation.nav.NavIcons
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.Traffic
import com.abdulrahman.fakelocation.trip.TripTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.ReusableBitmapDrawable
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Draws the map on the car screen: map tiles (OpenStreetMap or Google), the route, the car and the speed signs.
 * Android Auto gives navigation apps a bare surface, so the drawing is done here by hand.
 */
class CarMapRenderer(private val carContext: CarContext, private val owner: LifecycleOwner) : SurfaceCallback, DefaultLifecycleObserver {

    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var dpi = 160
    private var visibleArea: Rect? = null
    private var zoomOffset = 0.0
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private val tiles = MapTileProviderBasic(carContext.applicationContext, TileSourceFactory.MAPNIK)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        owner.lifecycle.addObserver(this)
        tiles.tileRequestCompleteHandlers.add(Handler(Looper.getMainLooper()) {
            requestRender()
            true
        })
        tiles.ensureCapacity(80)
        val prefs = Prefs(carContext)
        if (prefs.hasGoogleKey && prefs.mapProvider.isGoogle) {
            owner.lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { GoogleTiles.tileSource(carContext, prefs.mapProvider) } }
                    .onSuccess { tiles.tileSource = it; requestRender() }
            }
        }
        owner.lifecycleScope.launch { NavSession.state.collect { requestRender() } }
    }

    fun zoomIn() {
        zoomOffset = (zoomOffset + 1).coerceAtMost(3.0)
        requestRender()
    }

    fun zoomOut() {
        zoomOffset = (zoomOffset - 1).coerceAtLeast(-4.0)
        requestRender()
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface
        width = container.width
        height = container.height
        dpi = container.dpi
        requestRender()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        requestRender()
    }

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        zoomOffset = (zoomOffset + ln(scaleFactor.toDouble()) / ln(2.0)).coerceIn(-4.0, 3.0)
        requestRender()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        tiles.detach()
    }

    fun requestRender() {
        if (pending) return
        pending = true
        handler.post {
            pending = false
            render()
        }
    }

    private fun render() {
        val s = surface ?: return
        if (!s.isValid || width == 0) return
        val canvas = runCatching { s.lockCanvas(null) }.getOrNull() ?: return
        try {
            draw(canvas, NavSession.state.value)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private fun draw(canvas: Canvas, state: NavSession.State) {
        canvas.drawColor(0xFFE9EDF1.toInt())
        val area = visibleArea ?: Rect(0, 0, width, height)
        val scale = (dpi / 160f).coerceIn(1f, 2.5f)
        val route = state.route
        val navigating = state.navigating
        val real = state.real

        // Where to look, how close, and which way up.
        val center: LatLon
        var zoom: Double
        val rotation: Float
        val anchorX = area.exactCenterX()
        val anchorY: Float
        when {
            navigating && real != null -> {
                center = state.progress?.takeIf { it.offRouteMeters < 25 }?.snapped ?: real
                zoom = (if (state.mode == TravelMode.CAR) 17.0 else 18.0) + zoomOffset
                rotation = -heading(state)
                anchorY = area.top + area.height() * 0.68f
            }
            route != null -> {
                val minLat = route.points.minOf { it.lat }
                val maxLat = route.points.maxOf { it.lat }
                val minLon = route.points.minOf { it.lon }
                val maxLon = route.points.maxOf { it.lon }
                center = LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2)
                val worldW = (maxLon - minLon) / 360.0
                val worldH = (mercY(minLat) - mercY(maxLat)).coerceAtLeast(1e-9)
                val fitW = ln((area.width() * 0.8) / (256 * scale) / worldW.coerceAtLeast(1e-9)) / ln(2.0)
                val fitH = ln((area.height() * 0.8) / (256 * scale) / worldH) / ln(2.0)
                zoom = min(fitW, fitH).coerceIn(3.0, 17.0) + zoomOffset
                rotation = 0f
                anchorY = area.exactCenterY()
            }
            else -> {
                center = real ?: LatLon.HOME_REGION
                zoom = 15.0 + zoomOffset
                rotation = 0f
                anchorY = area.exactCenterY()
            }
        }
        zoom = zoom.coerceIn(2.0, 19.0)
        val z = floor(zoom).toInt()
        val n = 1 shl z
        val tile = 256 * scale * 2.0.pow(zoom - z).toFloat()
        val world = tile * n
        val cx = ((center.lon + 180) / 360 * world).toFloat()
        val cy = (mercY(center.lat) * world).toFloat()
        fun sx(p: LatLon) = anchorX + ((p.lon + 180) / 360 * world).toFloat() - cx
        fun sy(p: LatLon) = anchorY + (mercY(p.lat) * world).toFloat() - cy

        canvas.save()
        canvas.rotate(rotation, anchorX, anchorY)

        // Map tiles.
        val radius = hypot(width.toFloat(), height.toFloat())
        val x0 = floor((cx - radius) / tile).toInt()
        val x1 = floor((cx + radius) / tile).toInt()
        val y0 = max(0, floor((cy - radius) / tile).toInt())
        val y1 = min(n - 1, floor((cy + radius) / tile).toInt())
        for (ty in y0..y1) for (tx in x0..x1) {
            val drawable = tiles.getMapTile(MapTileIndex.getTileIndex(z, ((tx % n) + n) % n, ty)) ?: continue
            val left = (anchorX + tx * tile - cx).toInt()
            val top = (anchorY + ty * tile - cy).toInt()
            drawable.setBounds(left, top, (left + tile + 1).toInt(), (top + tile + 1).toInt())
            if (drawable is ReusableBitmapDrawable) {
                drawable.beginUsingDrawable()
                try {
                    if (drawable.isBitmapValid) drawable.draw(canvas)
                } finally {
                    drawable.finishUsingDrawable()
                }
            } else {
                drawable.draw(canvas)
            }
        }

        // Route: only the road still ahead (dark edge, green line), with yellow / red where traffic is slow.
        if (route != null) {
            val from = state.progress?.takeIf { navigating && it.offRouteMeters < 40 }?.alongMeters ?: 0.0
            val ahead = if (from > 0) route.slice(from, route.length) else route.points
            path.reset()
            ahead.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p), sy(p)) else path.lineTo(sx(p), sy(p)) }
            stroke(ContextCompat.getColor(carContext, R.color.route_green_dark), 13 * scale)
            canvas.drawPath(path, paint)
            stroke(ContextCompat.getColor(carContext, R.color.route_green), 9 * scale)
            canvas.drawPath(path, paint)
            for (span in route.traffic) {
                if (span.toAlong <= from) continue
                path.reset()
                route.slice(maxOf(span.fromAlong, from), span.toAlong).forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p), sy(p)) else path.lineTo(sx(p), sy(p)) }
                stroke(ContextCompat.getColor(carContext, if (span.level == Traffic.JAM) R.color.traffic_jam else R.color.traffic_slow), 9 * scale)
                canvas.drawPath(path, paint)
            }
            state.roadInfo?.camerasAlong?.forEach { along ->
                val p = route.points[route.along.indexOfFirst { it >= along }.coerceAtLeast(0)]
                fill(Color.WHITE)
                canvas.drawCircle(sx(p), sy(p), 9 * scale, paint)
                fill(ContextCompat.getColor(carContext, R.color.limit_red))
                canvas.drawCircle(sx(p), sy(p), 6 * scale, paint)
            }
            val end = route.points.last()
            fill(Color.WHITE)
            canvas.drawCircle(sx(end), sy(end), 11 * scale, paint)
            fill(ContextCompat.getColor(carContext, R.color.ink))
            canvas.drawCircle(sx(end), sy(end), 8 * scale, paint)
        }
        canvas.restore()

        // You: a car while driving, a blue arrow otherwise.
        if (real != null) {
            val shown = if (navigating) center else real
            val (px, py) = rotate(sx(shown), sy(shown), anchorX, anchorY, rotation)
            val onScreen = (if (navigating) heading(state) else state.bearing) + rotation
            val bitmap = if (navigating && state.mode == TravelMode.CAR) NavIcons.car(carContext, onScreen, scale) else NavIcons.arrow(carContext, onScreen, scale)
            canvas.drawBitmap(bitmap, px - bitmap.width / 2f, py - bitmap.height / 2f, null)
        }

        // Your speed and the limit, bottom left.
        if (navigating && state.mode == TravelMode.CAR) {
            val r = 26 * scale
            val x = area.left + r + 16 * scale
            val y = area.bottom - r - 16 * scale
            sign(canvas, x, y, r, state.speedKmh.toString(), if (state.speeding) ContextCompat.getColor(carContext, R.color.limit_red) else 0xFF0F1B2D.toInt(), null, scale)
            if (state.limitKmh > 0) sign(canvas, x + 2 * r + 10 * scale, y, r, state.limitKmh.toString(), 0xFF0F1B2D.toInt(), ContextCompat.getColor(carContext, R.color.limit_red), scale)
        }
    }

    private fun sign(canvas: Canvas, x: Float, y: Float, r: Float, text: String, textColor: Int, ring: Int?, scale: Float) {
        fill(Color.WHITE)
        canvas.drawCircle(x, y, r, paint)
        if (ring != null) {
            stroke(ring, 5 * scale)
            canvas.drawCircle(x, y, r - 2.5f * scale, paint)
        }
        fill(textColor)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 0.85f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(text, x, y - (paint.descent() + paint.ascent()) / 2, paint)
    }

    /** On the route, the direction of the road ahead (steadier than the GPS heading). */
    private fun heading(state: NavSession.State): Float {
        val route = state.route
        val p = state.progress?.takeIf { it.offRouteMeters < 25 } ?: return state.bearing
        if (route == null) return state.bearing
        return TripTimeline.bearing(route.pointAt(p.alongMeters), route.pointAt(p.alongMeters + 20))
    }

    private fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun rotate(x: Float, y: Float, ax: Float, ay: Float, degrees: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees.toDouble())
        val dx = x - ax
        val dy = y - ay
        return (ax + dx * cos(r) - dy * kotlin.math.sin(r)).toFloat() to (ay + dx * kotlin.math.sin(r) + dy * cos(r)).toFloat()
    }

    /** Web-Mercator y in 0..1 (top to bottom). */
    private fun mercY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }
}
