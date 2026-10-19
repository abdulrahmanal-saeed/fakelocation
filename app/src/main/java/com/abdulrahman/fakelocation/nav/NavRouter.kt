package com.abdulrahman.fakelocation.nav

import android.content.Context
import android.util.Log
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.drive.Tolls
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

/** What to keep off the route. */
data class Avoid(val tolls: Boolean = false, val highways: Boolean = false) {
    val any get() = tolls || highways
}

/**
 * Routes with turn-by-turn steps for real navigation: Google (live traffic) when chosen, otherwise OSRM.
 * Avoiding tolls/highways on OpenStreetMap uses Valhalla, since the free OSRM server can't.
 */
object NavRouter {

    suspend fun route(
        context: Context,
        from: LatLon,
        to: LatLon,
        mode: TravelMode,
        via: List<LatLon> = emptyList(),
        avoid: Avoid = Avoid(),
    ): NavRoute = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val stops = listOf(from) + via + to
        val carAvoid = if (mode == TravelMode.CAR) avoid else Avoid()
        if (prefs.useGoogleRoutes && prefs.hasGoogleKey) {
            try {
                return@withContext google(GoogleApi(context), stops, mode, carAvoid)
            } catch (e: Exception) {
                Log.w("NavRouter", "Google navigation route failed, using OpenStreetMap", e)
            }
        }
        if (carAvoid.any) {
            try {
                return@withContext valhalla(stops, carAvoid)
            } catch (e: Exception) {
                Log.w("NavRouter", "Valhalla route failed, using OSRM", e)
            }
        }
        osrm(stops, mode)
    }

    // region OSRM

    private fun osrm(stops: List<LatLon>, mode: TravelMode): NavRoute {
        val coords = stops.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val path = if (mode == TravelMode.CAR) "routed-car/route/v1/driving" else "routed-foot/route/v1/foot"
        return parseOsrm(JSONObject(get("https://routing.openstreetmap.de/$path/$coords?overview=full&geometries=geojson&steps=true")))
    }

    fun parseOsrm(json: JSONObject): NavRoute {
        if (json.optString("code") != "Ok") throw IOException("Routing failed: " + json.optString("message", json.optString("code")))
        val route = json.getJSONArray("routes").getJSONObject(0)
        val points = ArrayList<LatLon>()
        val steps = ArrayList<NavStep>()
        val stopPoints = ArrayList<Int>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val stepsJson = legs.getJSONObject(l).getJSONArray("steps")
            val lastLeg = l == legs.length() - 1
            for (s in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(s)
                val shape = step.getJSONObject("geometry").getJSONArray("coordinates").toPoints()
                val start = if (points.isNotEmpty() && shape.isNotEmpty() && points.last() == shape.first()) points.lastIndex else points.size
                val maneuver = step.getJSONObject("maneuver")
                val type = maneuver.optString("type")
                // Every leg after the first starts with another "depart": not worth saying.
                if (type == "depart" && l > 0) {
                    points.appendShape(shape)
                    continue
                }
                val instruction = if (type == "arrive" && !lastLeg) stopReached(l) else Instructions.fromOsrm(
                    type, maneuver.optString("modifier"), step.optString("name"),
                    maneuver.optInt("exit", 0), step.optString("ref")
                )
                val lanes = step.optJSONArray("intersections")?.optJSONObject(0)?.optJSONArray("lanes")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val lane = arr.getJSONObject(i)
                        val dirs = lane.optJSONArray("indications")
                        Lane((0 until (dirs?.length() ?: 0)).map { dirs!!.getString(it) }, lane.optBoolean("valid"))
                    }
                }.orEmpty()
                steps += NavStep(instruction.maneuver, instruction.text, instruction.spoken, start, step.optString("name").ifBlank { step.optString("ref") }, lanes, maneuver.optInt("exit", 0))
                points.appendShape(shape)
            }
            if (!lastLeg) stopPoints += points.lastIndex
        }
        if (points.size < 2) throw IOException("Route has no shape")
        val fixed = steps.map { it.copy(pointIndex = it.pointIndex.coerceIn(0, points.lastIndex)) }
        return NavRoute(points, fixed, route.getDouble("distance"), route.getDouble("duration"), stopPoints = stopPoints)
    }

    // endregion

    // region Valhalla (OpenStreetMap, can avoid tolls and highways)

    private fun valhalla(stops: List<LatLon>, avoid: Avoid): NavRoute {
        fun body(excludeGates: Boolean): JSONObject {
            val options = JSONObject()
            if (avoid.highways) options.put("use_highways", 0.0)
            if (avoid.tolls) options.put("use_tolls", 0.0)
            val json = JSONObject()
                .put("locations", JSONArray(stops.map { JSONObject().put("lat", it.lat).put("lon", it.lon).put("type", "break") }))
                .put("costing", "auto")
                .put("costing_options", JSONObject().put("auto", options))
                .put("directions_options", JSONObject().put("units", "kilometers").put("language", "en-US"))
            // Salik/Darb gates aren't always tagged as toll roads, so block the gates themselves.
            if (excludeGates) json.put("exclude_locations", JSONArray(Tolls.GATES.flatMap { it.points }.map { JSONObject().put("lat", it.lat).put("lon", it.lon) }))
            return json
        }
        val response = try {
            post(VALHALLA, body(excludeGates = avoid.tolls))
        } catch (e: IOException) {
            // No way around the gates (e.g. onto Abu Dhabi island): avoid tolls as much as possible instead.
            if (avoid.tolls) post(VALHALLA, body(excludeGates = false)) else throw e
        }
        return parseValhalla(JSONObject(response))
    }

    fun parseValhalla(json: JSONObject): NavRoute {
        val trip = json.optJSONObject("trip") ?: throw IOException("Valhalla: " + json.optString("error", "no route"))
        val points = ArrayList<LatLon>()
        val steps = ArrayList<NavStep>()
        val stopPoints = ArrayList<Int>()
        val legs = trip.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val lastLeg = l == legs.length() - 1
            val shape = Polyline.decode(leg.getString("shape"), precision = 6)
            // Where each shape point lands in [points] once repeated points are dropped.
            val index = IntArray(shape.size)
            shape.forEachIndexed { i, p ->
                if (points.isEmpty() || points.last() != p) points += p
                index[i] = points.lastIndex
            }
            val maneuvers = leg.getJSONArray("maneuvers")
            for (m in 0 until maneuvers.length()) {
                val man = maneuvers.getJSONObject(m)
                val type = man.optInt("type")
                if (type == 27 || (l > 0 && type in 1..3)) continue // roundabout exit folds into the entry; later departs are silent
                val names = man.optJSONArray("street_names")
                val name = names?.optString(0).orEmpty()
                val next = maneuvers.optJSONObject(m + 1)
                // At a roundabout the road you end up on is named on the following "exit" maneuver.
                val road = if (type == 26) next?.optJSONArray("street_names")?.optString(0).orEmpty() else name
                val exit = man.optInt("roundabout_exit_count", 0)
                val (osrmType, modifier) = valhallaToOsrm(type)
                val instruction = if (osrmType == "arrive" && !lastLeg) stopReached(l) else Instructions.fromOsrm(osrmType, modifier, road, exit)
                steps += NavStep(instruction.maneuver, instruction.text, instruction.spoken, index.getOrElse(man.optInt("begin_shape_index")) { points.lastIndex }, road, emptyList(), exit)
            }
            if (!lastLeg) stopPoints += points.lastIndex
        }
        if (points.size < 2) throw IOException("Valhalla route has no shape")
        val summary = trip.getJSONObject("summary")
        return NavRoute(
            points, steps.map { it.copy(pointIndex = it.pointIndex.coerceIn(0, points.lastIndex)) },
            summary.optDouble("length", 0.0) * 1000, summary.optDouble("time", 0.0), stopPoints = stopPoints,
        )
    }

    /** Valhalla maneuver type → OSRM-style (type, modifier) so one place writes the instructions. */
    private fun valhallaToOsrm(type: Int): Pair<String, String> = when (type) {
        1, 2, 3 -> "depart" to ""
        4, 5, 6 -> "arrive" to ""
        7, 8, 22 -> "continue" to "straight"
        9 -> "turn" to "slight right"
        10 -> "turn" to "right"
        11 -> "turn" to "sharp right"
        12, 13 -> "turn" to "uturn"
        14 -> "turn" to "sharp left"
        15 -> "turn" to "left"
        16 -> "turn" to "slight left"
        17 -> "on ramp" to "straight"
        18 -> "on ramp" to "slight right"
        19 -> "on ramp" to "slight left"
        20 -> "off ramp" to "slight right"
        21 -> "off ramp" to "slight left"
        23 -> "fork" to "slight right"
        24 -> "fork" to "slight left"
        25 -> "merge" to "straight"
        37 -> "merge" to "slight right"
        38 -> "merge" to "slight left"
        26 -> "roundabout" to ""
        else -> "continue" to "straight"
    }

    // endregion

    // region Google

    private fun google(api: GoogleApi, stops: List<LatLon>, mode: TravelMode, avoid: Avoid): NavRoute {
        fun waypoint(p: LatLon) = JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lon)))
        val body = JSONObject()
            .put("origin", waypoint(stops.first()))
            .put("destination", waypoint(stops.last()))
            .put("travelMode", if (mode == TravelMode.CAR) "DRIVE" else "WALK")
            .put("languageCode", "en")
        if (stops.size > 2) body.put("intermediates", JSONArray(stops.subList(1, stops.size - 1).map(::waypoint)))
        if (mode == TravelMode.CAR) {
            body.put("routingPreference", "TRAFFIC_AWARE")
            body.put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE"))
        }
        if (avoid.any) body.put("routeModifiers", JSONObject().put("avoidTolls", avoid.tolls).put("avoidHighways", avoid.highways))
        val json = api.post(
            "https://routes.googleapis.com/directions/v2:computeRoutes", body,
            "routes.distanceMeters,routes.duration,routes.legs.steps.distanceMeters,routes.legs.steps.polyline.encodedPolyline," +
                "routes.legs.steps.navigationInstruction,routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals"
        )
        return parseGoogle(json, trafficAware = mode == TravelMode.CAR)
    }

    fun parseGoogle(json: JSONObject, trafficAware: Boolean): NavRoute {
        val route = json.optJSONArray("routes")?.optJSONObject(0) ?: throw IOException("Google found no route")
        val points = ArrayList<LatLon>()
        val steps = ArrayList<NavStep>()
        val stopPoints = ArrayList<Int>()
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
            if (l < legs.length() - 1 && points.isNotEmpty()) {
                val reached = stopReached(l)
                steps += NavStep(reached.maneuver, reached.text, reached.spoken, points.lastIndex)
                stopPoints += points.lastIndex
            }
        }
        if (points.size < 2) throw IOException("Google route has no shape")
        steps += NavStep(Maneuver.ARRIVE, Instructions.ARRIVE_TEXT, Instructions.ARRIVE_TEXT, points.lastIndex)
        val built = NavRoute(
            points, steps.map { it.copy(pointIndex = it.pointIndex.coerceIn(0, points.lastIndex)) },
            route.optDouble("distanceMeters", 0.0), GoogleApi.seconds(route.optString("duration")), trafficAware, stopPoints,
        )
        return built.copy(traffic = googleTraffic(route, built.length))
    }

    /**
     * Slow / jammed stretches from Google's speedReadingIntervals, which index the overview polyline.
     * They're turned into distances along that polyline and scaled onto our step-by-step shape.
     */
    fun googleTraffic(route: JSONObject, length: Double): List<TrafficSpan> {
        val intervals = route.optJSONObject("travelAdvisory")?.optJSONArray("speedReadingIntervals") ?: return emptyList()
        val overview = Polyline.decode(route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty())
        if (overview.size < 2 || length <= 0) return emptyList()
        val along = DoubleArray(overview.size)
        for (i in 1 until overview.size) along[i] = along[i - 1] + com.abdulrahman.fakelocation.data.distanceMeters(overview[i - 1], overview[i])
        val scale = length / along.last().coerceAtLeast(1.0)
        val spans = ArrayList<TrafficSpan>()
        for (i in 0 until intervals.length()) {
            val r = intervals.getJSONObject(i)
            val level = when (r.optString("speed")) {
                "SLOW" -> Traffic.SLOW
                "TRAFFIC_JAM" -> Traffic.JAM
                else -> continue
            }
            val from = along[r.optInt("startPolylinePointIndex", 0).coerceIn(0, overview.lastIndex)] * scale
            val to = along[r.optInt("endPolylinePointIndex", overview.lastIndex).coerceIn(0, overview.lastIndex)] * scale
            if (to > from) spans += TrafficSpan(from, to, level)
        }
        return spans
    }

    // endregion

    private fun stopReached(legIndex: Int): Instructions.Instruction {
        val text = "You have reached stop ${legIndex + 1}"
        return Instructions.Instruction(Maneuver.ARRIVE, text, text)
    }

    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"

    private fun get(url: String): String = request(url, null)

    private fun post(url: String, body: JSONObject): String = request(url, body)

    private fun request(url: String, body: JSONObject?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", Nominatim.USER_AGENT)
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }
            if (code !in 200..299 && body != null) throw IOException("HTTP $code: ${text?.take(200)}")
            return text ?: throw IOException("HTTP $code")
        } finally {
            connection.disconnect()
        }
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
