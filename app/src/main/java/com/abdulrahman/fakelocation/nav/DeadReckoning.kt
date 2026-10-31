package com.abdulrahman.fakelocation.nav

import kotlin.math.pow

/**
 * Keeps the car moving along the route when the GPS drops out (tunnels, covered parking): from the last good fix it
 * goes on at the speed it had, easing off very slowly, for at most [maxMs] or [maxMeters], like Waze does.
 */
class DeadReckoning(
    private val maxMs: Long = MAX_MS,
    private val maxMeters: Double = MAX_METERS,
    /** Share of the speed lost per second (traffic in a tunnel tends to slow, not stop). */
    private val slowingPerSecond: Double = 0.01,
) {
    private var startAlong = 0.0
    private var startSpeed = 0.0
    private var startAt = 0L
    var active = false
        private set

    /** The GPS went quiet: [along] metres along the route at [speedMps], last seen at [at] ms. */
    fun start(along: Double, speedMps: Double, at: Long) {
        startAlong = along
        startSpeed = speedMps.coerceIn(0.0, 60.0)
        startAt = at
        active = startSpeed > 1.0
    }

    fun stop() {
        active = false
    }

    /** Estimated metres along the route at [now], or null once it has gone on too long (or isn't running). */
    fun alongAt(now: Long): Double? {
        if (!active) return null
        val seconds = (now - startAt).coerceAtLeast(0L) / 1000.0
        if (now - startAt > maxMs) return null
        // Distance with the speed falling by a constant share each second: v0 · (1 − k^t) / −ln k.
        val keep = 1 - slowingPerSecond
        val meters = if (slowingPerSecond > 0) startSpeed * (1 - keep.pow(seconds)) / -kotlin.math.ln(keep) else startSpeed * seconds
        if (meters > maxMeters) return null
        return startAlong + meters
    }

    /** Speed now, m/s. */
    fun speedAt(now: Long): Double {
        val seconds = (now - startAt).coerceAtLeast(0L) / 1000.0
        return startSpeed * (1 - slowingPerSecond).pow(seconds)
    }

    companion object {
        /** No fix for this long while driving the route: start estimating. */
        const val AFTER_MS = 3000L
        const val MAX_MS = 90_000L
        const val MAX_METERS = 2500.0
    }
}
