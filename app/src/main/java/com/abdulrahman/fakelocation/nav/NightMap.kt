package com.abdulrahman.fakelocation.nav

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.abdulrahman.fakelocation.data.LatLon
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** When to switch the navigation map to dark colours (Settings → Map & routing). */
enum class NightMapMode { AUTO, ALWAYS, OFF }

/** A dark map for driving at night, so the screen doesn't dazzle. */
object NightMap {

    /** The sun is this far below the horizon: just after sunset, before sunrise. */
    private const val DARK_BELOW_DEGREES = -2.0

    fun isNight(mode: NightMapMode, at: LatLon?, now: Long): Boolean = when (mode) {
        NightMapMode.ALWAYS -> true
        NightMapMode.OFF -> false
        NightMapMode.AUTO -> sunElevation(at ?: LatLon.HOME_REGION, now) < DARK_BELOW_DEGREES
    }

    /**
     * How high the sun is above the horizon, in degrees, at [at] and [epochMillis] (the usual low-precision solar
     * formulas: good to a fraction of a degree, plenty for "is it dark yet").
     */
    fun sunElevation(at: LatLon, epochMillis: Long): Double {
        val d = epochMillis / 86_400_000.0 - 10_957.5 // days since J2000.0 (2000-01-01 12:00 UTC)
        val meanAnomaly = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val meanLongitude = (280.459 + 0.98564736 * d) % 360
        val eclipticLongitude = Math.toRadians(meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly))
        val obliquity = Math.toRadians(23.439 - 0.00000036 * d)
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val siderealHours = (18.697374558 + 24.06570982441908 * d) % 24
        val hourAngle = Math.toRadians(siderealHours * 15 + at.lon) - rightAscension
        val lat = Math.toRadians(at.lat)
        return Math.toDegrees(asin(sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)))
    }

    /**
     * Light map → dark map: colours inverted and toned down, so the background is near black, labels light, and the
     * green route stands out. Used on the phone's map and the car screen.
     */
    val filter: ColorMatrixColorFilter by lazy {
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 225f,
                    0f, -0.85f, 0f, 0f, 228f,
                    0f, 0f, -0.85f, 0f, 235f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
}
