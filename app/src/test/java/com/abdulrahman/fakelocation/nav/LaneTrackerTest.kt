package com.abdulrahman.fakelocation.nav

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class LaneTrackerTest {

    /**
     * Drives north at [speed] m/s on a [lanes]-lane road for [seconds], feeding 50 Hz gyro and 1 Hz GPS.
     * [lateral] is the sideways position over time (metres, + right); [bias] is a constant gyro error.
     */
    private fun drive(
        tracker: LaneTracker, seconds: Double, lanes: Int, speed: Double = 25.0, start: Long = 0,
        bias: Double = 0.0, noise: Double = 0.0, lateral: (Double) -> Double = { 0.0 },
    ): Long {
        val random = java.util.Random(7)
        val dt = 0.02
        var t = 0.0
        fun headingAt(x: Double): Double {
            val v = (lateral(x + 0.01) - lateral(x - 0.01)) / 0.02
            return Math.toDegrees(atan2(v, speed))
        }
        var lastFix = -1.0
        while (t < seconds) {
            val ms = start + (t * 1000).toLong()
            val rate = (headingAt(t + dt) - headingAt(t)) / dt + bias + random.nextGaussian() * noise
            tracker.onYawRate(ms, rate)
            if (t - lastFix >= 1.0) {
                lastFix = t
                tracker.onFix(ms, speed, (headingAt(t) + random.nextGaussian() * noise * 4 + 360) % 360, 0.0, lanes)
            }
            t += dt
        }
        return start + (seconds * 1000).toLong()
    }

    /** A smooth 3.5 m lane change taking [duration] seconds, starting at [at]. */
    private fun laneChange(at: Double, duration: Double, dir: Int): (Double) -> Double = { x ->
        val u = ((x - at) / duration).coerceIn(0.0, 1.0)
        dir * 3.5 * (u - sin(2 * PI * u) / (2 * PI))
    }

    @Test
    fun rightTurnPutsYouInTheRightLane() {
        val tracker = LaneTracker()
        tracker.onTurn(Maneuver.RIGHT)
        drive(tracker, 3.0, lanes = 4)
        assertEquals(4, tracker.count)
        assertEquals(4, tracker.lane)
    }

    @Test
    fun countsLaneChanges() {
        val tracker = LaneTracker()
        tracker.onTurn(Maneuver.RIGHT)
        var t = drive(tracker, 5.0, lanes = 4)
        t = drive(tracker, 12.0, lanes = 4, start = t, lateral = laneChange(2.0, 4.0, -1))
        assertEquals(3, tracker.lane)
        t = drive(tracker, 12.0, lanes = 4, start = t, lateral = laneChange(2.0, 5.0, -1))
        assertEquals(2, tracker.lane)
        t = drive(tracker, 12.0, lanes = 4, start = t, lateral = laneChange(2.0, 4.0, 1))
        assertEquals(3, tracker.lane)
        // A slow, lazy lane change (7 s) at city speed still counts.
        drive(tracker, 14.0, lanes = 4, speed = 14.0, start = t, lateral = laneChange(2.0, 7.0, 1))
        assertEquals(4, tracker.lane)
    }

    @Test
    fun steadyDriftIsNotALaneChange() {
        val tracker = LaneTracker()
        tracker.onTurn(Maneuver.LEFT)
        drive(tracker, 90.0, lanes = 3, bias = 0.05)
        assertEquals(1, tracker.lane)
    }

    @Test
    fun noisySensorsDontInventLaneChanges() {
        val tracker = LaneTracker()
        tracker.onTurn(Maneuver.RIGHT)
        drive(tracker, 180.0, lanes = 4, bias = 0.1, noise = 0.5)
        assertEquals(4, tracker.lane)
    }

    @Test
    fun cameraAndSensorsCountOnce() {
        val tracker = LaneTracker()
        tracker.onTurn(Maneuver.RIGHT)
        var t = drive(tracker, 3.0, lanes = 3)
        t = drive(tracker, 9.0, lanes = 3, start = t, lateral = laneChange(1.0, 4.0, -1))
        tracker.onCameraLaneChange(t - 4000, -1)
        assertEquals(2, tracker.lane)
        tracker.onLeftEdgeSeen()
        assertEquals(1, tracker.lane)
    }

    @Test
    fun laneKeptFromTheRightWhenRoadWidens() {
        val tracker = LaneTracker()
        tracker.onTurn(Maneuver.RIGHT)
        val t = drive(tracker, 3.0, lanes = 3)
        drive(tracker, 3.0, lanes = 5, start = t)
        assertEquals(5, tracker.lane)
    }
}
