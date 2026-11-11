package com.abdulrahman.fakelocation.nav

import kotlin.math.exp

/**
 * A heading that follows its target smoothly: small wobbles are soaked up and a real turn is followed at a steady
 * pace (at most [maxDegreesPerSecond]), always the short way round (350° → 10° turns 20°, not 340°).
 */
class HeadingSmoother(
    private val blendMs: Double = 280.0,
    private val maxDegreesPerSecond: Double = 160.0,
) {
    private var value: Double? = null
    private var at = 0L

    val current: Float? get() = value?.toFloat()

    fun reset() {
        value = null
    }

    /** One step toward [target] degrees at [now] ms; returns the heading to show. */
    fun update(target: Float, now: Long): Float {
        val v = value
        val gap = now - at
        at = now
        if (v == null || gap < 0) {
            value = normalize(target.toDouble())
            return value!!.toFloat()
        }
        // After a pause in frames, carry on from where it was rather than jumping.
        val dt = gap.coerceAtMost(100L)
        val diff = difference(v, target.toDouble())
        val max = maxDegreesPerSecond * dt / 1000.0
        val step = (diff * (1 - exp(-dt / blendMs))).coerceIn(-max, max)
        value = normalize(v + step)
        return value!!.toFloat()
    }

    companion object {
        /** Signed short way from [from] to [to], in −180..180. */
        fun difference(from: Double, to: Double): Double = ((to - from) % 360 + 540) % 360 - 180

        fun normalize(degrees: Double): Double = (degrees % 360 + 360) % 360
    }
}
