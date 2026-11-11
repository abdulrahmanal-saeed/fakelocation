package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RouteJson
import com.abdulrahman.fakelocation.data.Schedule
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.Timing
import com.abdulrahman.fakelocation.data.TimingMode
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.geo.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Trip speed, there and back, laps and weekly runs. */
class TripOptionsTest {

    private val path = Router.parse(javaClass.classLoader!!.getResource("osrm_car.json")!!.readText())
    private val stops = listOf(
        Stop(1, "A", path.points.first(), 10),
        Stop(2, "B", path.points[path.legs[0].lastPoint], 5),
        Stop(3, "C", path.points.last(), 99),
    )
    private val route = Route(1, "Errands", TravelMode.CAR, stops, path)

    @Test
    fun reversedPathKeepsEveryLeg() {
        val back = path.reversed()
        assertEquals(path.points.last(), back.points.first())
        assertEquals(path.points.first(), back.points.last())
        assertEquals(path.legs.size, back.legs.size)
        assertEquals(0, back.legs.first().firstPoint)
        assertEquals(back.points.lastIndex, back.legs.last().lastPoint)
        assertEquals(path.legs.last().durationSeconds, back.legs.first().durationSeconds, 0.0)
        for (leg in back.legs) {
            assertEquals(leg.durationSeconds, back.segmentSeconds.subList(leg.firstPoint, leg.lastPoint).sum(), 0.01)
        }
    }

    @Test
    fun thereAndBack() {
        val trip = route.copy(returnTrip = true).expanded()
        assertEquals(listOf("A", "B", "C", "B", "A"), trip.stops.map { it.name })
        // The far end is a stop on the way now, so its wait counts.
        assertEquals(99, trip.stops[2].waitMinutes)
        assertEquals(trip.stops.size - 1, trip.path!!.legs.size)
        assertEquals(2 * path.distanceMeters, trip.path!!.distanceMeters, 0.01)
        assertEquals(trip.path!!.points.lastIndex, trip.path!!.legs.last().lastPoint)
        assertFalse(trip.returnTrip)
        // The trip engine takes it as is.
        val timeline = TripTimeline(trip.stops, trip.path!!, natural = false)
        assertEquals(stops.first().position, timeline.at(timeline.totalMs).position)
    }

    @Test
    fun lapsOnlyWhenTheTripEndsWhereItStarts() {
        val back3 = route.copy(returnTrip = true, laps = 3).expanded()
        assertEquals(1 + 3 * 4, back3.stops.size)
        assertEquals(3 * 2 * path.distanceMeters, back3.path!!.distanceMeters, 0.1)
        // Where one lap meets the next, the wait is the one at the very start.
        assertEquals(10, back3.stops[4].waitMinutes)
        assertEquals(10, back3.stops[8].waitMinutes)

        // A one-way route that ends somewhere else can't be repeated.
        val oneWay = route.copy(laps = 3)
        assertFalse(oneWay.canRepeat)
        assertEquals(3, oneWay.expanded().stops.size)

        // A loop (last stop back at the first) can.
        val loop = route.copy(stops = stops.dropLast(1) + stops.first().copy(id = 9), path = path.then(path.reversed()).let {
            // A → B → A: take the first leg there and back.
            val there = path.copy(points = path.points.subList(0, path.legs[0].lastPoint + 1), segmentSeconds = path.segmentSeconds.subList(0, path.legs[0].lastPoint), legs = listOf(path.legs[0]))
            there.then(there.reversed())
        }, laps = 2)
        assertTrue(loop.isLoop)
        assertEquals(5, loop.expanded().stops.size)
        assertEquals(4, loop.expanded().path!!.legs.size)
    }

    @Test
    fun speedChangesTravelTimeButNotWaits() {
        val normal = route.travelPath(0)!!
        val twice = route.copy(speed = 2.0).travelPath(0)!!
        assertEquals(normal.durationSeconds / 2, twice.durationSeconds, 0.01)
        assertEquals((Schedule.totalMillis(stops, normal) - normal.durationSeconds * 500), Schedule.totalMillis(stops, twice).toDouble(), 2.0)
        // Traffic and speed together: +30 % traffic at 1.5×.
        assertEquals(normal.durationSeconds * 1.3 / 1.5, route.copy(speed = 1.5).travelPath(30)!!.durationSeconds, 0.01)
    }

