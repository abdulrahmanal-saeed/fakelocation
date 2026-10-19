package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.geo.Overpass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What OpenStreetMap knows about the road along a route: speed limit per piece of road (km/h, 0 = unknown),
 * speed cameras, toll gantries and the lane arrows before each turn. Filled in chunks as you drive.
 */
data class RoadInfo(
    val limits: IntArray,
    val camerasAlong: List<Double>,
    /** Toll gantries mapped near the route (position, name), for gates the app doesn't know yet. */
    val gantries: List<Pair<LatLon, String>> = emptyList(),
    /** Lanes before a step (by step index), for routes whose router didn't give them. */
    val stepLanes: Map<Int, List<Lane>> = emptyMap(),
    /** How far along the route this covers. */
    val coveredTo: Double = 0.0,
    /** Lanes in our direction per piece of road (0 = unknown). */
    val laneCounts: IntArray = IntArray(0),
) {
    fun limitAt(segment: Int): Int = limits.getOrElse(segment) { 0 }

    fun laneCountAt(segment: Int): Int = laneCounts.getOrElse(segment) { 0 }

    /** The next camera ahead of [along] metres, as distance from here. */
    fun nextCamera(along: Double): Double? = camerasAlong.firstOrNull { it > along - 10 }?.minus(along)

    /** This plus a newer chunk. */
    fun merge(other: RoadInfo): RoadInfo = RoadInfo(
        IntArray(limits.size) { i -> other.limits.getOrElse(i) { 0 }.takeIf { it > 0 } ?: limits[i] },
        (camerasAlong + other.camerasAlong).distinctBy { (it / 20).roundToInt() }.sorted(),
        (gantries + other.gantries).distinctBy { it.first },
        stepLanes + other.stepLanes,
        maxOf(coveredTo, other.coveredTo),
        IntArray(maxOf(laneCounts.size, other.laneCounts.size)) { i ->
            other.laneCounts.getOrElse(i) { 0 }.takeIf { it > 0 } ?: laneCounts.getOrElse(i) { 0 }
        },
    )

    companion object {
        fun empty(route: NavRoute) = RoadInfo(IntArray((route.points.size - 1).coerceAtLeast(0)), emptyList())
    }
}

/** A road from OpenStreetMap with its limit (0 = none) and lane arrows per direction. */
data class MappedWay(
    val limitKmh: Int,
    val shape: List<LatLon>,
    val lanesForward: String? = null,
    val lanesBackward: String? = null,
    /** Number of lanes each way (0 = unknown). */
    val countForward: Int = 0,
    val countBackward: Int = 0,
)

/**
 * Looks up speed limits, cameras, gantries and lanes along a route from OpenStreetMap (Overpass API).
 * Coverage depends on what volunteers have mapped: unknown stretches simply show nothing.
 */
object RoadInfoFetcher {

    const val CHUNK_METERS = 20_000.0

