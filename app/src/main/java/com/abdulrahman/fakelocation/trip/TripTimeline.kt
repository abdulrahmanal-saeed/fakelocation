package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.RoutePath
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.distanceMeters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class TripPhase { SCHEDULED, WAITING, MOVING, FINISHED }

/** Where the trip is at one moment. Times are trip-clock milliseconds (0 = arriving at the first stop). */
data class TripPoint(
    val phase: TripPhase,
    /** WAITING: the stop we're at. MOVING: the stop we just left. */
    val stopIndex: Int,
    val position: LatLon,
    val bearing: Float,
    val speedMps: Float,
    /** Index of the path point just behind us (for drawing the travelled part). */
    val pointIndex: Int,
    val distanceDone: Double,
    val distanceToNextStop: Double,
)

/**
 * The whole trip laid out on one clock: waiting at each stop, then driving or walking the leg to the next one
 * at that street's speed. Pure maths, so it can be tested and recomputed any time (e.g. after "+5 min").
 */
class TripTimeline(val stops: List<Stop>, rawPath: RoutePath, val natural: Boolean) {

    val path: RoutePath = if (natural) slowAtTurns(rawPath) else rawPath
    val arrive = LongArray(stops.size)
    val leave = LongArray(stops.size)
    val totalMs: Long
    private val pointTime = DoubleArray(path.points.size)
    private val pointDist = DoubleArray(path.points.size)
    val totalDistance: Double

    init {
        var clock = 0L
        for (i in stops.indices) {
            if (i > 0) clock += legMs(i - 1)
            arrive[i] = clock
            if (i < stops.lastIndex) clock += stops[i].waitMinutes * 60_000L
            leave[i] = clock
        }
        totalMs = arrive.last()

        for (k in 1 until path.points.size) {
            pointDist[k] = pointDist[k - 1] + distanceMeters(path.points[k - 1], path.points[k])
        }
        totalDistance = pointDist.lastOrNull() ?: 0.0

        // Time at each path point, stretched so every leg starts when we leave a stop and ends on arrival.
        path.legs.forEachIndexed { i, leg ->
            val start = leave[i].toDouble()
            val span = (arrive[i + 1] - leave[i]).toDouble()
            val raw = path.segmentSeconds.subList(leg.firstPoint, leg.lastPoint)
            val sum = raw.sum().takeIf { it > 0 } ?: 1.0
            var t = start
            pointTime[leg.firstPoint] = t
            for (k in leg.firstPoint until leg.lastPoint) {
                t += path.segmentSeconds[k] / sum * span
                pointTime[k + 1] = t
            }
        }
    }

    private fun legMs(i: Int) = (path.legs[i].durationSeconds * 1000).toLong()

    fun at(t: Long): TripPoint {
        if (t < 0) return still(TripPhase.SCHEDULED, 0)
        if (t >= totalMs) return still(TripPhase.FINISHED, stops.lastIndex)
        for (i in stops.indices) {
            if (t >= arrive[i] && t < leave[i]) return still(TripPhase.WAITING, i)
            if (i < stops.lastIndex && t >= leave[i] && t < arrive[i + 1]) return moving(i, t)
        }
        return still(TripPhase.FINISHED, stops.lastIndex)
    }

    private fun still(phase: TripPhase, i: Int): TripPoint {
        val k = if (i == 0) 0 else path.legs[i - 1].lastPoint
        val next = if (i < path.legs.size) pointDist[path.legs[i].lastPoint] - pointDist[k] else 0.0
        return TripPoint(phase, i, stops[i].position, 0f, 0f, k, pointDist[k], next)
    }

    private fun moving(leg: Int, t: Long): TripPoint {
        val first = path.legs[leg].firstPoint
        val last = path.legs[leg].lastPoint
        val start = leave[leg]
        val span = arrive[leg + 1] - start
        val u = (t - start).toDouble()
        val warped = if (natural) start + warp(u, span.toDouble(), leg) else t.toDouble()
        // Find the segment we're on.
        var k = first
        while (k < last - 1 && pointTime[k + 1] <= warped) k++
        val segStart = pointTime[k]
        val segEnd = pointTime[k + 1]
        val f = if (segEnd > segStart) ((warped - segStart) / (segEnd - segStart)).coerceIn(0.0, 1.0) else 1.0
        val a = path.points[k]
        val b = path.points[k + 1]
        val position = LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        val segLength = pointDist[k + 1] - pointDist[k]
        val segSeconds = (segEnd - segStart) / 1000.0
        var speed = if (segSeconds > 0) segLength / segSeconds else 0.0
        if (natural) speed *= (warp(u + 500, span.toDouble(), leg) - warp(u - 500, span.toDouble(), leg)) / 1000.0
        val done = pointDist[k] + segLength * f
        return TripPoint(
            TripPhase.MOVING, leg, position, bearing(a, b), speed.toFloat(), k, done, pointDist[last] - done
        )
    }

    companion object {
        /**
         * Gently speeds up and slows down along a leg (about ±20 %) while still arriving exactly on time:
         * the shift is zero at both ends of the leg and never runs time backwards.
         */
        fun warp(u: Double, span: Double, leg: Int): Double {
            if (span <= 0) return u
            val x = u.coerceIn(0.0, span)
            val envelope = (minOf(x, span - x) / 30_000.0).coerceIn(0.0, 1.0)
            val p1 = 45_000.0
            val p2 = 110_000.0
            val wiggle = 0.10 * p1 / (2 * PI) * sin(2 * PI * x / p1 + leg) +
                0.08 * p2 / (2 * PI) * sin(2 * PI * x / p2 + leg * 1.7)
            return x + envelope * wiggle
        }

        fun bearing(a: LatLon, b: LatLon): Float {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        }

        /** Moves some travel time onto the pieces of road around sharp turns, keeping each leg's total the same. */
        fun slowAtTurns(path: RoutePath): RoutePath {
            val seconds = path.segmentSeconds.toMutableList()
            val weights = DoubleArray(seconds.size) { 1.0 }
            for (leg in path.legs) {
                for (k in leg.firstPoint + 1 until leg.lastPoint) {
                    val turn = abs(((bearing(path.points[k], path.points[k + 1]) - bearing(path.points[k - 1], path.points[k]) + 540) % 360) - 180)
                    if (turn > 30) {
                        val extra = 0.8 * (turn / 180.0)
                        weights[k - 1] += extra
                        weights[k] += extra
                    }
                }
                val before = (leg.firstPoint until leg.lastPoint).sumOf { seconds[it] }
                val after = (leg.firstPoint until leg.lastPoint).sumOf { seconds[it] * weights[it] }
                if (after > 0) for (k in leg.firstPoint until leg.lastPoint) seconds[k] = seconds[k] * weights[k] * before / after
            }
            return path.copy(segmentSeconds = seconds)
        }
    }
}
