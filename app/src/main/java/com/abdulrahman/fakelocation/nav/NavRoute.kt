package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.distanceMeters
import kotlin.math.cos

enum class Maneuver { DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

/** One lane at a junction: the arrows painted on it, and whether it leads where we're going. */
data class Lane(val directions: List<String>, val valid: Boolean)

/** One instruction: what to do when reaching [pointIndex] on the route. */
data class NavStep(
    val maneuver: Maneuver,
    val text: String,
    /** Short form for the voice (no street names in other scripts). */
    val spoken: String,
    val pointIndex: Int,
    /** The road you're on after this step (shown as "current street"). */
    val road: String = "",
    /** Lanes at this junction, when the map has them. */
    val lanes: List<Lane> = emptyList(),
    /** Roundabout exit number (1 = first), 0 when not a roundabout. */
    val exit: Int = 0,
)

/** A route to drive or walk for real, with turn-by-turn steps. */
data class NavRoute(
    val points: List<LatLon>,
    val steps: List<NavStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val trafficAware: Boolean = false,
    /** Point index where each in-between stop is reached (empty for a plain A → B route). */
    val stopPoints: List<Int> = emptyList(),
) {
    /** Distance along the route at each point. */
    val along: DoubleArray = DoubleArray(points.size).also {
        for (i in 1 until points.size) it[i] = it[i - 1] + distanceMeters(points[i - 1], points[i])
    }
    val length get() = along.lastOrNull() ?: 0.0

    /** Distance along the route of each in-between stop. */
    val stopsAlong: List<Double> get() = stopPoints.map { along[it.coerceIn(0, points.lastIndex)] }
}

/** Where the real position is relative to the route. */
data class NavProgress(
    val snapped: LatLon,
    val segment: Int,
    val alongMeters: Double,
    val offRouteMeters: Double,
    /** The next instruction to follow (null once only "arrive" is left and passed). */
    val nextStep: NavStep?,
    val metersToNextStep: Double,
    /** The instruction after [nextStep], for "then …". */
    val thenStep: NavStep?,
    /** The step we're currently driving along (its road is the current street). */
    val currentStep: NavStep?,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val arrived: Boolean,
)

/** Follows a real position along a route: snaps to the road, finds the next turn, spots going off-route. */
class NavTracker(val route: NavRoute) {

    private var lastSegment = 0

    fun update(position: LatLon): NavProgress {
        val points = route.points
        // Search near the last match first (cheap and avoids jumping to a parallel road), then everywhere.
        var best = nearest(position, (lastSegment - 5).coerceAtLeast(0), (lastSegment + 60).coerceAtMost(points.size - 2))
        if (best.second > 40) {
            val all = nearest(position, 0, points.size - 2)
            if (all.second < best.second - 10) best = all
        }
        val (segment, offRoute, fraction) = best
        lastSegment = segment
        val a = points[segment]
        val b = points[segment + 1]
        val snapped = LatLon(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)
        val along = route.along[segment] + (route.along[segment + 1] - route.along[segment]) * fraction
        val nextIndex = route.steps.indexOfFirst { route.along[it.pointIndex] > along + 5 }
        val next = route.steps.getOrNull(nextIndex)
        val remaining = (route.length - along).coerceAtLeast(0.0)
        val arrived = remaining < ARRIVE_METERS && offRoute < 60
        return NavProgress(
            snapped = snapped,
            segment = segment,
            alongMeters = along,
            offRouteMeters = offRoute,
            nextStep = next,
            metersToNextStep = next?.let { route.along[it.pointIndex] - along } ?: 0.0,
            thenStep = if (nextIndex >= 0) route.steps.getOrNull(nextIndex + 1) else null,
            currentStep = route.steps.lastOrNull { route.along[it.pointIndex] <= along + 5 },
            remainingMeters = remaining,
            remainingSeconds = if (route.length > 0) route.durationSeconds * remaining / route.length else 0.0,
            arrived = arrived,
        )
    }

    /** Nearest segment in [from, to]: index, distance in metres, and how far along that segment (0..1). */
    private fun nearest(p: LatLon, from: Int, to: Int): Triple<Int, Double, Double> {
        var bestIndex = from
        var bestDistance = Double.MAX_VALUE
        var bestFraction = 0.0
        val cosLat = cos(Math.toRadians(p.lat))
        for (i in from..to) {
            val a = route.points[i]
            val b = route.points[i + 1]
            // Flat projection in metres around p: plenty accurate over a few hundred metres.
            val ax = (a.lon - p.lon) * 111_320.0 * cosLat
            val ay = (a.lat - p.lat) * 110_540.0
            val bx = (b.lon - p.lon) * 111_320.0 * cosLat
            val by = (b.lat - p.lat) * 110_540.0
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val x = ax + dx * t
            val y = ay + dy * t
            val d = Math.sqrt(x * x + y * y)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
                bestFraction = t
            }
        }
        return Triple(bestIndex, bestDistance, bestFraction)
    }

    companion object {
        const val ARRIVE_METERS = 25.0
        const val OFF_ROUTE_METERS = 50.0
    }
}
