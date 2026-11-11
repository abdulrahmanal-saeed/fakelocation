package com.abdulrahman.fakelocation.nav

/**
 * Your average speed through an average speed check (the cameras time you between two points): from where you
 * entered it, worked out from distance and time like the cameras do.
 */
class AverageSpeedCheck {

    /** Inside a zone: the zone, whether we just entered it, the average so far (null for the first seconds), metres left. */
    data class Status(val zone: AverageZone, val entered: Boolean, val averageKmh: Int?, val leftMeters: Double) {
        val over get() = averageKmh != null && zone.limitKmh > 0 && averageKmh > zone.limitKmh
    }

    private var zone: AverageZone? = null
    private var enteredAlong = 0.0
    private var enteredAt = 0L

    fun reset() {
        zone = null
    }

    /** Where we are ([along] metres along the route, at [now] ms) against the route's [zones]. Null outside them. */
    fun update(zones: List<AverageZone>, along: Double, now: Long): Status? {
        val inside = zones.firstOrNull { along >= it.fromAlong && along < it.toAlong }
        if (inside == null) {
            zone = null
            return null
        }
        val entered = inside != zone
        if (entered) {
            zone = inside
            enteredAlong = along
            enteredAt = now
        }
        val seconds = (now - enteredAt) / 1000.0
        val average = if (seconds >= MIN_SECONDS && along > enteredAlong) ((along - enteredAlong) / seconds * 3.6).toInt() else null
        return Status(inside, entered, average, inside.toAlong - along)
    }

    companion object {
        /** Before this, an average would mostly say how fast you came in. */
        const val MIN_SECONDS = 10.0
    }
}
