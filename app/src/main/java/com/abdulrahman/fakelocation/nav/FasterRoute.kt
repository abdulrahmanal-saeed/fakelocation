package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import kotlin.math.cos
import kotlin.math.max

/** Compares routes: how much road two of them share, and whether another way is worth switching to. */
object FasterRoute {

    /** A quicker way than the route being driven, and how much it saves. */
    data class Offer(
        val route: NavRoute,
        val savesSeconds: Double,
        /** Stops already passed when it was found (the route only contains the ones after). */
        val stopBase: Int = 0,
        val offeredAt: Long = 0L,
    )

    /** Don't bother the driver for less than this. */
    const val MIN_SAVING_SECONDS = 120.0
    private const val MIN_SAVING_SHARE = 0.07
    private const val NEAR_METERS = 35.0
    private const val SAME_ROUTE = 0.9

    /**
     * How much of [candidate] runs along [other] (0..1), looking at [other] only from [fromAlong] metres on.
     * Sampled every 50 m or so, which is plenty to tell two roads apart.
     */
    fun sharedFraction(candidate: NavRoute, other: NavRoute, fromAlong: Double = 0.0): Double {
        if (candidate.points.size < 2 || other.points.size < 2 || candidate.length <= 0) return 0.0
        val step = max(50.0, candidate.length / 150)
        val firstSegment = other.along.indexOfFirst { it > fromAlong }.let { if (it <= 0) 0 else it - 1 }.coerceAtMost(other.points.size - 2)
        var samples = 0
        var near = 0
        var hint = firstSegment
        var m = 0.0
        while (m <= candidate.length) {
            val found = nearestSegment(candidate.pointAt(m), other, firstSegment, hint)
            if (found.second <= NEAR_METERS) {
                near++
                hint = found.first
            }
            samples++
            m += step
        }
        return near.toDouble() / samples
    }

    /**
     * Picks the route worth offering while driving [current], [fromAlong] metres in with [remainingSeconds] to go.
     * [candidates] are fresh routes from where the car is now. When one of them is the road we're already on, its
     * time is the up-to-date figure to beat; otherwise the estimate we had is.
     * [alreadyDeclined] is a saving the driver has turned down before: only a clearly bigger one is offered again.
     */
    fun pick(
        current: NavRoute,
        fromAlong: Double,
        remainingSeconds: Double,
        candidates: List<NavRoute>,
        alreadyDeclined: Double = 0.0,
    ): Offer? {
        if (candidates.isEmpty()) return null
        val same = candidates.filter { sharedFraction(it, current, fromAlong) > SAME_ROUTE }
        val others = candidates - same.toSet()
        val best = others.minByOrNull { it.durationSeconds } ?: return null
        val mine = same.minOfOrNull { it.durationSeconds } ?: remainingSeconds
        val saves = mine - best.durationSeconds
        val needed = max(MIN_SAVING_SECONDS, mine * MIN_SAVING_SHARE) + alreadyDeclined
        return if (saves >= needed) Offer(best, saves) else null
    }

    /** Nearest segment of [route] to [p] at or after [from]: index and distance in metres. Looks near [hint] first. */
    private fun nearestSegment(p: LatLon, route: NavRoute, from: Int, hint: Int): Pair<Int, Double> {
        val last = route.points.size - 2
        val local = scan(p, route, max(from, hint - 3), (hint + 40).coerceAtMost(last))
        if (local.second <= NEAR_METERS) return local
        return scan(p, route, from, last)
    }

    private fun scan(p: LatLon, route: NavRoute, from: Int, to: Int): Pair<Int, Double> {
        var bestIndex = from
        var best = Double.MAX_VALUE
        val cosLat = cos(Math.toRadians(p.lat))
        for (i in from..to) {
            val a = route.points[i]
            val b = route.points[i + 1]
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
            if (d < best) {
                best = d
                bestIndex = i
            }
        }
        return bestIndex to best
    }
}
