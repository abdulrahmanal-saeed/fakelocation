package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.Schedule
import com.abdulrahman.fakelocation.data.TimingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Routes that run every week on chosen days (see [com.abdulrahman.fakelocation.data.Timing.days]). Pure, so it can be tested. */
object TripSchedule {

    /** Monday to Friday, the UAE work week. */
    const val WEEKDAYS = 0b0011111
    const val EVERY_DAY = 0b1111111

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    /**
     * When the next run of [route] begins after [now]: at the chosen time for "start at", or early enough to reach
     * the last stop at the chosen time for "arrive by" (which can mean starting the evening before). Null when the
     * route doesn't repeat or has no path yet.
     */
    fun nextStart(route: Route, trafficPercent: Int, now: Long, zone: ZoneId, redLightPercent: Int = 0): Long? {
        val timing = route.timing
        if (!timing.repeats) return null
        val trip = route.expanded()
        val path = trip.travelPath(trafficPercent, redLightPercent) ?: return null
        val lead = if (timing.mode == TimingMode.ARRIVE_BY) Schedule.totalMillis(trip.stops, path) else 0L
        val clock = Instant.ofEpochMilli(timing.timeMillis!!).atZone(zone).toLocalTime().withSecond(0).withNano(0)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (d in -1L..8L) {
            val date = today.plusDays(d)
            if (timing.days and bit(date.dayOfWeek) == 0) continue
            val start = date.atTime(clock).atZone(zone).toInstant().toEpochMilli() - lead
            if (start > now) return start
        }
        return null
    }

    /**
     * "Every day", "Mon–Fri", "Sat, Sun", "Mon, Wed, Fri". [names] are the short day names Monday first; three or
     * more days in a row are written as a range.
     */
    fun daysText(days: Int, names: List<String>, everyDay: String): String {
        if (days and EVERY_DAY == EVERY_DAY) return everyDay
        val on = (0 until 7).filter { days and (1 shl it) != 0 }
        val parts = ArrayList<String>()
        var i = 0
        while (i < on.size) {
            var j = i
            while (j + 1 < on.size && on[j + 1] == on[j] + 1) j++
            if (j - i >= 2) parts += names[on[i]] + "–" + names[on[j]]
            else for (k in i..j) parts += names[on[k]]
            i = j + 1
        }
        return parts.joinToString(", ")
    }
}
