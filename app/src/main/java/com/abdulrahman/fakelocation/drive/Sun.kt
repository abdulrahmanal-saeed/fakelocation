package com.abdulrahman.fakelocation.drive

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Where the sun is, to switch the driving screen to night colours at dusk. */
object Sun {

    /**
     * The sun's height above the horizon in degrees at [timeMillis] (UTC epoch) for a place.
     * Good to a fraction of a degree, which is far more than a day/night switch needs.
     */
    fun elevation(lat: Double, lon: Double, timeMillis: Long): Double {
        val days = timeMillis / 86_400_000.0 - 10957.5 // days since 2000-01-01 12:00 UTC
        val meanLongitude = norm(280.460 + 0.9856474 * days)
        val anomaly = Math.toRadians(norm(357.528 + 0.9856003 * days))
        val eclipticLongitude = Math.toRadians(meanLongitude + 1.915 * sin(anomaly) + 0.020 * sin(2 * anomaly))
        val tilt = Math.toRadians(23.439 - 0.0000004 * days)
        val declination = asin(sin(tilt) * sin(eclipticLongitude))
        val rightAscension = Math.toDegrees(Math.atan2(cos(tilt) * sin(eclipticLongitude), cos(eclipticLongitude)))
        val siderealGreenwich = norm(280.46061837 + 360.98564736629 * days)
        val hourAngle = Math.toRadians(norm(siderealGreenwich + lon - rightAscension + 180) - 180)
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(declination) + cos(phi) * cos(declination) * cos(hourAngle)))
    }

    /** Dark enough for night colours: from a little after sunset to a little before sunrise. */
    fun isNight(lat: Double, lon: Double, timeMillis: Long): Boolean = elevation(lat, lon, timeMillis) < -2.0

    private fun norm(degrees: Double) = degrees - 360.0 * floor(degrees / 360.0)
}