    @Test
    fun changingSpeedMidTripKeepsThePosition() {
        val run = TripRun(route, TripClock.startingAt(0), mutableSetOf(), natural = false, trafficPercent = 0)
        // Halfway along the first leg.
        val t = run.timeline.leave[0] + (run.timeline.arrive[1] - run.timeline.leave[0]) / 2
        val before = run.timeline.at(t)
        run.clock = TripClock(t, 1_000L, false)
        run.changeSpeed(2.0, 1_000L)
        val after = run.timeline.at(run.clock.at(1_000L))
        assertEquals(TripPhase.MOVING, after.phase)
        assertEquals(0, after.stopIndex)
        assertTrue("moved ${before.distanceDone} → ${after.distanceDone}", kotlin.math.abs(before.distanceDone - after.distanceDone) < 50)
        assertEquals(before.speedMps * 2, after.speedMps, 0.5f)
        // Waiting keeps the same minute of the same wait.
        val waiting = run.timeline.arrive[1] + 60_000
        val slower = TripTimeline(run.route.stops, route.copy(speed = 0.5).travelPath(0)!!, false)
        assertEquals(slower.arrive[1] + 60_000, run.timeline.sameMomentIn(slower, waiting))
    }

    @Test
    fun newFieldsSurviveJson() {
        val saved = route.copy(speed = 1.5, returnTrip = true, laps = 3, timing = Timing(TimingMode.ARRIVE_BY, 123_000L, TripSchedule.WEEKDAYS))
        val back = RouteJson.read(RouteJson.write(saved))
        assertEquals(1.5, back.speed, 0.0)
        assertTrue(back.returnTrip)
        assertEquals(3, back.laps)
        assertEquals(saved.timing, back.timing)
        // Older files without them read as before.
        val old = RouteJson.read(RouteJson.write(route))
        assertEquals(1.0, old.speed, 0.0)
        assertEquals(1, old.laps)
        assertEquals(0, old.timing.days)
    }

    // region Weekly schedule

    private val dubai = ZoneId.of("Asia/Dubai")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(dubai).toInstant().toEpochMilli()

    @Test
    fun nextStartAtTheChosenTime() {
        // Mon–Fri 07:30. 2026-10-30 is a Friday.
        val weekly = route.copy(timing = Timing(TimingMode.START_AT, at("2026-01-01T07:30"), TripSchedule.WEEKDAYS))
        assertEquals(at("2026-10-30T07:30"), TripSchedule.nextStart(weekly, 0, at("2026-10-30T06:00"), dubai))
        // Friday after 07:30 → next Monday.
        assertEquals(at("2026-11-02T07:30"), TripSchedule.nextStart(weekly, 0, at("2026-10-30T08:00"), dubai))
        // Weekend days only.
        val weekend = weekly.copy(timing = weekly.timing.copy(days = TripSchedule.bit(java.time.DayOfWeek.SATURDAY) or TripSchedule.bit(java.time.DayOfWeek.SUNDAY)))
        assertEquals(at("2026-10-31T07:30"), TripSchedule.nextStart(weekend, 0, at("2026-10-30T08:00"), dubai))
    }

    @Test
    fun arriveByStartsEarlyEnough() {
        val weekly = route.copy(timing = Timing(TimingMode.ARRIVE_BY, at("2026-01-01T08:00"), TripSchedule.WEEKDAYS))
        val total = Schedule.totalMillis(route.stops, route.travelPath(20)!!)
        assertEquals(at("2026-10-30T08:00") - total, TripSchedule.nextStart(weekly, 20, at("2026-10-30T05:00"), dubai))
    }

    @Test
    fun notRepeating() {
        assertNull(TripSchedule.nextStart(route, 0, 0, dubai))
        assertNull(TripSchedule.nextStart(route.copy(timing = Timing(TimingMode.NOW, at("2026-01-01T08:00"), TripSchedule.EVERY_DAY)), 0, 0, dubai))
        assertNull(TripSchedule.nextStart(route.copy(path = null, timing = Timing(TimingMode.START_AT, 0L, TripSchedule.EVERY_DAY)), 0, 0, dubai))
    }

    @Test
    fun daysText() {
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        assertEquals("Mon–Fri", TripSchedule.daysText(TripSchedule.WEEKDAYS, names, "Every day"))
        assertEquals("Every day", TripSchedule.daysText(TripSchedule.EVERY_DAY, names, "Every day"))
        assertEquals("Sat, Sun", TripSchedule.daysText(0b1100000, names, "Every day"))
        assertEquals("Mon, Wed, Fri", TripSchedule.daysText(0b0010101, names, "Every day"))
        assertEquals("Mon, Tue, Thu–Sat", TripSchedule.daysText(0b0111011, names, "Every day"))
    }

    // endregion
}
