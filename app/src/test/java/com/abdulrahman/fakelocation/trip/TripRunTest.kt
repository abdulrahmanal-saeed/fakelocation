package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.geo.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRunTest {

    private val path = Router.parse(javaClass.classLoader!!.getResource("osrm_car.json")!!.readText())
    private val route = Route(
        1, "Errands", TravelMode.CAR,
        listOf(
            Stop(1, "A", path.points.first(), 10),
            Stop(2, "B", path.points[path.legs[0].lastPoint], 30),
            Stop(3, "C", path.points.last(), 0),
        ),
        path,
    )

    @Test
    fun survivesRestart() {
        val run = TripRun(route, TripClock(5_000, 1_000, true), mutableSetOf("ARRIVING:1"), natural = true, trafficPercent = 30, lastWaitingStop = 1, historyId = 42)
        val back = TripRun.fromJson(run.toJson())
        assertEquals(run.clock, back.clock)
        assertEquals(run.fired, back.fired)
        assertEquals(run.route.stops, back.route.stops)
        assertEquals(30, back.trafficPercent)
        assertEquals(run.timeline.totalMs, back.timeline.totalMs)
        assertEquals(42L, back.historyId)
    }

    @Test
    fun trafficMakesCarTripsLonger() {
        val normal = TripRun(route, TripClock.startingAt(0), mutableSetOf(), natural = false, trafficPercent = 0)
        val busy = TripRun(route, TripClock.startingAt(0), mutableSetOf(), natural = false, trafficPercent = 50)
        val driving = normal.timeline.totalMs - 40 * 60_000L
        assertEquals(driving * 1.5, (busy.timeline.totalMs - 40 * 60_000L).toDouble(), 5.0)
    }

    @Test
    fun plusFiveMinutesMovesLeavingLater() {
        val run = TripRun(route, TripClock.startingAt(0), mutableSetOf("LEAVING:1"), natural = false, trafficPercent = 0)
        val before = run.timeline.leave[1]
        run.addWait(1, 5)
        assertEquals(before + 5 * 60_000L, run.timeline.leave[1])
        assertTrue("leaving alarm can ring again", "LEAVING:1" !in run.fired)
    }
}
