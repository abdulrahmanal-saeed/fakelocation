package com.abdulrahman.fakelocation.ui

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.nav.CarMotion
import com.abdulrahman.fakelocation.nav.HeadingSmoother
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.trip.TripTimeline
import kotlin.math.abs

/**
 * Where to draw the car on each frame, for either Navigate map: positions from navigation go into [CarMotion]
 * (along the road while on the route), and the heading follows the road ahead (or the GPS) through a
 * [HeadingSmoother].
 */
class CarTracker {

    /** One frame: where, which way, how far along the route (null off it), and whether it's still moving. */
    data class Frame(val position: LatLon, val heading: Float, val along: Double?, val moving: Boolean)

    private val motion = CarMotion()
    private val smoother = HeadingSmoother()
    private var lastFixId = -1L
    private var shownHeading = 0f

    /** Takes the position from [s] if it's a new one. */
    fun takeFix(s: NavSession.State, now: Long) {
        if (s.fixId == lastFixId) return
        lastFixId = s.fixId
        val here = s.real ?: return
        val r = s.route
        val p = s.progress
        val onRoute = s.navigating && r != null && p != null && p.offRouteMeters < ON_ROUTE_METERS
        if (onRoute) motion.onFix(p!!.snapped, r, p.alongMeters, s.speedKmh / 3.6, s.bearing, now)
        else motion.onFix(here, null, null, s.speedKmh / 3.6, s.bearing, now)
    }

    fun frame(s: NavSession.State, now: Long): Frame? {
        val pose = motion.poseAt(now) ?: return null
        val r = s.route
        val along = pose.along
        // On the route, point along the road ahead (steadier than the GPS heading); elsewhere the GPS heading.
        val target = if (along != null && r != null) TripTimeline.bearing(r.pointAt(along - 5), r.pointAt(along + 15)) else s.bearing
        shownHeading = smoother.update(target, now)
        val turning = abs(HeadingSmoother.difference(shownHeading.toDouble(), target.toDouble())) > 0.3
        return Frame(pose.position, shownHeading, along, motion.moving(now) || turning)
    }

    private companion object {
        const val ON_ROUTE_METERS = 25.0
    }
}
