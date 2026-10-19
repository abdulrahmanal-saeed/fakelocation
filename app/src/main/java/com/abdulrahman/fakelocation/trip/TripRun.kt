package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RouteJson
import org.json.JSONArray
import org.json.JSONObject

/** A trip in progress (or scheduled): the route snapshot, its clock, and which alerts already went off. */
class TripRun(
    route: Route,
    var clock: TripClock,
    val fired: MutableSet<String>,
    val natural: Boolean,
    val trafficPercent: Int,
    var lastWaitingStop: Int = -1,
    var historyId: Long = -1,
) {
    var route: Route = route
        private set
    var timeline: TripTimeline = build()
        private set

    private fun build() = TripTimeline(route.stops, route.travelPath(trafficPercent)!!, natural)

    /** Adds minutes to one stop's wait (the trip clock stays where it is, so leaving moves later). */
    fun addWait(stopIndex: Int, minutes: Int) {
        route = route.copy(stops = route.stops.mapIndexed { i, s -> if (i == stopIndex) s.copy(waitMinutes = s.waitMinutes + minutes) else s })
        timeline = build()
        fired.remove(Alert(AlertKind.LEAVING, stopIndex).key)
    }

    fun toJson(): String = JSONObject()
        .put("route", RouteJson.write(route))
        .put("base", clock.base)
        .put("realBase", clock.realBase)
        .put("paused", clock.paused)
        .put("fired", JSONArray(fired.toList()))
        .put("natural", natural)
        .put("traffic", trafficPercent)
        .put("lastWaiting", lastWaitingStop)
        .put("historyId", historyId)
        .toString()

    companion object {
        fun fromJson(text: String): TripRun {
            val json = JSONObject(text)
            val fired = json.getJSONArray("fired").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            return TripRun(
                route = RouteJson.read(json.getJSONObject("route")),
                clock = TripClock(json.getLong("base"), json.getLong("realBase"), json.getBoolean("paused")),
                fired = fired.toMutableSet(),
                natural = json.getBoolean("natural"),
                trafficPercent = json.getInt("traffic"),
                lastWaitingStop = json.getInt("lastWaiting"),
                historyId = json.getLong("historyId"),
            )
        }
    }
}