    /** Road info for the part of [route] between [fromAlong] and [toAlong] metres. */
    suspend fun fetch(route: NavRoute, fromAlong: Double = 0.0, toAlong: Double = CHUNK_METERS): RoadInfo = withContext(Dispatchers.IO) {
        val part = route.slice(fromAlong.coerceAtLeast(0.0), toAlong.coerceAtMost(route.length))
        if (part.size < 2) return@withContext RoadInfo.empty(route).copy(coveredTo = toAlong)
        // Keep the shape faithful (bends included) but short enough for one query.
        var tolerance = 5.0
        var line = simplify(part, tolerance)
        while (line.size > 400) {
            tolerance *= 1.6
            line = simplify(part, tolerance)
        }
        val coords = line.joinToString(",") { String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon) }
        val query = "[out:json][timeout:40];(" +
            "way(around:20,$coords)[highway][~\"^(maxspeed|lanes(:forward|:backward)?|turn:lanes(:forward|:backward)?)$\"~\".\"];" +
            "node(around:30,$coords)[highway=speed_camera];" +
            "node(around:25,$coords)[highway=toll_gantry];" +
            ");out tags geom;"
        parse(route, Overpass.query(query), fromAlong, toAlong)
    }

    fun parse(route: NavRoute, json: JSONObject, fromAlong: Double = 0.0, toAlong: Double = Double.MAX_VALUE): RoadInfo {
        val ways = ArrayList<MappedWay>()
        val cameras = ArrayList<LatLon>()
        val gantries = ArrayList<Pair<LatLon, String>>()
        val elements = json.optJSONArray("elements")
        if (elements != null) for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags")
            when (e.optString("type")) {
                "way" -> {
                    val geometry = e.optJSONArray("geometry") ?: continue
                    val shape = (0 until geometry.length()).map {
                        val g = geometry.getJSONObject(it)
                        LatLon(g.getDouble("lat"), g.getDouble("lon"))
                    }
                    val limit = parseMaxspeed(tags?.optString("maxspeed").orEmpty()) ?: 0
                    val plain = tags?.optString("turn:lanes")?.takeIf { it.isNotBlank() }
                    val forward = tags?.optString("turn:lanes:forward")?.takeIf { it.isNotBlank() } ?: plain
                    val backward = tags?.optString("turn:lanes:backward")?.takeIf { it.isNotBlank() }
                    val oneway = tags?.optString("oneway") == "yes" || tags?.optString("junction") == "roundabout" ||
                        tags?.optString("highway").orEmpty().startsWith("motorway")
                    val total = tags?.optString("lanes")?.toIntOrNull() ?: 0
                    fun count(key: String, turn: String?, half: Boolean) =
                        tags?.optString(key)?.toIntOrNull() ?: turn?.split('|')?.size ?: if (half && total >= 2) total / 2 else 0
                    val countForward = if (oneway) total.takeIf { it > 0 } ?: forward?.split('|')?.size ?: 0 else count("lanes:forward", forward, true)
                    val countBackward = if (oneway) 0 else count("lanes:backward", backward, true)
                    ways += MappedWay(limit, shape, forward, backward, countForward.coerceIn(0, 8), countBackward.coerceIn(0, 8))
                }
                "node" -> {
                    val p = LatLon(e.getDouble("lat"), e.getDouble("lon"))
                    if (tags?.optString("highway") == "toll_gantry") {
                        gantries += p to (tags.optString("name:en").ifBlank { tags.optString("name") })
                    } else {
                        cameras += p
                    }
                }
            }
        }
        val first = route.along.indexOfFirst { it >= fromAlong }.coerceAtLeast(0)
        val last = route.along.indexOfLast { it <= toAlong }.coerceAtLeast(first)
        return RoadInfo(
            assignLimits(route, ways.filter { it.limitKmh > 0 }, first, last),
            camerasAlong(route, cameras),
            gantries,
            stepLanes(route, ways.filter { it.lanesForward != null || it.lanesBackward != null }, fromAlong, toAlong),
            toAlong,
            assignLaneCounts(route, ways.filter { it.countForward > 0 || it.countBackward > 0 }, first, last),
        )
    }

    /** "60" → 60, "30 mph" → 48. Anything else (e.g. "AE:urban", "signals") → null. */
    fun parseMaxspeed(value: String): Int? {
        val v = value.trim().lowercase(Locale.US)
        Regex("""^(\d{1,3})$""").find(v)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 5..200 } }
        Regex("""^(\d{1,3})\s*mph$""").find(v)?.let { return (it.groupValues[1].toInt() * 1.609).roundToInt() }
        return null
    }

    /** For each piece of the route (in [from]..[to]), the limit of the mapped road it runs along. */
    fun assignLimits(route: NavRoute, ways: List<MappedWay>, from: Int = 0, to: Int = route.points.size - 1): IntArray {
        val limits = IntArray((route.points.size - 1).coerceAtLeast(0))
        if (ways.isEmpty()) return limits
        for (i in from until minOf(to + 1, limits.size)) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
            val heading = bearing(a, b)
            var best = Double.MAX_VALUE
            var bestLimit = 0
            for (way in ways) {
                for (k in 0 until way.shape.size - 1) {
                    val d = project(mid, way.shape[k], way.shape[k + 1]).first
                    if (d >= best || d > 15.0) continue
                    // A crossing road isn't the road we're on.
                    if (axisDiff(heading, bearing(way.shape[k], way.shape[k + 1])) > 35) continue
                    best = d
                    bestLimit = way.limitKmh
                }
            }
            limits[i] = bestLimit
        }
        return limits
    }

    /** For each piece of the route, how many lanes the mapped road has in our direction. */
    fun assignLaneCounts(route: NavRoute, ways: List<MappedWay>, from: Int = 0, to: Int = route.points.size - 1): IntArray {
        val counts = IntArray((route.points.size - 1).coerceAtLeast(0))
        if (ways.isEmpty()) return counts
        for (i in from until minOf(to + 1, counts.size)) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
            val heading = bearing(a, b)
            var best = 15.0
            for (way in ways) for (k in 0 until way.shape.size - 1) {
                val d = project(mid, way.shape[k], way.shape[k + 1]).first
                if (d >= best) continue
                val diff = angleDiff(heading, bearing(way.shape[k], way.shape[k + 1]))
                val n = when {
                    diff < 35 -> way.countForward
                    diff > 145 -> way.countBackward
                    else -> 0
                }
                if (n <= 0) continue
                best = d
                counts[i] = n
            }
        }
        return counts
    }

    /**
     * Lane arrows before each turn: the mapped road we're on ~25 m before the junction, read in our direction
     * (turn:lanes / :forward / :backward), with the lanes that lead our way marked valid.
     */
    fun stepLanes(route: NavRoute, ways: List<MappedWay>, fromAlong: Double, toAlong: Double): Map<Int, List<Lane>> {
        if (ways.isEmpty()) return emptyMap()
        val result = HashMap<Int, List<Lane>>()
        route.steps.forEachIndexed { index, step ->
            if (step.maneuver == Maneuver.DEPART || step.maneuver == Maneuver.ARRIVE || step.lanes.isNotEmpty()) return@forEachIndexed
            val at = route.along[step.pointIndex]
            if (at < fromAlong || at > toAlong || at < 40) return@forEachIndexed
            val probe = route.pointAt(at - 25)
            val heading = bearing(route.pointAt(at - 45), route.pointAt(at - 10))
            var best = 12.0
            var tag: String? = null
            for (way in ways) for (k in 0 until way.shape.size - 1) {
                val d = project(probe, way.shape[k], way.shape[k + 1]).first
                if (d >= best) continue
                val diff = angleDiff(heading, bearing(way.shape[k], way.shape[k + 1]))
                val value = when {
                    diff < 40 -> way.lanesForward
                    diff > 140 -> way.lanesBackward
                    else -> null
                } ?: continue
                best = d
                tag = value
            }
            val lanes = tag?.let { lanesFor(it, step.maneuver) }
            if (lanes != null) result[index] = lanes
        }
        return result
    }

    /** "left|through|through;right" read for [m]: null when no lane leads that way (then the tag is likely stale). */
    fun lanesFor(tag: String, m: Maneuver): List<Lane>? {
        val lanes = tag.split('|').map { lane -> lane.split(';').map(::token).ifEmpty { listOf("straight") } }
        if (lanes.size < 2) return null
        val wanted = wantedTokens(m)
        val pick = wanted.firstOrNull { w -> lanes.any { w in it } } ?: return null
        return lanes.map { Lane(it, pick in it) }
    }

    private fun token(raw: String) = when (raw.trim()) {
        "left" -> "left"
        "slight_left" -> "slight left"
        "sharp_left" -> "sharp left"
        "right" -> "right"
        "slight_right" -> "slight right"
        "sharp_right" -> "sharp right"
        "reverse" -> "uturn"
        else -> "straight" // through, none, merge_to_*, empty
    }

    private fun wantedTokens(m: Maneuver) = when (m) {
        Maneuver.LEFT -> listOf("left", "sharp left", "slight left")
        Maneuver.SHARP_LEFT -> listOf("sharp left", "left")
        Maneuver.SLIGHT_LEFT -> listOf("slight left", "left", "straight")
        Maneuver.RIGHT -> listOf("right", "sharp right", "slight right")
        Maneuver.SHARP_RIGHT -> listOf("sharp right", "right")
        Maneuver.SLIGHT_RIGHT -> listOf("slight right", "right", "straight")
        Maneuver.UTURN -> listOf("uturn", "left")
        else -> listOf("straight")
    }

    /** Distance along the route of each camera that sits on it (within 30 m), sorted. */
    fun camerasAlong(route: NavRoute, cameras: List<LatLon>): List<Double> = cameras.mapNotNull { alongOf(route, it, 30.0) }.sorted()

    /** How far along [route] the point nearest to [p] is, or null if the route never comes within [maxMeters]. */
    fun alongOf(route: NavRoute, p: LatLon, maxMeters: Double): Double? {
        var best = Double.MAX_VALUE
        var at = 0.0
        for (i in 0 until route.points.size - 1) {
            val a = route.points[i]
            val b = route.points[i + 1]
            // Cheap reject: a segment wholly north or south of p by ~5 km can't be close.
            if ((a.lat - p.lat > 0.05 && b.lat - p.lat > 0.05) || (a.lat - p.lat < -0.05 && b.lat - p.lat < -0.05)) continue
            val (d, t) = project(p, a, b)
            if (d < best) {
                best = d
                at = route.along[i] + (route.along[i + 1] - route.along[i]) * t
            }
        }
        return at.takeIf { best <= maxMeters }
    }

    /** Douglas–Peucker: drop points that stay within [tolerance] metres of the simplified line. */
    fun simplify(points: List<LatLon>, tolerance: Double): List<LatLon> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var worst = -1.0
            var index = -1
            for (i in s + 1 until e) {
                val d = project(points[i], points[s], points[e]).first
                if (d > worst) {
                    worst = d
                    index = i
                }
            }
            if (worst > tolerance && index > 0) {
                keep[index] = true
                stack.addLast(s to index)
                stack.addLast(index to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance from [p] to segment a→b in metres, and where along the segment (0..1) the closest point is. */
    private fun project(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val cosLat = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * 111_320.0 * cosLat
        val ay = (a.lat - p.lat) * 110_540.0
        val bx = (b.lon - p.lon) * 111_320.0 * cosLat
        val by = (b.lat - p.lat) * 110_540.0
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        val x = ax + dx * t
        val y = ay + dy * t
        return sqrt(x * x + y * y) to t
    }

    private fun bearing(a: LatLon, b: LatLon): Double {
        val dx = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    /** 0..180 between two headings. */
    private fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540) % 360) - 180)

    /** 0..90 between two lines, ignoring direction. */
    private fun axisDiff(a: Double, b: Double): Double = angleDiff(a, b).let { if (it > 90) 180 - it else it }
}
