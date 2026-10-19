package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Schedule
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.geo.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTimelineTest {

    private val path = Router.parse(javaClass.classLoader!!.getResource("osrm_car.json")!!.readText())
    private val stops = listOf(
        Stop(1, "A", path.points.first(), 10),
        Stop(2, "B", path.points[path.legs[0].lastPoint], 30),
        Stop(3, "C", path.points.last(), 0),
    )

    @Test
    fun timesMatchTheSchedule() {
        val timeline = TripTimeline(stops, path, natural = false)
        val schedule = Schedule.times(stops, path, 0)
        for (i in stops.indices) {
            assertEquals(schedule[i].arriveAt, timeline.arrive[i])
            assertEquals(schedule[i].leaveAt, timeline.leave[i])
        }
    }

    @Test
    fun phasesAlongTheTrip() {
        val t = TripTimeline(stops, path, natural = false)
        assertEquals(TripPhase.SCHEDULED, t.at(-1).phase)
        assertEquals(TripPhase.WAITING, t.at(0).phase)
        assertEquals(0, t.at(0).stopIndex)
        assertEquals(TripPhase.MOVING, t.at(t.leave[0] + 1000).phase)
        assertEquals(TripPhase.WAITING, t.at(t.arrive[1] + 1000).phase)
        assertEquals(1, t.at(t.arrive[1] + 1000).stopIndex)
        assertEquals(TripPhase.FINISHED, t.at(t.totalMs).phase)
        assertEquals(stops.last().position, t.at(t.totalMs + 5000).position)
    }

    @Test
    fun movesForwardAtStreetSpeed() {
        for (natural in listOf(false, true)) {
            val t = TripTimeline(stops, path, natural)
            var lastDone = -1.0
            var time = t.leave[0]
            while (time < t.arrive[1]) {
                val p = t.at(time)
                assertEquals(TripPhase.MOVING, p.phase)
                assertTrue("distance must not go backwards", p.distanceDone >= lastDone - 0.01)
                assertTrue("speed should be like a car in town", p.speedMps in 0f..45f)
                lastDone = p.distanceDone
                time += 1000
            }
            // Arrives where the stop is.
            val arrived = t.at(t.arrive[1])
            assertEquals(TripPhase.WAITING, arrived.phase)
            assertEquals(path.legs[0].lastPoint, arrived.pointIndex)
        }
    }

    @Test
    fun warpKeepsLegEndsAndOrder() {
        val span = 300_000.0
        assertEquals(0.0, TripTimeline.warp(0.0, span, 0), 1e-6)
        assertEquals(span, TripTimeline.warp(span, span, 0), 1e-6)
        var last = -1.0
        var u = 0.0
        while (u <= span) {
            val w = TripTimeline.warp(u, span, 2)
            assertTrue(w >= last)
            last = w
            u += 250.0
        }
    }

    @Test
    fun slowAtTurnsKeepsLegDurations() {
        val slowed = TripTimeline.slowAtTurns(path)
        for (leg in path.legs) {
            val before = path.segmentSeconds.subList(leg.firstPoint, leg.lastPoint).sum()
            val after = slowed.segmentSeconds.subList(leg.firstPoint, leg.lastPoint).sum()
            assertEquals(before, after, 0.01)
        }
    }

    @Test
    fun bearingPointsTheRightWay() {
        assertEquals(0f, TripTimeline.bearing(LatLon(30.0, 31.0), LatLon(30.1, 31.0)), 0.5f)
        assertEquals(90f, TripTimeline.bearing(LatLon(30.0, 31.0), LatLon(30.0, 31.1)), 0.5f)
        assertEquals(180f, TripTimeline.bearing(LatLon(30.1, 31.0), LatLon(30.0, 31.0)), 0.5f)
    }
}
