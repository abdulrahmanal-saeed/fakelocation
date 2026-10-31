package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.data.moved
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * "Wander around here": a walk to random spots near a place, pausing a few minutes at each, and back to the start.
 * The streets in between come from the router; this only picks the spots. Pure, so it can be tested.
 */
object Wander {
    private const val WALK_MPS = 1.35
    /** Streets are longer than a straight line between two spots. */
    private const val DETOUR = 1.3
    private const val MIN_SPOTS = 2
    private const val MAX_SPOTS = 12

    /**
     * Stops for a walk of about [minutes] within [radius] metres of [center]: [center] first and last, spots in
     * between. [spotName] names the n-th spot (1-based).
     */
    fun stops(center: LatLon, centerName: String, radius: Double, minutes: Int, random: Random, spotName: (Int) -> String): List<Stop> {
        // Two random spots in a circle are about 0.9 radius apart; a pause is 1–5 minutes, 3 on average.
        val perSpot = 0.9 * radius * DETOUR / WALK_MPS + 3 * 60
        val count = (minutes * 60 / perSpot).roundToInt().coerceIn(MIN_SPOTS, MAX_SPOTS)
        val spots = ArrayList<LatLon>()
        var previous = center
        repeat(count) {
            var spot = randomSpot(center, radius, random)
            // Not right next to the previous spot, or the walk is mostly standing still.
            for (attempt in 0 until 20) {
                if (distanceMeters(spot, previous) >= radius * 0.35) break
                spot = randomSpot(center, radius, random)
            }
            spots += spot
            previous = spot
        }
        var id = 0L
        return buildList {
            add(Stop(++id, centerName, center, 0))
            spots.forEachIndexed { i, spot -> add(Stop(++id, spotName(i + 1), spot, random.nextInt(1, 6))) }
            add(Stop(++id, centerName, center, 0))
        }
    }

    /** Evenly spread inside the circle (not bunched in the middle). */
    private fun randomSpot(center: LatLon, radius: Double, random: Random): LatLon =
        center.moved(random.nextDouble(0.0, 360.0), radius * sqrt(random.nextDouble()))
}
