package com.abdulrahman.fakelocation.mock

import com.abdulrahman.fakelocation.data.FakeSession
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.trip.TripPhase
import com.abdulrahman.fakelocation.trip.TripPoint
import com.abdulrahman.fakelocation.trip.TripTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the screens need to draw a running trip. */
data class TripUi(
    val routeName: String,
    val mode: TravelMode,
    val timeline: TripTimeline,
    val t: Long,
    val point: TripPoint,
    val paused: Boolean,
    val wallNow: Long,
    val beforeArriveMs: Long,
    val beforeLeaveMs: Long,
) {
    val stops get() = timeline.stops

    /** Wall-clock time of a trip-clock moment, if the trip keeps running from now. */
    fun wallOf(tripMs: Long) = wallNow + (tripMs - t)

    val nextStop: Int?
        get() = when (point.phase) {
            TripPhase.SCHEDULED -> 0
            TripPhase.MOVING -> point.stopIndex + 1
            TripPhase.WAITING -> (point.stopIndex + 1).takeIf { it <= stops.lastIndex }
            TripPhase.FINISHED -> null
        }
}

/** Shared state between the screens and the background service. */
object MockState {
    private val _session = MutableStateFlow<FakeSession?>(null)
    val session: StateFlow<FakeSession?> = _session

    private val _trip = MutableStateFlow<TripUi?>(null)
    val trip: StateFlow<TripUi?> = _trip

    fun set(session: FakeSession?) {
        _session.value = session
    }

    fun setTrip(trip: TripUi?) {
        _trip.value = trip
    }

    /** True while the phone is being given a fake position (fixed or trip). */
    val isFaking: Boolean
        get() = _session.value != null || _trip.value?.let { it.point.phase != TripPhase.SCHEDULED } == true
}
