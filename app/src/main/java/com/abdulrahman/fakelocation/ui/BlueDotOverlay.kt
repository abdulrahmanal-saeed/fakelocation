package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Where you really are, like the blue dot in Google Maps: a pale circle as wide as the GPS is unsure, a cone the way
 * the phone points (or the way you move), and the dot itself. Positions are set from outside on every frame.
 */
class BlueDotOverlay(context: Context) : Overlay() {

    var position: LatLon? = null
    /** Metres; 0 hides the circle. */
    var accuracy = 0f
    /** Degrees clockwise from north; null hides the cone. */
    var heading: Float? = null

    private val density = context.resources.displayMetrics.density
    private val blue = ContextCompat.getColor(context, R.color.real)
    private val white = ContextCompat.getColor(context, R.color.white)
    private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(blue, 0x26) }
    private val circleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(blue, 0x60)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coneLength = 56f * density
    private val cone = Path().apply {
        // Pointing up (north on an unrotated map), ±32° wide; rotated when drawn.
        moveTo(0f, 0f)
        val half = Math.toRadians(32.0)
        lineTo((coneLength * Math.sin(-half)).toFloat(), (-coneLength * Math.cos(half)).toFloat())
        arcTo(-coneLength, -coneLength, coneLength, coneLength, -90f - 32f, 64f, false)
        close()
    }
    private val point = Point()

    init {
        conePaint.shader = RadialGradient(
            0f, 0f, coneLength,
            intArrayOf(ColorUtils.setAlphaComponent(blue, 0x99), ColorUtils.setAlphaComponent(blue, 0x00)),
            floatArrayOf(0.15f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val at = position ?: return
        projection.toPixels(GeoPoint(at.lat, at.lon), point)
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        val dotRadius = 7f * density
        if (accuracy > 0) {
            val radius = (accuracy / TileSystem.GroundResolution(at.lat, projection.zoomLevel)).toFloat()
            if (radius > dotRadius * 1.6f) {
                canvas.drawCircle(x, y, radius, circleFill)
                canvas.drawCircle(x, y, radius, circleEdge)
            }
        }
        heading?.let { h ->
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(h + projection.orientation)
            canvas.drawPath(cone, conePaint)
            canvas.restore()
        }
        canvas.drawCircle(x, y + 0.8f * density, dotRadius + 3.2f * density, shadow)
        canvas.drawCircle(x, y, dotRadius + 2.5f * density, ring)
        canvas.drawCircle(x, y, dotRadius, dot)
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent?, mapView: MapView?) = false
}
