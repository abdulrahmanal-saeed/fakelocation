package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where to draw the car on every frame, between GPS fixes that come once a second. It carries on at the speed of the
 * last fix (along the road's own shape while on the route, so it follows bends instead of cutting them), and the
 * correction a new fix brings is blended in over a fraction of a second instead of jumping. On the route it never
 * slides backwards: when it got ahead of the GPS it waits for it. Times are passed in, so this is plain maths.
 */
class CarMotion(
    private val maxAheadMs: Long = MAX_AHEAD_MS,
    private val blendMs: Double = BLEND_MS,
    private val snapMeters: Double = SNAP_METERS,
) {
    /** What to draw: a position, and how far along the route it is (null when not on the route). */
    data class Pose(val position: LatLon, val along: Double?)

    private var route: NavRoute? = null
    private var fixAt = 0L
    private var fixPosition: LatLon? = null
    private var fixAlong: Double? = null
    private var speed = 0.0
    private var bearing = 0.0

    // The correction still being blended in (it fades from [offsetAt]): metres along the route, and east / north.
    private var offsetAlong = 0.0
    private var offsetEast = 0.0
    private var offsetNorth = 0.0
    private var offsetAt = 0L
    /** Furthest along the route drawn so far, so the car doesn't roll back. */
    private var shownAlong: Double? = null

    /** Speed the car is being moved at, in m/s. */
    val speedMps get() = speed

    fun reset() {
        fixPosition = null
        fixAlong = null
        route = null
        shownAlong = null
        offsetAlong = 0.0
        offsetEast = 0.0
        offsetNorth = 0.0
    }

    /**
     * A new fix at [at] ms: [position], and when it is on [route], [along] metres along it (else both null).
     * [speedMps] and [bearing] say where it goes until the next one.
     */
    fun onFix(position: LatLon, route: NavRoute?, along: Double?, speedMps: Double, bearing: Float, at: Long) {
        val before = poseAt(at)
        val sameRoute = route != null && route === this.route
        this.route = route
        fixAt = at
        fixPosition = position
        fixAlong = if (route != null) along?.coerceIn(0.0, route.length) else null
        speed = if (speedMps.isFinite()) speedMps.coerceIn(0.0, 90.0) else 0.0
        this.bearing = bearing.toDouble()
        offsetAlong = 0.0
        offsetEast = 0.0
        offsetNorth = 0.0
        offsetAt = at
        if (before == null) {
            shownAlong = null
            return
        }
        val newAlong = fixAlong
        if (route != null && newAlong != null && sameRoute && before.along != null) {
            // Same road: correct the distance along it, and fade out any sideways bit left from before.
            val behind = before.along - newAlong
            if (abs(behind) <= snapMeters) {
                offsetAlong = behind
                val (e, n) = metres(route.pointAt(before.along), before.position)
                offsetEast = e
                offsetNorth = n
            } else {
                shownAlong = null // too far off: jump there
            }
            return
        }
        // Joining or leaving the route (or a new one): blend from where the car is drawn to the new spot.
        shownAlong = null
        val (e, n) = metres(base(at), before.position)
        if (hypot(e, n) <= snapMeters * 2) {
            offsetEast = e
            offsetNorth = n
        }
    }

    /** Where to draw the car at [now] ms; null before the first fix. */
    fun poseAt(now: Long): Pose? {
        if (fixPosition == null) return null
        val fade = exp(-(now - offsetAt).coerceAtLeast(0L) / blendMs)
        val r = route
        val a = fixAlong
        if (r != null && a != null) {
            var along = (a + speed * aheadSeconds(now) + offsetAlong * fade).coerceIn(0.0, r.length)
            shownAlong?.let { if (along < it) along = it }
            shownAlong = along
            return Pose(shift(r.pointAt(along), offsetEast * fade, offsetNorth * fade), along)
        }
        return Pose(shift(base(now), offsetEast * fade, offsetNorth * fade), null)
    }

    /** True while the car is still moving on screen (carrying on at speed, or a correction fading out). */
    fun moving(now: Long): Boolean =
        (speed > 0.3 && now - fixAt < maxAheadMs) || now - offsetAt < blendMs * 5

    private fun aheadSeconds(now: Long) = (now - fixAt).coerceIn(0L, maxAheadMs) / 1000.0

    /** The latest fix carried on at its speed, without any correction. */
    private fun base(now: Long): LatLon {
        val r = route
        val a = fixAlong
        if (r != null && a != null) return r.pointAt(a + speed * aheadSeconds(now))
        val d = speed * aheadSeconds(now)
        val rad = Math.toRadians(bearing)
        return shift(fixPosition!!, d * sin(rad), d * cos(rad))
    }

    companion object {
        /** Carry on at the last speed for at most this long without a new fix. */
        const val MAX_AHEAD_MS = 2500L
        /** How quickly a correction fades (time constant). */
        const val BLEND_MS = 350.0
        /** A correction bigger than this is a jump, not something to glide over. */
        const val SNAP_METERS = 30.0

        /** East / north metres from [a] to [b] (flat, fine over a few hundred metres). */
        fun metres(a: LatLon, b: LatLon): Pair<Double, Double> {
            val k = cos(Math.toRadians(a.lat))
            return (b.lon - a.lon) * 111_320.0 * k to (b.lat - a.lat) * 110_540.0
        }

        fun shift(p: LatLon, east: Double, north: Double): LatLon {
            if (east == 0.0 && north == 0.0) return p
            return LatLon(p.lat + north / 110_540.0, p.lon + east / (111_320.0 * cos(Math.toRadians(p.lat))))
        }
    }
}
