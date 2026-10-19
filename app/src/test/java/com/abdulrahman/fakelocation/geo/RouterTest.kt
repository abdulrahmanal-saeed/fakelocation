package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.RouteJson
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.Schedule
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RouterTest {

    private val body = javaClass.classLoader!!.getResource("osrm_car.json")!!.readText()

    @Test
    fun parsesLegsAndScalesSegmentTimes() {
        val path = Router.parse(body)
        assertEquals(2, path.legs.size)
        assertEquals(path.points.size - 1, path.segmentSeconds.size)
        assertEquals(0, path.legs[0].firstPoint)
        assertEquals(path.legs[0].lastPoint, path.legs[1].firstPoint)
        assertEquals(path.points.lastIndex, path.legs[1].lastPoint)
        for (leg in path.legs) {
            val sum = path.segmentSeconds.subList(leg.firstPoint, leg.lastPoint).sum()
            assertEquals(leg.durationSeconds, sum, 0.01)
        }
    }

    @Test
    fun scheduleAndArriveBy() {
        val path = Router.parse(body)
        val stops = listOf(
            Stop(1, "A", LatLon(30.06, 31.33), 10),
            Stop(2, "B", LatLon(30.04, 31.23), 30),
            Stop(3, "C", LatLon(30.04, 31.22), 99), // last stop's wait does not count
        )
        val start = 1_000_000L
        val times = Schedule.times(stops, path, start)
        assertEquals(start, times[0].arriveAt)
        assertEquals(start + 10 * 60_000L, times[0].leaveAt)
        assertEquals(times[0].leaveAt + (path.legs[0].durationSeconds * 1000).toLong(), times[1].arriveAt)
        assertEquals(times[2].arriveAt, times[2].leaveAt)

        val arriveBy = times[2].arriveAt
        assertEquals(start, Schedule.startFor(stops, path, arriveBy))
    }

    @Test
    fun routeSurvivesJsonRoundTrip() {
        val path = Router.parse(body)
        val route = Route(7, "Test", TravelMode.WALK, listOf(Stop(1, "A", LatLon(30.0, 31.0), 5), Stop(2, "B", LatLon(30.1, 31.1), 0)), path)
        val back = RouteJson.read(RouteJson.write(route))
        assertEquals(route.stops, back.stops)
        assertEquals(route.mode, back.mode)
        assertEquals(path.points.size, back.path!!.points.size)
        assertEquals(path.legs, back.path!!.legs)
    }
}
