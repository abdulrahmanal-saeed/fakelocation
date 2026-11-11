package com.abdulrahman.fakelocation.ui

import android.os.SystemClock
import android.view.Choreographer
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.nav.CarMotion
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Moves a marker smoothly to each new position (and carries it on at [speedMps] between updates, as the joystick
 * does), on screen frames, instead of jumping. A far jump (a teleport) is shown at once. [follow] keeps the map
 * centred on it.
 */
class MarkerGlide(private val map: MapView, private val marker: Marker) : Choreographer.FrameCallback {

    var follow = false
    private val motion = CarMotion()
    private var queued = false
    private var running = false

    fun moveTo(position: LatLon, speedMps: Double = 0.0, bearing: Float = 0f) {
        motion.onFix(position, null, null, speedMps, bearing, SystemClock.uptimeMillis())
        if (running) queueFrame() else place(position)
    }

    /** Forget the last position: the next one is shown where it is. */
    fun reset() {
        motion.reset()
    }

    fun start() {
        running = true
        queueFrame()
    }

    fun stop() {
        running = false
        queued = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun queueFrame() {
        if (!running || queued) return
        queued = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        queued = false
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val pose = motion.poseAt(now) ?: return
        place(pose.position)
        if (motion.moving(now)) queueFrame()
    }

    private fun place(at: LatLon) {
        val point = GeoPoint(at.lat, at.lon)
        marker.position = point
        if (follow) map.controller.setCenter(point)
        map.invalidate()
    }
}
