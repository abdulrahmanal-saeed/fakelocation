package com.abdulrahman.fakelocation.mock

import com.abdulrahman.fakelocation.data.LatLon
import kotlin.math.cos
import kotlin.random.Random

/** Tiny random wander (a few metres) like a real GPS fix. */
internal class Drift {
    private var north = 0.0
    private var east = 0.0

    /** The position nudged a little (latitude, longitude) and an accuracy to report with it. */
    fun apply(position: LatLon): Triple<Double, Double, Float> {
        north = (north + Random.nextDouble(-0.6, 0.6)).coerceIn(-4.0, 4.0)
        east = (east + Random.nextDouble(-0.6, 0.6)).coerceIn(-4.0, 4.0)
        val lat = position.lat + north / 111_320.0
        val lon = position.lon + east / (111_320.0 * cos(Math.toRadians(position.lat)))
        return Triple(lat, lon, Random.nextDouble(3.0, 8.0).toFloat())
    }
}
