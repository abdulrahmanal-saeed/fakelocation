package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.nav.CarMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LocationKalmanTest {

    private val start = LatLon(25.2, 55.27)

    /** Total error of raw vs smoothed fixes around a spot we're standing at. */
    private fun standingStill(withSpeed: Boolean): Pair<Double, Double> {
        val k = LocationKalman(LocationKalman.WALK_ACCELERATION)
        val random = Random(7)
        var rawError = 0.0
        var smoothError = 0.0
        for (i in 0 until 60) {
            val fix = CarMotion.shift(start, random.nextDouble(-6.0, 6.0), random.nextDouble(-6.0, 6.0))
            val e = if (withSpeed) k.update(fix, 6.0, i * 1000L, 0.0, 0f) else k.update(fix, 6.0, i * 1000L)
            if (i >= 10) {
                rawError += distanceMeters(fix, start)
                smoothError += distanceMeters(e.position, start)
            }
        }
        return rawError to smoothError
    }

    @Test
    fun standingStillTheJitterShrinks() {
        val (raw, smooth) = standingStill(withSpeed = false)
        assertTrue("smoothed $smooth vs raw $raw", smooth < raw * 0.7)
        // The GPS saying "speed 0" (it usually does) settles it much more.
        val (raw2, smooth2) = standingStill(withSpeed = true)
        assertTrue("smoothed $smooth2 vs raw $raw2", smooth2 < raw2 * 0.4)
    }

    @Test
    fun keepsUpWithACarWithoutLagging() {
        val k = LocationKalman()
        // East at 25 m/s (90 km/h), GPS speed and heading included, a couple of metres of noise.
        val random = Random(3)
        var last = k.update(start, 4.0, 0, 25.0, 90f)
        for (i in 1..30) {
            val truth = CarMotion.shift(start, 25.0 * i, 0.0)
            last = k.update(CarMotion.shift(truth, random.nextDouble(-2.0, 2.0), random.nextDouble(-2.0, 2.0)), 4.0, i * 1000L, 25.0, 90f)
            if (i > 3) assertTrue("off by ${distanceMeters(last.position, truth)} at $i", distanceMeters(last.position, truth) < 3.0)
        }
        assertEquals(25.0, last.speedMps, 0.5)
        assertEquals(90.0, last.bearing!!.toDouble(), 2.0)
    }

    @Test
    fun followsATurn() {
        val k = LocationKalman()
        var t = 0L
        var at = start
        var heading = 90.0
        k.update(at, 4.0, t, 15.0, 90f)
        repeat(10) { t += 1000; at = CarMotion.shift(at, 15.0, 0.0); k.update(at, 4.0, t, 15.0, 90f) }
        // A left turn at 54 km/h, 30° a second (a junction), then straight on north.
        var worst = 0.0
        repeat(12) { i ->
            t += 1000
            if (i < 3) heading -= 30.0
            val rad = Math.toRadians(heading)
            at = CarMotion.shift(at, 15.0 * Math.sin(rad), 15.0 * Math.cos(rad))
            val e = k.update(at, 4.0, t, 15.0, heading.toFloat())
            assertFalse(e.rejected)
            worst = maxOf(worst, distanceMeters(e.position, at))
        }
        assertTrue("worst $worst m", worst < 5.0)
    }

    @Test
    fun aSharpTurnIsNotTakenForAGlitch() {
        val k = LocationKalman()
        var t = 0L
        var at = start
        k.update(at, 4.0, t, 15.0, 90f)
        repeat(10) { t += 1000; at = CarMotion.shift(at, 15.0, 0.0); k.update(at, 4.0, t, 15.0, 90f) }
        // A right angle in one second: unlikely, but possible, and not a reflection.
        t += 1000
        at = CarMotion.shift(at, 0.0, 15.0)
        assertFalse(k.update(at, 4.0, t, 15.0, 0f).rejected)
    }

    @Test
    fun ignoresAGlitchButAcceptsARealJump() {
        val k = LocationKalman()
        var t = 0L
        repeat(5) { k.update(start, 5.0, t); t += 1000 }
        // One fix 300 m away: a reflection, ignored.
        val glitch = k.update(CarMotion.shift(start, 300.0, 0.0), 5.0, t)
        assertTrue(glitch.rejected)
        assertTrue(distanceMeters(glitch.position, start) < 5)
        t += 1000
        assertFalse(k.update(start, 5.0, t).rejected)
        // Three far fixes in a row agree with each other: that's where we really are.
        val there = CarMotion.shift(start, 0.0, 400.0)
        var e = k.update(there, 5.0, t + 1000)
        assertTrue(e.rejected)
        e = k.update(there, 5.0, t + 2000)
        assertTrue(e.rejected)
        e = k.update(there, 5.0, t + 3000)
        assertFalse(e.rejected)
        assertTrue(distanceMeters(e.position, there) < 1)
    }

    @Test
    fun aLongGapStartsAfresh() {
        val k = LocationKalman()
        k.update(start, 5.0, 0)
        val far = CarMotion.shift(start, 2000.0, 0.0)
        val e = k.update(far, 5.0, 60_000)
        assertFalse(e.rejected)
        assertNotNull(e.position)
        assertTrue(distanceMeters(e.position, far) < 1)
    }
}
