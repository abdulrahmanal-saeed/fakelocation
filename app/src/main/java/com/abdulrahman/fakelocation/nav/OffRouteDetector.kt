package com.abdulrahman.fakelocation.nav

import kotlin.math.abs

/**
 * Decides when you've left the route. Quickly when it's clear: driving the wrong way from the route (your heading far
 * from the road's) a little away from it, or well away from it with a good fix. Patiently when the GPS is poor: three
 * fixes in a row well away, as before, so a jumpy signal doesn't start a reroute.
 */
class OffRouteDetector {

    private var awayFixes = 0
    private var wrongWayFixes = 0

    fun reset() {
        awayFixes = 0
        wrongWayFixes = 0
    }

    /**
     * One fix: [offMeters] from the route, GPS [accuracy] in metres (null if unknown), [speedKmh], the GPS heading
     * ([gpsBearing], null when it has none) and the route's direction where you are ([roadBearing]). True = off it.
     */
    fun update(offMeters: Double, accuracy: Float?, speedKmh: Double, gpsBearing: Float?, roadBearing: Float): Boolean {
        val goodFix = accuracy != null && accuracy <= GOOD_ACCURACY
        awayFixes = when {
            offMeters <= FAR_METERS -> 0
            goodFix -> PATIENT_FIXES
            else -> awayFixes + 1
        }
        val wrongWay = gpsBearing != null && speedKmh >= MIN_KMH && offMeters > NEAR_METERS &&
            (accuracy == null || accuracy <= offMeters) &&
            abs(HeadingSmoother.difference(roadBearing.toDouble(), gpsBearing.toDouble())) > WRONG_WAY_DEGREES
        wrongWayFixes = if (wrongWay) wrongWayFixes + 1 else 0
        return awayFixes >= PATIENT_FIXES || wrongWayFixes >= WRONG_WAY_FIXES
    }

    companion object {
        const val FAR_METERS = NavTracker.OFF_ROUTE_METERS
        const val NEAR_METERS = 20.0
        const val GOOD_ACCURACY = 15f
        const val PATIENT_FIXES = 3
        const val MIN_KMH = 15.0
        const val WRONG_WAY_DEGREES = 60.0
        const val WRONG_WAY_FIXES = 2
        /** At least this long between two reroutes. */
        const val REROUTE_GAP_MS = 8_000L
    }
}
