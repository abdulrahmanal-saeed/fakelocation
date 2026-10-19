package com.abdulrahman.fakelocation.geo.google

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Leg
import com.abdulrahman.fakelocation.data.RoutePath
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.distanceMeters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Street routing through the Google Routes API, with live traffic for cars. */
object GoogleRoutes {

    private const val URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELDS = "routes.legs.distanceMeters,routes.legs.duration,routes.legs.steps.distanceMeters," +
        "routes.legs.steps.staticDuration,routes.legs.steps.polyline.encodedPolyline"

    fun route(api: GoogleApi, stops: List<LatLon>, mode: TravelMode): RoutePath {
        fun waypoint(p: LatLon) = JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lon)))
        val body = JSONObject()
            .put("origin", waypoint(stops.first()))
            .put("destination", waypoint(stops.last()))
            .put("intermediates", JSONArray(stops.subList(1, stops.size - 1).map(::waypoint)))
            .put("travelMode", if (mode == TravelMode.CAR) "DRIVE" else "WALK")
            .put("languageCode", "en")
        if (mode == TravelMode.CAR) body.put("routingPreference", "TRAFFIC_AWARE")
        return parse(api.post(URL, body, FIELDS), trafficAware = mode == TravelMode.CAR)
    }

    /**
     * Builds the path from each step's road shape. A step's time is spread over its pieces by length, then each
     * leg is scaled to Google's traffic-aware duration, so busy stretches come out slower.
     */
    fun parse(json: JSONObject, trafficAware: Boolean): RoutePath {
        val route = json.optJSONArray("routes")?.optJSONObject(0) ?: throw IOException("Google found no route")
        val legsJson = route.getJSONArray("legs")
        val points = ArrayList<LatLon>()
        val seconds = ArrayList<Double>()
        val legs = ArrayList<Leg>()
        for (l in 0 until legsJson.length()) {
            val leg = legsJson.getJSONObject(l)
            val first = (points.size - 1).coerceAtLeast(0)
            val legStartSeconds = seconds.size
            val steps = leg.optJSONArray("steps") ?: JSONArray()
            for (s in 0 until steps.length()) {
                val step = steps.getJSONObject(s)
                val shape = Polyline.decode(step.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty())
                if (shape.isEmpty()) continue
                val stepSeconds = GoogleApi.seconds(step.optString("staticDuration"))
                val pieces = shape.zipWithNext { a, b -> distanceMeters(a, b) }
                val length = pieces.sum()
                val startIndex = if (points.isNotEmpty() && points.last() == shape.first()) 1 else 0
                if (startIndex == 0 && points.isNotEmpty()) {
                    // Join a gap between steps with a zero-time piece.
                    seconds += 0.0
                }
                points += shape.subList(startIndex, shape.size)
                pieces.forEach { seconds += if (length > 0) stepSeconds * it / length else 0.0 }
            }
            if (points.isEmpty()) throw IOException("Google route has no shape")
            val last = points.lastIndex
            val duration = GoogleApi.seconds(leg.optString("duration"))
            val raw = seconds.subList(legStartSeconds, seconds.size)
            val sum = raw.sum()
            if (sum > 0 && duration > 0) for (i in raw.indices) raw[i] = raw[i] * duration / sum
            legs += Leg(leg.optDouble("distanceMeters", 0.0), if (duration > 0) duration else sum, first, last)
        }
        if (seconds.size != points.size - 1) throw IOException("Unexpected Google route shape")
        return RoutePath(points, seconds, legs, trafficAware)
    }
}
