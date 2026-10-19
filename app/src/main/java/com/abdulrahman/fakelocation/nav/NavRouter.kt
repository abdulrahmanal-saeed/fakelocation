package com.abdulrahman.fakelocation.nav

import android.content.Context
import android.util.Log
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.geo.Nominatim
import com.abdulrahman.fakelocation.geo.google.GoogleApi
import com.abdulrahman.fakelocation.geo.google.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Routes with turn-by-turn steps for real navigation: Google (live traffic) when chosen, otherwise OSRM. */
object NavRouter {

    suspend fun route(context: Context, from: LatLon, to: LatLon, mode: TravelMode): NavRoute = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        if (prefs.useGoogleRoutes && prefs.hasGoogleKey) {
            try {
                return@withContext google(GoogleApi(context), from, to, mode)
            } catch (e: Exception) {
                Log.w("NavRouter", "Google navigation route failed, using OSRM", e)
            }
        }
        osrm(from, to, mode)
    }

    private fun osrm(from: LatLon, to: LatLon, mode: TravelMode): NavRoute {
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", from.lon, from.lat, to.lon, to.lat)
        val path = if (mode == TravelMode.CAR) "routed-car/route/v1/driving" else "routed-foot/route/v1/foot"
        val url = "https://routing.openstreetmap.de/$path/$coords?overview=full&geometries=geojson&steps=true"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", Nominatim.USER_AGENT)
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }
                ?: throw IOException("HTTP $code")
            return parseOsrm(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    fun parseOsrm(json: JSONObject): NavRoute {
        if (json.optString("code") != "Ok") throw IOException("Routing failed: " + json.optString("message", json.optString("code")))
        val route = json.getJSONArray("routes").getJSONObject(0)
        val points = ArrayList<LatLon>()
        val steps = ArrayList<NavStep>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val stepsJson = legs.getJSONObject(l).getJSONArray("steps")
            for (s in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(s)
                val shape = step.getJSONObject("geometry").getJSONArray("coordinates").toPoints()
                val start = if (points.isNotEmpty() && shape.isNotEmpty() && points.last() == shape.first()) points.lastIndex else points.size
                val maneuver = step.getJSONObject("maneuver")
                val instruction = Instructions.fromOsrm(
                    maneuver.optString("type"), maneuver.optString("modifier"), step.optString("name"),
                    maneuver.optInt("exit", 0), step.optString("ref")
                )
                val lanes = step.optJSONArray("intersections")?.optJSONObject(0)?.optJSONArray("lanes")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val lane = arr.getJSONObject(i)
                        val dirs = lane.optJSONArray("indications")
                        Lane((0 until (dirs?.length() ?: 0)).map { dirs!!.getString(it) }, lane.optBoolean("valid"))
                    }
                }.orEmpty()
                steps += NavStep(instruction.maneuver, instruction.text, instruction.spoken, start, step.optString("name").ifBlank { step.optString("ref") }, lanes)
                points.appendShape(shape)
            }
        }
        if (points.size < 2) throw IOException("Route has no shape")
        val fixed = steps.map { it.copy(pointIndex = it.pointIndex.coerceIn(0, points.lastIndex)) }
        return NavRoute(points, fixed, route.getDouble("distance"), route.getDouble("duration"))
    }

    private fun google(api: GoogleApi, from: LatLon, to: LatLon, mode: TravelMode): NavRoute {
        fun waypoint(p: LatLon) = JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lon)))
        val body = JSONObject()
            .put("origin", waypoint(from))
            .put("destination", waypoint(to))
            .put("travelMode", if (mode == TravelMode.CAR) "DRIVE" else "WALK")
            .put("languageCode", "en")
        if (mode == TravelMode.CAR) body.put("routingPreference", "TRAFFIC_AWARE")
        val json = api.post(
            "https://routes.googleapis.com/directions/v2:computeRoutes", body,
            "routes.distanceMeters,routes.duration,routes.legs.steps.distanceMeters,routes.legs.steps.polyline.encodedPolyline," +
                "routes.legs.steps.navigationInstruction"
        )
        return parseGoogle(json, trafficAware = mode == TravelMode.CAR)
    }

    fun parseGoogle(json: JSONObject, trafficAware: Boolean): NavRoute {
        val route = json.optJSONArray("routes")?.optJSONObject(0) ?: throw IOException("Google found no route")
        val points = ArrayList<LatLon>()
        val steps = ArrayList<NavStep>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val stepsJson = legs.getJSONObject(l).optJSONArray("steps") ?: JSONArray()
            for (s in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(s)
                val shape = Polyline.decode(step.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty())
                if (shape.isEmpty()) continue
                val start = if (points.isNotEmpty() && points.last() == shape.first()) points.lastIndex else points.size
                val nav = step.optJSONObject("navigationInstruction")
                val instruction = Instructions.fromGoogle(nav?.optString("maneuver").orEmpty(), nav?.optString("instructions").orEmpty())
                steps += NavStep(instruction.maneuver, instruction.text, instruction.spoken, start)
                points.appendShape(shape)
            }
        }
        if (points.size < 2) throw IOException("Google route has no shape")
        steps += NavStep(Maneuver.ARRIVE, Instructions.ARRIVE_TEXT, Instructions.ARRIVE_TEXT, points.lastIndex)
        return NavRoute(
            points, steps.map { it.copy(pointIndex = it.pointIndex.coerceIn(0, points.lastIndex)) },
            route.optDouble("distanceMeters", 0.0), GoogleApi.seconds(route.optString("duration")), trafficAware
        )
    }

    /** Adds a step's shape, skipping points that repeat the previous one. */
    private fun ArrayList<LatLon>.appendShape(shape: List<LatLon>) {
        for (p in shape) if (isEmpty() || last() != p) add(p)
    }

    private fun JSONArray.toPoints(): List<LatLon> = (0 until length()).map { i ->
        val c = getJSONArray(i)
        LatLon(c.getDouble(1), c.getDouble(0))
    }
}
