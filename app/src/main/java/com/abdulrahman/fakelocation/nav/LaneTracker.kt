package com.abdulrahman.fakelocation.nav

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin

/**
 * Which lane you're in (1 = leftmost), without HD maps:
 *  - after a turn you're usually on the edge lane on that side (right turn → rightmost lane, left/U-turn → leftmost);
 *  - lane changes are seen as a short sideways drift: heading (gyroscope, anchored to GPS) against the road's
 *    direction, integrated with speed into a sideways distance, ~3.5 m per lane;
 *  - the camera (when on) reports lane changes and the yellow edge line, which beat the sensors;
 *  - a very accurate GPS fix (dual-frequency) can nudge it too.
 * It is an estimate: good on straight multi-lane roads, unsure right after joining a road.
 */
class LaneTracker {

    enum class Source { TURN, SENSORS, CAMERA, GPS }

    /** Lanes in our direction (0 = unknown). */
    var count = 0
        private set
    /** Our lane, 1 = leftmost (0 = unknown). */
    var lane = 0
        private set
    var source = Source.TURN
        private set
    /** Sideways drift since the last lane decision, metres (+ = to the right). For debugging and tests. */
    var lateral = 0.0
        private set

    private var heading = Double.NaN
    private var roadBearing = Double.NaN
    private var speed = 0.0
    private var bias = 0.0
    private var lastSampleAt = 0L
    private var lastGyroAt = 0L
    private var lastChangeAt = 0L
    private var lastChangeDir = 0
    private var lastChangeSource = Source.SENSORS
    private var pendingSide = 0 // -1 left edge, +1 right edge, once the lane count is known
    private var hintLane = 0
    private var hintVotes = 0

    /** A GPS fix: speed, its heading (if moving), the road's direction here and how many lanes it has. */
    fun onFix(t: Long, speedMps: Double, gpsBearing: Double?, road: Double, laneCount: Int) {
        speed = speedMps
        roadBearing = road
        if (gpsBearing != null && speedMps > 4) {
            heading = if (heading.isNaN()) gpsBearing else norm(heading + 0.15 * wrap(gpsBearing - heading))
        }
        // Without a gyroscope, the GPS heading is all we have (once a second).
        if (t - lastGyroAt > 2000 && gpsBearing != null) {
            heading = gpsBearing
            integrate(t)
        }
        setCount(laneCount)
    }

    /** Turn rate from the gyroscope, degrees per second, clockwise positive (like compass headings). */
    fun onYawRate(t: Long, degPerSec: Double) {
        if (!heading.isNaN() && lastGyroAt > 0) {
            val dt = ((t - lastGyroAt) / 1000.0).coerceIn(0.0, 0.2)
            heading = norm(heading + degPerSec * dt)
        }
        lastGyroAt = t
        integrate(t)
    }

    /** Just passed a turn: you're now on the edge lane on that side. */
    fun onTurn(m: Maneuver) {
        val side = when (m) {
            Maneuver.RIGHT, Maneuver.SHARP_RIGHT, Maneuver.SLIGHT_RIGHT, Maneuver.ROUNDABOUT, Maneuver.DEPART -> 1
            Maneuver.LEFT, Maneuver.SHARP_LEFT, Maneuver.SLIGHT_LEFT, Maneuver.UTURN -> -1
            else -> 0
        }
        lateral = 0.0
        bias = 0.0
        if (side == 0) return
        pendingSide = side
        applyPending()
    }

    /** The camera saw us cross a lane line (+1 to the right, -1 to the left). */
    fun onCameraLaneChange(t: Long, direction: Int) = change(t, direction, Source.CAMERA)

    /** The camera sees the solid yellow line just left of us: we're in the leftmost lane. */
    fun onLeftEdgeSeen() {
        if (count > 0 && lane != 1) {
            lane = 1
            source = Source.CAMERA
        }
    }

    /** A precise GPS position suggests [guess]; taken only when it keeps saying so. */
    fun onPositionHint(guess: Int) {
        if (count <= 0 || guess !in 1..count) return
        if (guess == hintLane) hintVotes++ else {
            hintLane = guess
            hintVotes = 1
        }
        if ((lane == 0 || hintVotes >= 8) && guess != lane) {
            lane = guess
            source = Source.GPS
        }
    }

    fun reset() {
        count = 0
        lane = 0
        lateral = 0.0
        bias = 0.0
        heading = Double.NaN
        pendingSide = 0
    }

    private fun setCount(n: Int) {
        if (n <= 0 || n == count) {
            applyPending()
            return
        }
        // Keep our place counted from the right edge: lanes usually start or end on the left in the middle of roads.
        val fromRight = if (lane > 0 && count > 0) count - lane else -1
        count = n
        if (fromRight >= 0) lane = (count - fromRight).coerceIn(1, count)
        applyPending()
    }

    private fun applyPending() {
        if (pendingSide == 0 || count <= 0) return
        lane = if (pendingSide > 0) count else 1
        source = Source.TURN
        pendingSide = 0
    }

    private fun integrate(t: Long) {
        val dt = if (lastSampleAt == 0L) 0.0 else ((t - lastSampleAt) / 1000.0).coerceIn(0.0, 1.5)
        lastSampleAt = t
        if (heading.isNaN() || roadBearing.isNaN() || dt == 0.0) return
        val angle = wrap(heading - roadBearing)
        if (speed < 4 || abs(angle) > 20) {
            // Turning or crawling: no lane change to measure.
            lateral = 0.0
            return
        }
        // Remove the slow offset (road shape vs. real lanes, sensor bias); keep the short drift of a lane change.
        // Learn the offset only while driving steadily, so a slow lane change isn't absorbed into it.
        if (abs(angle - bias) < 1.0) bias += (angle - bias) * (dt / 15.0).coerceAtMost(1.0)
        val a = Math.toRadians(angle - bias)
        lateral = lateral * exp(-dt / 20.0) + speed * sin(a) * dt
        if (abs(lateral) > 2.4) {
            val dir = sign(lateral).toInt()
            lateral -= dir * 3.5
            change(t, dir, Source.SENSORS)
        }
    }

    private fun change(t: Long, dir: Int, from: Source) {
        // The camera and the sensors may both see the same lane change: count it once.
        if (dir == lastChangeDir && from != lastChangeSource && t - lastChangeAt < 3000) {
            if (from == Source.CAMERA) source = Source.CAMERA
            return
        }
        lastChangeAt = t
        lastChangeDir = dir
        lastChangeSource = from
        if (from == Source.CAMERA) lateral = 0.0
        if (lane > 0 && count > 0) {
            lane = (lane + dir).coerceIn(1, count)
            source = from
        }
    }

    private fun wrap(d: Double) = ((d + 540) % 360) - 180
    private fun norm(d: Double) = (d % 360 + 360) % 360
}
