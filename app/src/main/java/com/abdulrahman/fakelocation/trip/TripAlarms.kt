package com.abdulrahman.fakelocation.trip

enum class AlertKind { ARRIVING, LEAVING, ARRIVED, FINISHED }

data class Alert(val kind: AlertKind, val stopIndex: Int) {
    val key get() = "${kind.name}:$stopIndex"
}

/** Which alerts are due at trip time [t]. Each alert has a window; once it has passed it never fires late. */
object TripAlarms {
    fun due(timeline: TripTimeline, t: Long, fired: Set<String>, beforeArriveMs: Long, beforeLeaveMs: Long): List<Alert> {
        if (t < 0) return emptyList()
        val last = timeline.stops.lastIndex
        val result = mutableListOf<Alert>()
        for (i in 1..last) {
            val arrive = timeline.arrive[i]
            if (beforeArriveMs > 0 && t >= arrive - beforeArriveMs && t < arrive) result += Alert(AlertKind.ARRIVING, i)
            if (i < last && t >= arrive && t < arrive + 60_000L && t < timeline.leave[i]) result += Alert(AlertKind.ARRIVED, i)
        }
        for (i in 0 until last) {
            val leave = timeline.leave[i]
            val waitMs = leave - timeline.arrive[i]
            if (beforeLeaveMs > 0 && waitMs > beforeLeaveMs && t >= leave - beforeLeaveMs && t < leave) result += Alert(AlertKind.LEAVING, i)
        }
        if (t >= timeline.totalMs) result += Alert(AlertKind.FINISHED, last)
        return result.filter { it.key !in fired }
    }
}

/** The trip clock: runs with real time, can be paused, and can jump (skip / leave now). */
data class TripClock(val base: Long, val realBase: Long, val paused: Boolean) {
    fun at(real: Long): Long = if (paused) base else base + (real - realBase)
    fun pause(real: Long) = TripClock(at(real), real, true)
    fun resume(real: Long) = TripClock(base, real, false)
    fun jumpTo(t: Long, real: Long) = TripClock(t, real, paused)

    companion object {
        /** Trip clock 0 (arriving at the first stop) happens at wall time [startAt]. */
        fun startingAt(startAt: Long) = TripClock(0, startAt, false)
    }
}
