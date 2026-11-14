package com.abdulrahman.fakelocation.geo

import android.content.Context
import android.util.Log
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.geo.google.GoogleApi
import com.abdulrahman.fakelocation.geo.google.GoogleRoutes
import com.abdulrahman.fakelocation.data.Leg
import com.abdulrahman.fakelocation.data.RoutePath
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.geo.google.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Street routing on OpenStreetMap data: the OSRM servers first (own one from Settings, then the public ones), then
 * Valhalla if they all fail. Each piece of road comes back with its own travel time, so movement follows the
 * street's speed.
 */
object Router {

    /** Google (with live traffic) when chosen in Settings, otherwise, or if Google fails, OpenStreetMap. */
    suspend fun route(context: Context, stops: List<LatLon>, mode: TravelMode): RoutePath = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        if (prefs.useGoogleRoutes && prefs.hasGoogleKey && prefs.mapProvider.isGoogle) {
            try {
                return@withContext GoogleRoutes.route(GoogleApi(context), stops, mode)
            } catch (e: Exception) {
                Log.w("Router", "Google routing failed, using OSRM", e)
            }
        }
        route(stops, mode, prefs.osrmServer, prefs.valhallaServer)
    }

    suspend fun route(stops: List<LatLon>, mode: TravelMode, ownOsrm: String = "", ownValhalla: String = ""): RoutePath =
        withContext(Dispatchers.IO) {
            require(stops.size >= 2) { "A route needs at least two stops" }
            val attempts = RoutingServers.osrm(ownOsrm, mode).map { base -> base to { osrm(base, stops) } } +
                RoutingServers.valhalla(ownValhalla).map { url -> url to { valhalla(url, stops, mode) } }
            var lastError: Exception? = null
            for ((server, attempt) in attempts) {
                try {
                    return@withContext attempt()
                } catch (e: NoInternetException) {
                    throw e // every other server would fail the same way
                } catch (e: Exception) {
                    // Includes a server that answers an HTML error page (not JSON): try the next one.
                    Log.w("Router", "routing server failed: ${server.substringBefore("/route")}", e)
                    lastError = e
                }
            }
            throw lastError ?: IOException("No route")
        }

    /** One OSRM server ([base] from [RoutingServers.osrm]); used by Settings to check an own server. */
    fun osrm(base: String, stops: List<LatLon>): RoutePath {
        val coords = stops.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        return parse(get("$base/$coords?overview=full&geometries=geojson&annotations=duration,distance&steps=false"))
    }

    /** One Valhalla server ([url] from [RoutingServers.valhalla]). */
    fun valhalla(url: String, stops: List<LatLon>, mode: TravelMode): RoutePath {
        val body = JSONObject()
            .put("locations", JSONArray(stops.map { JSONObject().put("lat", it.lat).put("lon", it.lon).put("type", "break") }))
            .put("costing", if (mode == TravelMode.CAR) "auto" else "pedestrian")
            .put("directions_options", JSONObject().put("units", "kilometers"))
        return parseValhalla(Http.request(url, "POST", body.toString(), contentType = "application/json").requireOk())
    }

    /** Turns an OSRM response into a path. Segment times are scaled so each leg adds up to its official duration. */
    fun parse(body: String): RoutePath {
        val json = JSONObject(body)
        if (json.optString("code") != "Ok") throw IOException("Routing failed: " + json.optString("message", json.optString("code")))
        val route = json.getJSONArray("routes").getJSONObject(0)
        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = (0 until coords.length()).map { i ->
            val c = coords.getJSONArray(i)
            LatLon(c.getDouble(1), c.getDouble(0))
        }
        val legsJson = route.getJSONArray("legs")
        val seconds = ArrayList<Double>(points.size)
        val legs = ArrayList<Leg>(legsJson.length())
        var pointIndex = 0
        for (i in 0 until legsJson.length()) {
            val leg = legsJson.getJSONObject(i)
            val annotation = leg.getJSONObject("annotation").getJSONArray("duration")
            val raw = (0 until annotation.length()).map { annotation.getDouble(it) }
            val duration = leg.getDouble("duration")
            val sum = raw.sum()
            val scale = if (sum > 0) duration / sum else 0.0
            seconds += raw.map { it * scale }
            legs += Leg(leg.getDouble("distance"), duration, pointIndex, pointIndex + raw.size)
            pointIndex += raw.size
        }
        if (seconds.size != points.size - 1) throw IOException("Unexpected route shape")
        return RoutePath(points, seconds, legs)
    }

    /**
     * Turns a Valhalla response into a path. Valhalla times whole maneuvers, not pieces of road, so each maneuver's
     * time is spread over its shape by length; then, as with OSRM, each leg adds up to its official duration.
     */
    fun parseValhalla(body: String): RoutePath {
        val json = JSONObject(body)
        val trip = json.optJSONObject("trip") ?: throw IOException("Valhalla: " + json.optString("error", "no route"))
        val legsJson = trip.getJSONArray("legs")
        val points = ArrayList<LatLon>()
        val seconds = ArrayList<Double>()
        val legs = ArrayList<Leg>(legsJson.length())
        for (l in 0 until legsJson.length()) {
            val leg = legsJson.getJSONObject(l)
            val shape = Polyline.decode(leg.getString("shape"), precision = 6)
            if (shape.size < 2) throw IOException("Valhalla route has no shape")
            val raw = DoubleArray(shape.size - 1)
            val maneuvers = leg.getJSONArray("maneuvers")
            for (m in 0 until maneuvers.length()) {
                val man = maneuvers.getJSONObject(m)
                val from = man.optInt("begin_shape_index").coerceIn(0, raw.size)
                val to = man.optInt("end_shape_index").coerceIn(from, raw.size)
                if (to == from) continue
                val lengths = (from until to).map { distanceMeters(shape[it], shape[it + 1]) }
                val total = lengths.sum()
                val time = man.optDouble("time", 0.0)
                lengths.forEachIndexed { i, len -> raw[from + i] += if (total > 0) time * len / total else time / lengths.size }
            }
            val summary = leg.getJSONObject("summary")
            val duration = summary.optDouble("time", raw.sum())
            val sum = raw.sum()
            val scale = if (sum > 0) duration / sum else 0.0
            // A leg starts where the previous one ended: that shared point is listed once.
            val first = if (points.isEmpty()) 0 else points.lastIndex
            if (points.isEmpty()) points += shape.first()
            points += shape.drop(1)
            seconds += raw.map { it * scale }
            legs += Leg(summary.optDouble("length", 0.0) * 1000, duration, first, points.lastIndex)
        }
        return RoutePath(points, seconds, legs)
    }

    /** OSRM explains failures in a JSON body, so a non-2xx answer with a body is handed to [parse] too. */
    private fun get(url: String): String =
        Http.request(url).let { if (it.ok || it.body.isNotBlank()) it.body else throw HttpException(it.code, it.body) }
}
