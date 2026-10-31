package com.abdulrahman.fakelocation.mock

import com.abdulrahman.fakelocation.data.LatLon
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tiny random wander (a few metres) like a real GPS fix. It wanders by time, not by fix, so it looks the same whether
 * the position is sent once a second or five times.
 */
internal class Drift(private val random: Random = Random.Default) {
    private var north = 0.0
    private var east = 0.0
    private var accuracy = random.nextDouble(3.0, 8.0)

    /** The position nudged a little (latitude, longitude) and an accuracy to report with it, [seconds] after the last. */
    fun apply(position: LatLon, seconds: Double = 1.0): Triple<Double, Double, Float> {
        // A random walk: steps grow with the square root of the time, so 5 small steps ≈ 1 step a second.
        val k = sqrt(seconds.coerceIn(0.0, 2.0))
        north = (north + random.nextDouble(-0.6, 0.6) * k).coerceIn(-LIMIT, LIMIT)
        east = (east + random.nextDouble(-0.6, 0.6) * k).coerceIn(-LIMIT, LIMIT)
        accuracy = (accuracy + random.nextDouble(-1.5, 1.5) * k).coerceIn(3.0, 8.0)
        val lat = position.lat + north / 111_320.0
        val lon = position.lon + east / (111_320.0 * cos(Math.toRadians(position.lat)))
        return Triple(lat, lon, accuracy.toFloat())
    }

    /** Metres off the true position now (east, north), for tests. */
    val offset get() = east to north

    companion object {
        const val LIMIT = 4.0
    }
}
