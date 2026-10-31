package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.RoutePath
import com.abdulrahman.fakelocation.data.TrafficLight
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.nav.RoadInfoFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Traffic lights along a fake trip's path, from OpenStreetMap (nodes tagged highway=traffic_signals). Only lights
 * that sit on the path itself count; one junction with a light on every corner is one light.
 */
object TrafficLights {

    /** A light this close to a point of the path is on it. Lights on the other side of a divided road are further. */
    const val ON_PATH_METERS = 12.0
    /** Lights closer together than this along the path are the same junction. */
    const val SAME_JUNCTION_METERS = 45.0
    /** The light is stood at the path's nearest corner point, at most this far from it. */
    const val TO_CORNER_METERS = 35.0
    /** Not at the trip's own stops (the wait there already covers it). */
    const val FROM_STOPS_METERS = 30.0

    /** Looks them up (network: call off the main thread). */
    suspend fun find(path: RoutePath): List<TrafficLight> = withContext(Dispatchers.IO) {
        val nodes = ArrayList<LatLon>()
        for (part in parts(path.points)) {
            val coords = part.joinToString(",") { String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon) }
            val json = Overpass.query("[out:json][timeout:30];node(around:20,$coords)[highway=traffic_signals];out;")
            nodes += nodesOf(json)
        }
        snap(path, nodes)
    }

    /** The path in pieces short enough for one query each, keeping its shape within a few metres. */
    private fun parts(points: List<LatLon>): List<List<LatLon>> {
        val out = ArrayList<List<LatLon>>()
        var start = 0
        while (start < points.lastIndex) {
            var end = start
            var length = 0.0
            while (end < points.lastIndex && length < 15_000) {
                length += distanceMeters(points[end], points[end + 1])
                end++
            }
            var tolerance = 4.0
            var line = RoadInfoFetcher.simplify(points.subList(start, end + 1), tolerance)
            while (line.size > 400) {
                tolerance *= 1.6
                line = RoadInfoFetcher.simplify(points.subList(start, end + 1), tolerance)
            }
            out += line
            start = end
        }
        return out
    }

    fun nodesOf(json: JSONObject): List<LatLon> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        return (0 until elements.length()).mapNotNull { i ->
            val e = elements.optJSONObject(i) ?: return@mapNotNull null
            if (e.optString("type") != "node" || !e.has("lat")) return@mapNotNull null
            LatLon(e.getDouble("lat"), e.getDouble("lon"))
        }
    }

    /** Each light that's on the path, as the nearest point of the path, in order along it. */
    fun snap(path: RoutePath, lights: List<LatLon>): List<TrafficLight> {
        val points = path.points
        if (points.size < 3 || lights.isEmpty()) return emptyList()
        val along = DoubleArray(points.size)
        for (k in 1 until points.size) along[k] = along[k - 1] + distanceMeters(points[k - 1], points[k])
        val stopsAlong = path.legs.flatMap { listOf(along[it.firstPoint], along[it.lastPoint]) }
        val hits = lights.mapNotNull { light ->
            // The nearest piece of road within reach, then its end closest to the light (the junction).
            var best = -1
            var bestDistance = ON_PATH_METERS
            for (j in 0 until points.lastIndex) {
                val a = points[j]
                val b = points[j + 1]
                if ((a.lat - light.lat > 0.002 && b.lat - light.lat > 0.002) || (light.lat - a.lat > 0.002 && light.lat - b.lat > 0.002)) continue
                val (d, t) = project(light, a, b)
                if (d < bestDistance) {
                    bestDistance = d
                    best = if (t < 0.5) j else j + 1
                }
            }
            best.takeIf { it in 1 until points.lastIndex && distanceMeters(points[it], light) <= TO_CORNER_METERS }
        }.distinct().sorted()
        val kept = ArrayList<Int>()
        for (k in hits) {
            if (stopsAlong.any { kotlin.math.abs(it - along[k]) < FROM_STOPS_METERS }) continue
            if (kept.isNotEmpty() && along[k] - along[kept.last()] < SAME_JUNCTION_METERS) continue
            kept += k
        }
        return kept.map { TrafficLight(it) }
    }

    /** Distance from [p] to the segment a→b in metres, and how far along it (0..1) the closest point is. */
    private fun project(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val k = Math.cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * 111_320.0 * k
        val ay = (a.lat - p.lat) * 110_540.0
        val bx = (b.lon - p.lon) * 111_320.0 * k
        val by = (b.lat - p.lat) * 110_540.0
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        return Math.hypot(ax + dx * t, ay + dy * t) to t
    }
}
