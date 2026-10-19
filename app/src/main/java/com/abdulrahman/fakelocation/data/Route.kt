package com.abdulrahman.fakelocation.data

enum class TravelMode { CAR, WALK }

/** One stop on a route. [waitMinutes] is how long to stay there before moving on. */
data class Stop(val id: Long, val name: String, val position: LatLon, val waitMinutes: Int)

/** One stretch of road between two stops, as returned by the router. */
data class Leg(val distanceMeters: Double, val durationSeconds: Double, val firstPoint: Int, val lastPoint: Int)

/**
 * The street path for a route: every point along the road plus how long each piece takes
 * at that street's speed (segment i goes from points[i] to points[i + 1]).
 */
data class RoutePath(
    val points: List<LatLon>,
    val segmentSeconds: List<Double>,
    val legs: List<Leg>,
    /** True when the times already include live traffic (Google), so the traffic setting is not added again. */
    val trafficAware: Boolean = false,
) {
    val distanceMeters get() = legs.sumOf { it.distanceMeters }
    val durationSeconds get() = legs.sumOf { it.durationSeconds }

    /** The same path, every piece taking [factor] times as long (e.g. 1.3 for heavy traffic). */
    fun slower(factor: Double): RoutePath =
        if (factor == 1.0) this
        else copy(segmentSeconds = segmentSeconds.map { it * factor }, legs = legs.map { it.copy(durationSeconds = it.durationSeconds * factor) })
}

enum class TimingMode { NOW, START_AT, ARRIVE_BY }

data class Timing(val mode: TimingMode = TimingMode.NOW, val timeMillis: Long? = null)

data class Route(
    val id: Long,
    val name: String,
    val mode: TravelMode,
    val stops: List<Stop>,
    val path: RoutePath? = null,
    val timing: Timing = Timing(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** The path with the traffic slowdown applied (cars only). */
    fun travelPath(trafficPercent: Int): RoutePath? =
        if (mode == TravelMode.CAR && path?.trafficAware == false) path.slower(1 + trafficPercent / 100.0) else path

    /** Waiting time that counts towards the trip: every stop except the last one. */
    val waitMinutes get() = stops.dropLast(1).sumOf { it.waitMinutes }
}

/** When the trip reaches and leaves each stop. */
data class StopTime(val arriveAt: Long, val leaveAt: Long)

object Schedule {
    /** Times at every stop for a trip that begins (at the first stop) at [startAt]. */
    fun times(stops: List<Stop>, path: RoutePath, startAt: Long): List<StopTime> {
        var clock = startAt
        return stops.mapIndexed { i, stop ->
            if (i > 0) clock += (path.legs[i - 1].durationSeconds * 1000).toLong()
            val arrive = clock
            if (i < stops.lastIndex) clock += stop.waitMinutes * 60_000L
            StopTime(arrive, clock)
        }
    }

    fun totalMillis(stops: List<Stop>, path: RoutePath): Long =
        (path.durationSeconds * 1000).toLong() + stops.dropLast(1).sumOf { it.waitMinutes } * 60_000L

    /** When the trip has to begin so it reaches the last stop at [arriveBy]. */
    fun startFor(stops: List<Stop>, path: RoutePath, arriveBy: Long): Long = arriveBy - totalMillis(stops, path)
}
