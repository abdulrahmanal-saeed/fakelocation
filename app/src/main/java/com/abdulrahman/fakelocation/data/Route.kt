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

    /** The same streets driven the other way: last point first, legs in reverse order. */
    fun reversed(): RoutePath {
        val last = points.lastIndex
        return copy(
            points = points.asReversed().toList(),
            segmentSeconds = segmentSeconds.asReversed().toList(),
            legs = legs.asReversed().map { it.copy(firstPoint = last - it.lastPoint, lastPoint = last - it.firstPoint) },
        )
    }

    /**
     * This path followed by [next], which starts where this one ends (its first point is not repeated; when the two
     * ends are a few metres apart, [next]'s first piece of road simply starts from here).
     */
    fun then(next: RoutePath): RoutePath {
        val offset = points.lastIndex
        return RoutePath(
            points = points + next.points.drop(1),
            segmentSeconds = segmentSeconds + next.segmentSeconds,
            legs = legs + next.legs.map { it.copy(firstPoint = it.firstPoint + offset, lastPoint = it.lastPoint + offset) },
            trafficAware = trafficAware && next.trafficAware,
        )
    }
}

enum class TimingMode { NOW, START_AT, ARRIVE_BY }

/**
 * When a route runs. [days] repeats it every week on those days (bit 0 = Monday … bit 6 = Sunday, 0 = once), at the
 * clock time of [timeMillis].
 */
data class Timing(val mode: TimingMode = TimingMode.NOW, val timeMillis: Long? = null, val days: Int = 0) {
    val repeats get() = days != 0 && mode != TimingMode.NOW && timeMillis != null
}

data class Route(
    val id: Long,
    val name: String,
    val mode: TravelMode,
    val stops: List<Stop>,
    val path: RoutePath? = null,
    val timing: Timing = Timing(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** How fast the fake trip moves compared with each street's speed (1.0 = as normal, 2.0 = twice as fast). */
    val speed: Double = 1.0,
    /** After the last stop, come back the same way to the first one. */
    val returnTrip: Boolean = false,
    /** How many times in a row the whole trip (with the way back) is done. Only for trips that end where they start. */
    val laps: Int = 1,
) {
    /** The path as the trip runs it: traffic slowdown (cars only, not already in the times) and the chosen speed. */
    fun travelPath(trafficPercent: Int): RoutePath? {
        val path = path ?: return null
        val traffic = if (mode == TravelMode.CAR && !path.trafficAware) 1 + trafficPercent / 100.0 else 1.0
        return path.slower(traffic / speed)
    }

    /** Waiting time that counts towards the trip: every stop except the last one. */
    val waitMinutes get() = stops.dropLast(1).sumOf { it.waitMinutes }

    /** True when the last stop is (almost) where the first one is, so the trip can be done again straight away. */
    val isLoop get() = stops.size >= 2 && distanceMeters(stops.first().position, stops.last().position) < LOOP_METERS

    /** More than one lap only makes sense when each lap ends where the next one starts. */
    val canRepeat get() = returnTrip || isLoop

    /**
     * The trip as it actually runs: with the way back and every lap laid out as plain stops and one long path, so the
     * trip engine, alarms and screens need nothing special. The result has [returnTrip] off and one lap.
     */
    fun expanded(): Route {
        val path = path ?: return this
        if (!returnTrip && laps <= 1) return this
        val lapStops = if (returnTrip) stops + stops.asReversed().drop(1) else stops
        val lapPath = if (returnTrip) path.then(path.reversed()) else path
        val count = if (canRepeat) laps.coerceIn(1, MAX_LAPS) else 1
        val allStops = ArrayList<Stop>()
        var allPath = lapPath
        for (lap in 0 until count) {
            if (lap > 0) {
                // Where one lap ends the next begins: wait there as long as at the very start.
                allStops[allStops.lastIndex] = allStops.last().copy(waitMinutes = stops.first().waitMinutes)
                allPath = allPath.then(lapPath)
            }
            allStops += if (lap == 0) lapStops else lapStops.drop(1)
        }
        return copy(stops = allStops, path = allPath, returnTrip = false, laps = 1)
    }

    companion object {
        const val LOOP_METERS = 75.0
        const val MAX_LAPS = 10
    }
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
