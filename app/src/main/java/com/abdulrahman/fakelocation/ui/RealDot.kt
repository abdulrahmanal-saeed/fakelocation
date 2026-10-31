package com.abdulrahman.fakelocation.ui

import android.location.Location
import android.os.SystemClock
import android.view.Choreographer
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.geo.LocationKalman
import com.abdulrahman.fakelocation.nav.CarMotion
import com.abdulrahman.fakelocation.nav.HeadingSmoother
import org.osmdroid.views.MapView
import kotlin.math.abs

/**
 * The real-position blue dot on the main map: fixes are smoothed ([LocationKalman]), the dot glides between them
 * ([CarMotion]) and shows how accurate the GPS is and which way you face (compass while standing, the way you move
 * while moving). Frames are drawn only while something on it moves.
 */
class RealDot(private val map: MapView) : Choreographer.FrameCallback {

    private val overlay = BlueDotOverlay(map.context)
    private val kalman = LocationKalman(3.0)
    private val motion = CarMotion()
    private val smoother = HeadingSmoother(blendMs = 220.0, maxDegreesPerSecond = 360.0)
    private val compass = Compass(map.context).also { it.onChange = { if (needsTurning()) queueFrame() } }
    private var running = false
    private var queued = false
    private var accuracy = 0f
    private var moveBearing: Float? = null

    val position: LatLon? get() = overlay.position

    fun attach() {
        if (!map.overlays.contains(overlay)) map.overlays.add(0, overlay)
    }

    /** A remembered position (e.g. while the fake location runs): just the dot. */
    fun showStill(at: LatLon) {
        motion.reset()
        kalman.reset()
        overlay.position = at
        overlay.accuracy = 0f
        overlay.heading = null
        map.invalidate()
    }

    fun onFix(location: Location) {
        val now = SystemClock.uptimeMillis()
        val estimate = kalman.update(
            LatLon(location.latitude, location.longitude),
            if (location.hasAccuracy()) location.accuracy.toDouble() else 15.0,
            now,
            if (location.hasSpeed()) location.speed.toDouble() else null,
            if (location.hasBearing() && location.hasSpeed()) location.bearing else null,
        )
        if (estimate.rejected) return
        accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        // Standing still the filter's speed is just noise: keep the dot put.
        val speed = if (estimate.speedMps > 0.7) estimate.speedMps else 0.0
        moveBearing = estimate.bearing?.takeIf { estimate.speedMps > 2.0 }
        motion.onFix(estimate.position, null, null, speed, estimate.bearing ?: 0f, now)
        compass.near = estimate.position
        if (running) queueFrame() else draw(now)
    }

    /** The map is on screen: follow the compass and draw frames. */
    fun start() {
        running = true
        compass.start()
        queueFrame()
    }

    fun stop() {
        running = false
        queued = false
        Choreographer.getInstance().removeFrameCallback(this)
        compass.stop()
        overlay.heading = null
        smoother.reset()
        map.invalidate()
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
        draw(now)
        if (motion.moving(now) || needsTurning()) queueFrame()
    }

    private fun draw(now: Long) {
        val pose = motion.poseAt(now) ?: return
        overlay.position = pose.position
        overlay.accuracy = accuracy
        val target = target()
        overlay.heading = target?.let { smoother.update(it, now) }
        map.invalidate()
    }

    private fun target(): Float? = moveBearing ?: compass.heading

    /** The cone is still catching up with where the phone points. */
    private fun needsTurning(): Boolean {
        val target = target() ?: return false
        val shown = smoother.current ?: return true
        return abs(HeadingSmoother.difference(shown.toDouble(), target.toDouble())) > 1.5
    }
}
