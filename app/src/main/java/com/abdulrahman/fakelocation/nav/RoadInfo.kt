package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.geo.Overpass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Speed limits along the route (km/h per piece of road, 0 = unknown) and where the speed cameras are. */
data class RoadInfo(
    val limits: IntArray,
    val camerasAlong: List<Double>,
    /** Toll gantries mapped near the route (position, name), for gates the app doesn't know yet. */
    val gantries: List<Pair<LatLon, String>> = emptyList(),
) {
    fun limitAt(segment: Int): Int = limits.getOrElse(segment) { 0 }

    /** The next camera ahead of [along] metres, as distance from here. */
    fun nextCamera(along: Double): Double? = camerasAlong.firstOrNull { it > along - 10 }?.minus(along)
}

/** A road from OpenStreetMap with a posted limit. */
data class LimitedWay(val limitKmh: Int, val shape: List<LatLon>)

/**
 * Looks up speed limits and speed cameras along a route from OpenStreetMap (Overpass API).
 * Coverage depends on what volunteers have mapped: unknown stretches simply show no limit.
 */
object RoadInfoFetcher {

    suspend fun fetch(route: NavRoute): RoadInfo = withContext(Dispatchers.IO) {
        val step = (route.points.size / 150).coerceAtLeast(1)
        val line = (route.points.filterIndexed { i, _ -> i % step == 0 } + route.points.last())
            .joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
        val query = "[out:json][timeout:25];(way(around:15,$line)[highway][maxspeed];" +
            "node(around:30,$line)[highway=speed_camera];node(around:25,$line)[highway=toll_gantry];);out tags geom;"
        parse(route, Overpass.query(query))
    }

    fun parse(route: NavRoute, json: JSONObject): RoadInfo {
        val ways = ArrayList<LimitedWay>()
        val cameras = ArrayList<LatLon>()
        val gantries = ArrayList<Pair<LatLon, String>>()
        val elements = json.optJSONArray("elements") ?: return RoadInfo(IntArray(route.points.size), emptyList())
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            when (e.optString("type")) {
                "way" -> {
                    val limit = parseMaxspeed(e.optJSONObject("tags")?.optString("maxspeed").orEmpty()) ?: continue
                    val geometry = e.optJSONArray("geometry") ?: continue
                    ways += LimitedWay(limit, (0 until geometry.length()).map {
                        val g = geometry.getJSONObject(it)
                        LatLon(g.getDouble("lat"), g.getDouble("lon"))
                    })
                }
                "node" -> {
                    val tags = e.optJSONObject("tags")
                    val p = LatLon(e.getDouble("lat"), e.getDouble("lon"))
                    if (tags?.optString("highway") == "toll_gantry") {
                        gantries += p to (tags.optString("name:en").ifBlank { tags.optString("name") })
                    } else {
                        cameras += p
                    }
                }
            }
        }
        return RoadInfo(assignLimits(route, ways), camerasAlong(route, cameras), gantries)
    }

    /** "60" → 60, "30 mph" → 48. Anything else (e.g. "EG:urban", "signals") → null. */
    fun parseMaxspeed(value: String): Int? {
        val v = value.trim().lowercase(Locale.US)
        Regex("""^(\d{1,3})$""").find(v)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 5..200 } }
        Regex("""^(\d{1,3})\s*mph$""").find(v)?.let { return (it.groupValues[1].toInt() * 1.609).roundToInt() }
        return null
    }

    /** For each piece of the route, the limit of the mapped road it runs along (within 12 m and same direction-ish). */
    fun assignLimits(route: NavRoute, ways: List<LimitedWay>): IntArray {
        val limits = IntArray((route.points.size - 1).coerceAtLeast(0))
        if (ways.isEmpty()) return limits
        for (i in limits.indices) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
            var best = Double.MAX_VALUE
            var bestLimit = 0
            for (way in ways) {
                for (k in 0 until way.shape.size - 1) {
                    val d = distanceToSegment(mid, way.shape[k], way.shape[k + 1])
                    if (d < best) {
                        best = d
                        bestLimit = way.limitKmh
                    }
                }
            }
            if (best <= 12.0) limits[i] = bestLimit
        }
        return limits
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

    private fun distanceToSegment(p: LatLon, a: LatLon, b: LatLon): Double = project(p, a, b).first

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

}
