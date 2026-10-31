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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Street routing through the free OSRM servers (OpenStreetMap data).
 * Each piece of road comes back with its own travel time, so movement follows the street's speed.
 */
object Router {

    private const val FOSSGIS = "https://routing.openstreetmap.de"
    private const val OSRM_DEMO = "https://router.project-osrm.org"

    /** Google (with live traffic) when chosen in Settings, otherwise, or if Google fails, the free OSRM servers. */
    suspend fun route(context: Context, stops: List<LatLon>, mode: TravelMode): RoutePath = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        if (prefs.useGoogleRoutes && prefs.hasGoogleKey) {
            try {
                return@withContext GoogleRoutes.route(GoogleApi(context), stops, mode)
            } catch (e: Exception) {
                Log.w("Router", "Google routing failed, using OSRM", e)
            }
        }
        route(stops, mode)
    }

    suspend fun route(stops: List<LatLon>, mode: TravelMode): RoutePath = withContext(Dispatchers.IO) {
        require(stops.size >= 2) { "A route needs at least two stops" }
        val coords = stops.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val query = "$coords?overview=full&geometries=geojson&annotations=duration,distance&steps=false"
        val urls = when (mode) {
            TravelMode.CAR -> listOf("$FOSSGIS/routed-car/route/v1/driving/$query", "$OSRM_DEMO/route/v1/driving/$query")
            TravelMode.WALK -> listOf("$FOSSGIS/routed-foot/route/v1/foot/$query")
        }
        var lastError: Exception? = null
        for (url in urls) {
            try {
                return@withContext parse(get(url))
            } catch (e: Exception) {
                // Includes a server that answers an HTML error page (not JSON): try the next one.
                Log.w("Router", "OSRM server failed: ${url.substringBefore("/route/")}", e)
                lastError = e
            }
        }
        throw lastError ?: IOException("No route")
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

    /** OSRM explains failures in a JSON body, so a non-2xx answer with a body is handed to [parse] too. */
    private fun get(url: String): String =
        Http.request(url).let { if (it.ok || it.body.isNotBlank()) it.body else throw HttpException(it.code, it.body) }
}
