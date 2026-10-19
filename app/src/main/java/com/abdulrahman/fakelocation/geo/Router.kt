package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Leg
import com.abdulrahman.fakelocation.data.RoutePath
import com.abdulrahman.fakelocation.data.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Street routing through the free OSRM servers (OpenStreetMap data).
 * Each piece of road comes back with its own travel time, so movement follows the street's speed.
 */
object Router {

    private const val FOSSGIS = "https://routing.openstreetmap.de"
    private const val OSRM_DEMO = "https://router.project-osrm.org"

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
            } catch (e: IOException) {
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

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", Nominatim.USER_AGENT)
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream ?: throw IOException("HTTP $code")
            return stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
