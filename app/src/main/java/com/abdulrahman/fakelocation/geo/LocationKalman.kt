package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.nav.CarMotion
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smooths GPS fixes: a Kalman filter that assumes you keep going at about the same speed (changing it by at most
 * [acceleration] m/s² or so), trusting each fix as much as its reported accuracy says. Small jitter is soaked up and a
 * fix that lands far from where you can be (a bad reflection between buildings) is ignored, unless the next ones
 * agree with it, which means the jump was real. Works in metres around the first fix; times in ms.
 */
class LocationKalman(private val acceleration: Double = CAR_ACCELERATION) {

    /** The smoothed position and how sure it is (metres), with the speed and heading it worked out. */
    data class Estimate(val position: LatLon, val accuracy: Double, val speedMps: Double, val bearing: Float?, val rejected: Boolean)

    private class Axis {
        var p = 0.0
        var v = 0.0
        var pp = 0.0
        var pv = 0.0
        var vv = 0.0

        fun reset(position: Double, variance: Double, velocity: Double, velocityVariance: Double) {
            p = position
            v = velocity
            pp = variance
            pv = 0.0
            vv = velocityVariance
        }

        /** Moves on [dt] seconds; with the GPS's velocity now ([measured]), along the average of then and now. */
        fun predict(dt: Double, q: Double, measured: Double?) {
            p += (if (measured != null) (v + measured) / 2 else v) * dt
            val dt2 = dt * dt
            pp += 2 * dt * pv + dt2 * vv + q * dt2 * dt2 / 4
            pv += dt * vv + q * dt2 * dt / 2
            vv += q * dt2
        }

        /** Innovation variance of a position fix with noise [r]. */
        fun positionS(r: Double) = pp + r

        fun updatePosition(z: Double, r: Double) {
            val s = pp + r
            val k0 = pp / s
            val k1 = pv / s
            val y = z - p
            p += k0 * y
            v += k1 * y
            val newPp = (1 - k0) * pp
            val newPv = (1 - k0) * pv
            vv -= k1 * pv
            pp = newPp
            pv = newPv
        }

        fun updateVelocity(z: Double, r: Double) {
            val s = vv + r
            val k0 = pv / s
            val k1 = vv / s
            val y = z - v
            p += k0 * y
            v += k1 * y
            val newPp = pp - k0 * pv
            val newPv = pv - k0 * vv
            vv -= k1 * vv
            pp = newPp
            pv = newPv
        }
    }

    private var origin: LatLon? = null
    private var at = 0L
    private val east = Axis()
    private val north = Axis()
    private var rejectedInRow = 0

    fun reset() {
        origin = null
        rejectedInRow = 0
    }

    /**
     * A new fix [position] with [accuracy] metres at [now] ms; [speedMps] and [bearing] when the GPS gave them (they
     * are measured from the signal's Doppler shift, so they're better than what positions alone would say).
     */
    fun update(position: LatLon, accuracy: Double, now: Long, speedMps: Double? = null, bearing: Float? = null): Estimate {
        val acc = accuracy.takeIf { it.isFinite() && it > 0 }?.coerceAtLeast(1.0) ?: 10.0
        val r = acc * acc
        val velocity = if (speedMps != null && bearing != null && speedMps.isFinite()) {
            val rad = Math.toRadians(bearing.toDouble())
            speedMps * sin(rad) to speedMps * cos(rad)
        } else null
        val o = origin
        val dt = (now - at) / 1000.0
        if (o == null || dt > RESET_AFTER_S || dt < 0) {
            start(position, r, velocity, now)
            return estimate(false)
        }
        at = now
        val q = acceleration * acceleration
        east.predict(dt, q, velocity?.first)
        north.predict(dt, q, velocity?.second)
        val (x, y) = CarMotion.metres(o, position)
        // How unlikely is this fix given where we expected to be? Far out (5 sigma, and tens of metres: not just a
        // sharp turn) is a glitch, unless it repeats.
        val dx = x - east.p
        val dy = y - north.p
        val d2 = dx * dx / east.positionS(r) + dy * dy / north.positionS(r)
        if (d2 > OUTLIER_SIGMA * OUTLIER_SIGMA && hypot(dx, dy) > OUTLIER_METERS + acc) {
            rejectedInRow++
            if (rejectedInRow < ACCEPT_AFTER) return estimate(true)
            start(position, r, velocity, now)
            return estimate(false)
        }
        rejectedInRow = 0
        east.updatePosition(x, r)
        north.updatePosition(y, r)
        if (velocity != null) {
            val rv = VELOCITY_ERROR * VELOCITY_ERROR
            east.updateVelocity(velocity.first, rv)
            north.updateVelocity(velocity.second, rv)
        }
        return estimate(false)
    }

    private fun start(position: LatLon, r: Double, velocity: Pair<Double, Double>?, now: Long) {
        origin = position
        at = now
        rejectedInRow = 0
        val vVar = if (velocity != null) VELOCITY_ERROR * VELOCITY_ERROR else 25.0
        east.reset(0.0, r, velocity?.first ?: 0.0, vVar)
        north.reset(0.0, r, velocity?.second ?: 0.0, vVar)
    }

    private fun estimate(rejected: Boolean): Estimate {
        val position = CarMotion.shift(origin!!, east.p, north.p)
        val speed = hypot(east.v, north.v)
        val bearing = if (speed > 1.0) ((Math.toDegrees(atan2(east.v, north.v)) + 360) % 360).toFloat() else null
        return Estimate(position, sqrt(max(east.pp, north.pp)), speed, bearing, rejected)
    }

    companion object {
        /** How hard a car speeds up, brakes or turns, m/s² (a walker: [WALK_ACCELERATION]). */
        const val CAR_ACCELERATION = 4.0
        const val WALK_ACCELERATION = 1.5
        const val OUTLIER_SIGMA = 5.0
        const val OUTLIER_METERS = 30.0
        /** This many far-off fixes in a row: they're right, start again from there. */
        const val ACCEPT_AFTER = 3
        /** A gap longer than this (seconds) starts afresh. */
        const val RESET_AFTER_S = 5.0
        /** GPS (Doppler) speed is good to about this, m/s. */
        const val VELOCITY_ERROR = 0.7
    }
}
