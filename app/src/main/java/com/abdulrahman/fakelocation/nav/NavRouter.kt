package com.abdulrahman.fakelocation.nav

import android.content.Context
import android.util.Log
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.drive.Tolls
import com.abdulrahman.fakelocation.geo.Http
import com.abdulrahman.fakelocation.geo.MapLocale
import com.abdulrahman.fakelocation.geo.HttpException
import com.abdulrahman.fakelocation.geo.NoInternetException
import com.abdulrahman.fakelocation.geo.RoutingServers
import com.abdulrahman.fakelocation.geo.google.GoogleApi
import com.abdulrahman.fakelocation.geo.google.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
    ): NavRoute = routes(context, from, to, mode, via, avoid, alternatives = false).first()

    /**
     * The best route first, then up to two other ways when [alternatives] is on (only for a plain A → B; routers
     * don't offer other ways around stops in between). Never empty.
     */
    suspend fun routes(
        context: Context,
        from: LatLon,
        to: LatLon,
        mode: TravelMode,
        via: List<LatLon> = emptyList(),
        avoid: Avoid = Avoid(),
        alternatives: Boolean,
    ): List<NavRoute> = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val stops = listOf(from) + via + to
        val alt = alternatives && via.isEmpty()
        val carAvoid = if (mode == TravelMode.CAR) avoid else Avoid()
        if (prefs.useGoogleRoutes && prefs.hasGoogleKey && prefs.mapProvider.isGoogle) {
            try {
                return@withContext distinct(google(GoogleApi(context), stops, mode, carAvoid, alt))
            } catch (e: Exception) {
                Log.w("NavRouter", "Google navigation route failed, using OpenStreetMap", e)
            }
        }
        val valhallaUrls = RoutingServers.valhalla(prefs.valhallaServer)
        val language = if (MapLocale.from(context).language == "ar") "ar-SA" else "en-US"
        try {
            distinct(valhalla(valhallaUrls, stops, carAvoid, mode, alt, language))
        } catch (error: NoInternetException) {
            throw error
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException || carAvoid.any) throw error
            Log.w("NavRouter", "Valhalla route failed, using OSRM", error)
            distinct(osrm(RoutingServers.osrm(prefs.osrmServer, mode), stops, alt))
        }
    }

    /** Other ways that are really the same (within 1 % in length and time) are left out; at most three. */
    fun distinct(routes: List<NavRoute>): List<NavRoute> {
        val out = ArrayList<NavRoute>()
        for (r in routes) {
            val same = out.any {
                kotlin.math.abs(it.distanceMeters - r.distanceMeters) <= it.distanceMeters * 0.01 &&
                    kotlin.math.abs(it.durationSeconds - r.durationSeconds) <= it.durationSeconds * 0.01
            }
            if (!same) out += r
            if (out.size == 3) break
        }
        return out
    }

    /** Tries each server in turn; the last error if none of them gives a route. */
    private fun <T> firstAnswer(servers: List<String>, attempt: (String) -> T): T {
        var lastError: Exception? = null
        for (server in servers) {
            try {
                return attempt(server)
            } catch (e: NoInternetException) {
                throw e
            } catch (e: Exception) {
                Log.w("NavRouter", "routing server failed: ${server.substringBefore("/route")}", e)
                lastError = e
            }
        }
        throw lastError ?: IOException("No routing server")
    }

    // region OSRM

    private fun osrm(bases: List<String>, stops: List<LatLon>, alternatives: Boolean): List<NavRoute> = firstAnswer(bases) { base ->
        val coords = stops.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        parseOsrmAll(JSONObject(get("$base/$coords?overview=full&geometries=geojson&steps=true&alternatives=$alternatives")))
    }

    fun parseOsrm(json: JSONObject): NavRoute = parseOsrmAll(json).first()

    /** Every route in the answer: the best one first, then the other ways. */
    fun parseOsrmAll(json: JSONObject): List<NavRoute> {
        if (json.optString("code") != "Ok") throw IOException("Routing failed: " + json.optString("message", json.optString("code")))
        val routes = json.getJSONArray("routes")
        // A broken other way never loses the main route.
        return listOf(parseOsrmRoute(routes.getJSONObject(0))) +
            (1 until routes.length()).mapNotNull { runCatching { parseOsrmRoute(routes.getJSONObject(it)) }.getOrNull() }
    }

    private fun parseOsrmRoute(route: JSONObject): NavRoute {
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

    private fun valhalla(urls: List<String>, stops: List<LatLon>, avoid: Avoid, mode: TravelMode, alternatives: Boolean, language: String): List<NavRoute> {
        val costing = if (mode == TravelMode.CAR) "auto" else "pedestrian"
        fun body(excludeGates: Boolean, languageCode: String): JSONObject {
            val options = JSONObject()
            if (avoid.highways) options.put("use_highways", 0.0)
            if (avoid.tolls) options.put("use_tolls", 0.0)
            val json = JSONObject()
                .put("locations", JSONArray(stops.map { JSONObject().put("lat", it.lat).put("lon", it.lon).put("type", "break") }))
                .put("costing", costing)
                .put("units", "kilometers").put("language", languageCode)
            if (options.length() > 0) json.put("costing_options", JSONObject().put(costing, options))
            if (alternatives) json.put("alternates", 2)
            // Salik/Darb gates aren't always tagged as toll roads, so block the gates themselves.
            if (excludeGates) json.put("exclude_locations", JSONArray(Tolls.GATES.flatMap { it.points }.filter { gate ->
                stops.any { com.abdulrahman.fakelocation.data.distanceMeters(it, gate) <= 100_000 }
            }.map { JSONObject().put("lat", it.lat).put("lon", it.lon) }))
            return json
        }
        return firstAnswer(urls) { url ->
            fun request(languageCode: String): String = try {
                post(url, body(excludeGates = avoid.tolls, languageCode = languageCode))
            } catch (error: HttpException) {
                if (avoid.tolls) post(url, body(excludeGates = false, languageCode = languageCode)) else throw error
            }
            val response = try {
                request(language)
            } catch (error: HttpException) {
                if (language == "en-US") throw error
                request("en-US")
            }
            parseValhallaAll(JSONObject(response))
        }
    }

    /** The main trip, then Valhalla's "alternates" (each shaped like a whole answer: {"trip": …}). */
    fun parseValhallaAll(json: JSONObject): List<NavRoute> {
        val others = json.optJSONArray("alternates")
        return listOf(parseValhalla(json)) +
            (0 until (others?.length() ?: 0)).mapNotNull { runCatching { parseValhalla(others!!.getJSONObject(it)) }.getOrNull() }
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

    private fun google(api: GoogleApi, stops: List<LatLon>, mode: TravelMode, avoid: Avoid, alternatives: Boolean): List<NavRoute> {
        fun waypoint(p: LatLon) = JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lon)))
        val body = JSONObject()
            .put("origin", waypoint(stops.first()))
            .put("destination", waypoint(stops.last()))
            .put("travelMode", if (mode == TravelMode.CAR) "DRIVE" else "WALK")
            .put("languageCode", api.locale.language)
        if (stops.size > 2) body.put("intermediates", JSONArray(stops.subList(1, stops.size - 1).map(::waypoint)))
        if (mode == TravelMode.CAR) {
            body.put("routingPreference", "TRAFFIC_AWARE")
            body.put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE"))
        }
        if (avoid.any) body.put("routeModifiers", JSONObject().put("avoidTolls", avoid.tolls).put("avoidHighways", avoid.highways))
        if (alternatives) body.put("computeAlternativeRoutes", true)
        val json = api.post(
            "https://routes.googleapis.com/directions/v2:computeRoutes", body,
            "routes.distanceMeters,routes.duration,routes.legs.steps.distanceMeters,routes.legs.steps.polyline.encodedPolyline," +
                "routes.legs.steps.navigationInstruction,routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals"
        )
        return parseGoogleAll(json, trafficAware = mode == TravelMode.CAR)
    }

    fun parseGoogle(json: JSONObject, trafficAware: Boolean): NavRoute = parseGoogleAll(json, trafficAware).first()

    /** Google's best route first, then its other ways. */
    fun parseGoogleAll(json: JSONObject, trafficAware: Boolean): List<NavRoute> {
        val routes = json.optJSONArray("routes")?.takeIf { it.length() > 0 } ?: throw IOException("Google found no route")
        return listOf(parseGoogleRoute(routes.getJSONObject(0), trafficAware)) +
            (1 until routes.length()).mapNotNull { runCatching { parseGoogleRoute(routes.getJSONObject(it), trafficAware) }.getOrNull() }
    }

    private fun parseGoogleRoute(route: JSONObject, trafficAware: Boolean): NavRoute {
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

    /** OSRM explains failures in a JSON body, so a non-2xx answer with a body is still returned. */
    private fun get(url: String): String =
        Http.request(url).let { if (it.ok || it.body.isNotBlank()) it.body else throw HttpException(it.code, it.body) }

    private fun post(url: String, body: JSONObject): String =
        Http.request(url, "POST", body.toString(), contentType = "application/json").requireOk()

    /** Adds a step's shape, skipping points that repeat the previous one. */
    private fun ArrayList<LatLon>.appendShape(shape: List<LatLon>) {
        for (p in shape) if (isEmpty() || last() != p) add(p)
    }

    private fun JSONArray.toPoints(): List<LatLon> = (0 until length()).map { i ->
        val c = getJSONArray(i)
        LatLon(c.getDouble(1), c.getDouble(0))
    }
}
