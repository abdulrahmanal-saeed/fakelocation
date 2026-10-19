package com.abdulrahman.fakelocation.data

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

fun distanceMeters(a: LatLon, b: LatLon): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else String.format(Locale.US, "%.1f km", meters / 1000)

fun LatLon.format(digits: Int = 5): String =
    String.format(Locale.US, "%.${digits}f, %.${digits}f", lat, lon)

/** "2h 10m", "35m", "48s" */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}

/** "00:42:10" */
fun formatClock(millis: Long): String {
    val s = millis / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
