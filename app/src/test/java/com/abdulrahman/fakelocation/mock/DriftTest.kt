package com.abdulrahman.fakelocation.mock

import com.abdulrahman.fakelocation.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DriftTest {

    private val here = LatLon(25.2, 55.27)

    /** How far the drift wanders in one second, on average (squared metres), sending [perSecond] fixes a second. */
    private fun spreadPerSecond(perSecond: Int): Double {
        val random = Random(11)
        var sum = 0.0
        val trials = 20_000
        repeat(trials) {
            val drift = Drift(random)
            repeat(perSecond) { drift.apply(here, 1.0 / perSecond) }
            val (east, north) = drift.offset
            sum += east * east + north * north
        }
        return sum / trials
    }

    @Test
    fun wandersTheSameWhateverTheUpdateRate() {
        val once = spreadPerSecond(1)
        val five = spreadPerSecond(5)
        assertEquals(once, five, once * 0.1)
    }

    @Test
    fun staysWithinAFewMetresAndReportsAPlausibleAccuracy() {
        val drift = Drift(Random(5))
        repeat(5000) {
            val (_, _, accuracy) = drift.apply(here, 0.2)
            val (east, north) = drift.offset
            assertTrue(Math.abs(east) <= Drift.LIMIT && Math.abs(north) <= Drift.LIMIT)
            assertTrue(accuracy in 3f..8f)
        }
    }
}
