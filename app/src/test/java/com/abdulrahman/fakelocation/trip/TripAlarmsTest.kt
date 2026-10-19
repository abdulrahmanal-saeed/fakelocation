package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.geo.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAlarmsTest {

    private val path = Router.parse(javaClass.classLoader!!.getResource("osrm_car.json")!!.readText())
    private val stops = listOf(
        Stop(1, "A", path.points.first(), 10),
        Stop(2, "B", path.points[path.legs[0].lastPoint], 30),
        Stop(3, "C", path.points.last(), 0),
    )
    private val timeline = TripTimeline(stops, path, natural = false)
    private val twoMin = 120_000L
    private val oneMin = 60_000L

    private fun due(t: Long, fired: Set<String> = emptySet()) =
        TripAlarms.due(timeline, t, fired, twoMin, oneMin).map { it.key }

    @Test
    fun twoMinutesBeforeEachStop() {
        assertTrue("ARRIVING:1" in due(timeline.arrive[1] - twoMin))
        assertTrue("ARRIVING:1" in due(timeline.arrive[1] - 1000))
        assertTrue("ARRIVING:1" !in due(timeline.arrive[1] - twoMin - 1000))
        assertTrue("ARRIVING:2" in due(timeline.arrive[2] - 30_000))
    }

    @Test
    fun oneMinuteBeforeLeaving() {
        assertTrue("LEAVING:0" in due(timeline.leave[0] - oneMin))
        assertTrue("LEAVING:1" in due(timeline.leave[1] - 10_000))
        assertTrue("LEAVING:0" !in due(timeline.leave[0]))
    }

    @Test
    fun firedAlertsDoNotRepeat() {
        val t = timeline.arrive[1] - 60_000
        assertEquals(emptyList<String>(), due(t, setOf("ARRIVING:1")))
    }

    @Test
    fun arrivedAndFinished() {
        assertTrue("ARRIVED:1" in due(timeline.arrive[1] + 5000))
        assertTrue("FINISHED:2" in due(timeline.totalMs))
        assertEquals(emptyList<String>(), due(-5000))
    }

    @Test
    fun clockPausesAndJumps() {
        val c = TripClock.startingAt(1_000)
        assertEquals(-1_000, c.at(0))
        assertEquals(4_000, c.at(5_000))
        val paused = c.pause(5_000)
        assertEquals(4_000, paused.at(60_000))
        val resumed = paused.resume(60_000)
        assertEquals(5_000, resumed.at(61_000))
        assertEquals(100_500, resumed.jumpTo(100_000, 61_000).at(61_500))
    }
}
