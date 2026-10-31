package com.abdulrahman.fakelocation.nav

import kotlin.math.exp
import kotlin.math.ln

/**
 * How close the driving map is: closer in town, further out on fast roads, and while a turn comes up, out just enough
 * that the turn shows above the car before you reach it, then back in. Changes glide frame by frame.
 */
object ZoomPlanner {

    /** Speed (km/h) → zoom, joined by straight lines in between. */
    private val bySpeed = listOf(25.0 to 17.8, 50.0 to 17.2, 85.0 to 16.6, 115.0 to 16.1)

    /** Approaching a turn closer than this, the map makes room for it. */
    const val TURN_WINDOW_METERS = 800.0
    /** At most this much further out for a turn than for the speed. */
    const val MAX_OUT = 1.6
    /** Right at a turn, this much closer than for the speed, to see the junction. */
    const val AT_TURN_IN = 0.3
    const val AT_TURN_METERS = 90.0

    fun forSpeed(kmh: Double): Double {
        if (kmh <= bySpeed.first().first) return bySpeed.first().second
        for (i in 1 until bySpeed.size) {
            val (s1, z1) = bySpeed[i]
            if (kmh <= s1) {
                val (s0, z0) = bySpeed[i - 1]
                return z0 + (z1 - z0) * (kmh - s0) / (s1 - s0)
            }
        }
        return bySpeed.last().second
    }

    /**
     * The zoom to aim for at [kmh], with a turn [metersToTurn] ahead (null for none). [zoom] is the map's zoom now and
     * [pixelsPerMeter] its scale at that zoom; [aheadPixels] is the room on screen above the car.
     */
    fun target(kmh: Double, metersToTurn: Double?, zoom: Double, pixelsPerMeter: Double, aheadPixels: Double): Double {
        val base = forSpeed(kmh)
        if (metersToTurn == null || metersToTurn <= 0 || metersToTurn > TURN_WINDOW_METERS || pixelsPerMeter <= 0 || aheadPixels <= 0) return base
        if (metersToTurn < AT_TURN_METERS) return base + AT_TURN_IN
        // Each zoom level doubles the scale: the zoom at which the turn lands at the top of the free space.
        val fit = zoom + ln(aheadPixels / (metersToTurn * pixelsPerMeter)) / ln(2.0)
        return minOf(base, maxOf(fit, base - MAX_OUT))
    }

    /** One frame ([dtMs]) of gliding from [zoom] toward [target]. */
    fun ease(zoom: Double, target: Double, dtMs: Long): Double {
        if (dtMs <= 0) return zoom
        return zoom + (target - zoom) * (1 - exp(-dtMs / 1100.0))
    }
}
