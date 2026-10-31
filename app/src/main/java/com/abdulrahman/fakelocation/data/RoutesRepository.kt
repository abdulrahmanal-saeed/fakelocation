package com.abdulrahman.fakelocation.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saved routes, kept in a JSON file next to the places. */
class RoutesRepository private constructor(private val file: File) {

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes

    init {
        load()
    }

    fun get(id: Long): Route? = _routes.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(route: Route) {
        val updated = route.copy(updatedAt = System.currentTimeMillis())
        _routes.value = listOf(updated) + _routes.value.filterNot { it.id == route.id }
        write()
    }

    @Synchronized
    fun delete(id: Long) {
        _routes.value = _routes.value.filterNot { it.id == id }
        write()
    }

    /** Puts a deleted route back as it was, edit time included (Undo). */
    @Synchronized
    fun restore(route: Route) {
        if (_routes.value.any { it.id == route.id }) return
        _routes.value = (_routes.value + route).sortedByDescending { it.updatedAt }
        write()
    }

    /** Every route at once, from a restored backup. */
    @Synchronized
    fun replaceAll(routes: List<Route>) {
        _routes.value = routes
        write()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            _routes.value = JSONArray(file.readText()).let { arr -> (0 until arr.length()).map { RouteJson.read(arr.getJSONObject(it)) } }
        } catch (e: Exception) {
            Log.w("RoutesRepository", "could not read routes", e)
        }
    }

    private fun write() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(_routes.value.map(RouteJson::write)).toString())
        tmp.renameTo(file)
    }

    companion object {
        @Volatile
        private var instance: RoutesRepository? = null

        fun get(context: Context): RoutesRepository =
            instance ?: synchronized(this) {
                instance ?: RoutesRepository(File(context.applicationContext.filesDir, "routes.json")).also { instance = it }
            }

        fun newId(): Long = System.nanoTime()
    }
}

/** JSON form of a route. Points are stored as flat [lat, lon, lat, lon, ...] arrays to keep the file small. */
object RouteJson {
    fun write(route: Route): JSONObject = JSONObject()
        .put("id", route.id)
        .put("name", route.name)
        .put("mode", route.mode.name)
        .put("updatedAt", route.updatedAt)
        .put("timingMode", route.timing.mode.name)
        .apply { route.timing.timeMillis?.let { put("timingTime", it) } }
        .apply { if (route.timing.days != 0) put("timingDays", route.timing.days) }
        .apply { if (route.speed != 1.0) put("speed", route.speed) }
        .apply { if (route.returnTrip) put("returnTrip", true) }
        .apply { if (route.laps != 1) put("laps", route.laps) }
        .put("stops", JSONArray(route.stops.map {
            JSONObject().put("id", it.id).put("name", it.name).put("lat", it.position.lat).put("lon", it.position.lon)
                .put("wait", it.waitMinutes)
        }))
        .apply { route.path?.let { put("path", writePath(it)) } }

    fun read(json: JSONObject) = Route(
        id = json.getLong("id"),
        name = json.getString("name"),
        mode = TravelMode.valueOf(json.getString("mode")),
        stops = json.getJSONArray("stops").let { arr ->
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                Stop(s.getLong("id"), s.getString("name"), LatLon(s.getDouble("lat"), s.getDouble("lon")), s.getInt("wait"))
            }
        },
        path = json.optJSONObject("path")?.let(::readPath),
        timing = Timing(
            TimingMode.valueOf(json.optString("timingMode", TimingMode.NOW.name)),
            if (json.has("timingTime")) json.getLong("timingTime") else null,
            json.optInt("timingDays", 0),
        ),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
        speed = json.optDouble("speed", 1.0).takeIf { it > 0 } ?: 1.0,
        returnTrip = json.optBoolean("returnTrip", false),
        laps = json.optInt("laps", 1).coerceIn(1, Route.MAX_LAPS),
    )

    private fun writePath(path: RoutePath): JSONObject {
        val coords = JSONArray()
        path.points.forEach { coords.put(round(it.lat, 6)).put(round(it.lon, 6)) }
        return JSONObject()
            .put("points", coords)
            .put("seconds", JSONArray(path.segmentSeconds.map { round(it, 2) }))
            .put("traffic", path.trafficAware)
            .apply {
                if (path.lights.isNotEmpty()) put("lights", JSONArray(path.lights.map {
                    JSONArray().put(it.point).put(it.rule.name).put(it.waitSeconds)
                }))
            }
            .put("legs", JSONArray(path.legs.map {
                JSONObject().put("distance", it.distanceMeters).put("duration", it.durationSeconds)
                    .put("first", it.firstPoint).put("last", it.lastPoint)
            }))
    }

    private fun readPath(json: JSONObject): RoutePath {
        val coords = json.getJSONArray("points")
        val seconds = json.getJSONArray("seconds")
        val legs = json.getJSONArray("legs")
        return RoutePath(
            points = (0 until coords.length() / 2).map { LatLon(coords.getDouble(2 * it), coords.getDouble(2 * it + 1)) },
            segmentSeconds = (0 until seconds.length()).map { seconds.getDouble(it) },
            legs = (0 until legs.length()).map {
                val l = legs.getJSONObject(it)
                Leg(l.getDouble("distance"), l.getDouble("duration"), l.getInt("first"), l.getInt("last"))
            },
            trafficAware = json.optBoolean("traffic", false),
            lights = json.optJSONArray("lights")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val l = arr.optJSONArray(i) ?: return@mapNotNull null
                    TrafficLight(
                        l.getInt(0),
                        runCatching { LightRule.valueOf(l.getString(1)) }.getOrDefault(LightRule.AUTO),
                        l.optInt(2, TrafficLight.DEFAULT_WAIT_SECONDS),
                    )
                }.filter { it.point in 0 until coords.length() / 2 }
            }.orEmpty(),
        )
    }

    private fun round(value: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(value * f) / f
    }
}
