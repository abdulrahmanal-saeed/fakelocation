package com.abdulrahman.fakelocation.data

enum class TravelMode { CAR, WALK }

/** One stop on a route. [waitMinutes] is how long to stay there before moving on. */
data class Stop(val id: Long, val name: String, val position: LatLon, val waitMinutes: Int)

/** What a fake trip does at one traffic light: what the setting says (some red, some green), always stop, or never. */
enum class LightRule { AUTO, STOP, GO }

/**
 * A traffic light on the path, at [point] (an index into the path's points). [waitSeconds] is how long to stop when
 * the rule is [LightRule.STOP].
 */
data class TrafficLight(val point: Int, val rule: LightRule = LightRule.AUTO, val waitSeconds: Int = DEFAULT_WAIT_SECONDS) {
    companion object {
        const val DEFAULT_WAIT_SECONDS = 60
    }
}

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
    /** Traffic lights along the way (from OpenStreetMap), in order. */
    val lights: List<TrafficLight> = emptyList(),
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
            lights = lights.asReversed().map { it.copy(point = last - it.point) },
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
            lights = lights + next.lights.map { it.copy(point = it.point + offset) },
        )
    }

    /**
     * The path with a stop at some traffic lights: [waits] maps a point index to the seconds spent standing there. The
     * car slows down over the last [rampMeters] before the light and speeds up again over as many after it (taking
     * twice as long there, as braking to a stop and pulling away do), then the light point is repeated with the wait
     * as a piece of road of no length. Each leg gets longer by what was added, so arrival times move with it.
     */
    fun withStops(waits: Map<Int, Double>, rampMeters: Double = 40.0): RoutePath {
        // A light right at a stop of the trip is part of that stop's wait.
        val ends = legs.flatMap { listOf(it.firstPoint, it.lastPoint) }.toSet()
        val stops = waits.filter { (k, w) -> w > 0 && k in 1 until points.lastIndex && k !in ends }
        if (stops.isEmpty()) return this
        val along = DoubleArray(points.size)
        for (k in 1 until points.size) along[k] = along[k - 1] + distanceMeters(points[k - 1], points[k])
        // Never ramp across a stop of the trip itself (a leg's ends).
        val legOf = IntArray(points.size)
        legs.forEachIndexed { i, leg -> for (k in leg.firstPoint..leg.lastPoint) legOf[k] = i }
        val cuts = ArrayList<Double>()
        val slow = ArrayList<ClosedFloatingPointRange<Double>>()
        for ((k, _) in stops) {
            val leg = legs[legOf[k]]
            val from = maxOf(along[k] - rampMeters, along[leg.firstPoint])
            val to = minOf(along[k] + rampMeters, along[leg.lastPoint])
            cuts += from
            cuts += to
            slow += from..to
        }
        val newPoints = ArrayList<LatLon>()
        val newSeconds = ArrayList<Double>()
        val newIndex = IntArray(points.size)
        val added = DoubleArray(legs.size)
        newPoints += points[0]
        for (k in 0 until points.lastIndex) {
            newIndex[k] = newPoints.lastIndex
            val a = along[k]
            val b = along[k + 1]
            val length = b - a
            // Split this piece of road where a slow-down or speed-up starts or ends.
            val inside = cuts.filter { it > a + 0.5 && it < b - 0.5 }.distinct().sorted()
            var last = a
            for (c in inside + b) {
                val share = if (length > 0) (c - last) / length else 1.0
                var seconds = segmentSeconds[k] * share
                val mid = (last + c) / 2
                if (slow.any { mid in it }) {
                    added[legOf[k]] += seconds
                    seconds *= 2
                }
                newSeconds += seconds
                newPoints += if (c == b) points[k + 1] else interpolate(points[k], points[k + 1], (c - a) / length)
                last = c
            }
            // Standing at the light.
            stops[k + 1]?.let { wait ->
                newPoints += points[k + 1]
                newSeconds += wait
                added[legOf[k + 1]] += wait
            }
        }
        newIndex[points.lastIndex] = newPoints.lastIndex
        return RoutePath(
            points = newPoints,
            segmentSeconds = newSeconds,
            legs = legs.mapIndexed { i, leg ->
                leg.copy(durationSeconds = leg.durationSeconds + added[i], firstPoint = newIndex[leg.firstPoint], lastPoint = newIndex[leg.lastPoint])
            },
            trafficAware = trafficAware,
        )
    }

    private fun interpolate(a: LatLon, b: LatLon, f: Double) = LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
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
    /**
     * The path as the trip runs it: traffic slowdown (cars only, not already in the times), stops at red lights
     * ([redLightPercent] of the lights left on "auto" are red, see [redLights]) and the chosen speed.
     */
    fun travelPath(trafficPercent: Int, redLightPercent: Int = 0): RoutePath? {
        val path = path ?: return null
        val traffic = if (mode == TravelMode.CAR && !path.trafficAware) 1 + trafficPercent / 100.0 else 1.0
        return path.slower(traffic / speed).withStops(redLights(redLightPercent).mapValues { it.value / speed })
    }

    /**
     * Which lights are red on this trip and for how many seconds (by point index): the ones set to "stop", and of
     * those on "auto" about [percent] in 100, waiting 15 s to 1.5 min. Always the same for the same route, so working
     * the trip out again (a speed change, a restart) gives the same lights.
     */
    fun redLights(percent: Int): Map<Int, Double> {
        val path = path ?: return emptyMap()
        if (mode != TravelMode.CAR) return emptyMap()
        val red = HashMap<Int, Double>()
        for (light in path.lights) {
            when (light.rule) {
                LightRule.STOP -> red[light.point] = light.waitSeconds.toDouble()
                LightRule.GO -> Unit
                LightRule.AUTO -> {
                    val random = kotlin.random.Random(id * 7919 + light.point)
                    if (random.nextInt(100) < percent) red[light.point] = random.nextInt(15, 91).toDouble()
                }
            }
        }
        return red
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
