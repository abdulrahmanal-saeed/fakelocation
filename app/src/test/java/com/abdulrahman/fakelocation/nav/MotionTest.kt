package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.distanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotionTest {

    /** 400 m east, then a right-angle bend and 400 m north: a road with a corner to cut (or not). */
    private val corner = run {
        val a = LatLon(25.2000, 55.2700)
        val b = CarMotion.shift(a, 400.0, 0.0)
        val c = CarMotion.shift(b, 0.0, 400.0)
        NavRoute(listOf(a, b, c), emptyList(), 800.0, 60.0)
    }

    // region CarMotion

    @Test
    fun carriesOnAtTheSpeedOfTheLastFix() {
        val m = CarMotion()
        m.onFix(corner.pointAt(100.0), corner, 100.0, 20.0, 90f, 0)
        assertEquals(100.0, m.poseAt(0)!!.along!!, 0.01)
        assertEquals(110.0, m.poseAt(500)!!.along!!, 0.01)
        assertEquals(120.0, m.poseAt(1000)!!.along!!, 0.01)
    }

    @Test
    fun stopsGuessingAfterAWhileWithoutFixes() {
        val m = CarMotion()
        m.onFix(corner.pointAt(100.0), corner, 100.0, 20.0, 90f, 0)
        val capped = 100.0 + 20.0 * CarMotion.MAX_AHEAD_MS / 1000.0
        assertEquals(capped, m.poseAt(CarMotion.MAX_AHEAD_MS)!!.along!!, 0.01)
        assertEquals(capped, m.poseAt(10_000)!!.along!!, 0.01)
        assertFalse(m.moving(10_000))
    }

    @Test
    fun followsTheBendInsteadOfCuttingIt() {
        val m = CarMotion()
        m.onFix(corner.pointAt(380.0), corner, 380.0, 20.0, 90f, 0)
        // 2 s later: 420 m along, which is 20 m up the second leg, so it is right on the road.
        val pose = m.poseAt(2000)!!
        assertEquals(420.0, pose.along!!, 0.01)
        assertEquals(0.0, distanceMeters(pose.position, corner.pointAt(420.0)), 0.5)
        // A straight line between the fixes would have passed ~14 m inside the corner.
        val straight = LatLon((corner.pointAt(380.0).lat + corner.pointAt(420.0).lat) / 2, (corner.pointAt(380.0).lon + corner.pointAt(420.0).lon) / 2)
        assertTrue(distanceMeters(m.poseAt(1000)!!.position, straight) > 10)
    }

    @Test
    fun aNewFixIsBlendedInWithoutAJump() {
        val m = CarMotion()
        m.onFix(corner.pointAt(100.0), corner, 100.0, 20.0, 90f, 0)
        val before = m.poseAt(1000)!!.along!! // 120
        // The GPS says we're a bit further on than guessed.
        m.onFix(corner.pointAt(126.0), corner, 126.0, 20.0, 90f, 1000)
        assertEquals(before, m.poseAt(1000)!!.along!!, 0.01)
        // …and the difference is gone within a second or so.
        val later = m.poseAt(2500)!!.along!!
        assertEquals(126.0 + 20.0 * 1.5, later, 0.5)
    }

    @Test
    fun neverRollsBackwardsOnTheRoute() {
        val m = CarMotion()
        m.onFix(corner.pointAt(100.0), corner, 100.0, 20.0, 90f, 0)
        m.poseAt(1000) // drawn at 120
        // The car braked: the GPS says 110, and it isn't moving.
        m.onFix(corner.pointAt(110.0), corner, 110.0, 0.0, 90f, 1000)
        var last = 0.0
        for (t in 1000L..3000L step 16) {
            val along = m.poseAt(t)!!.along!!
            assertTrue("went back at $t: $along < $last", along >= last - 1e-9)
            last = along
        }
        assertEquals(120.0, last, 0.01)
    }

    @Test
    fun bigCorrectionsJumpInsteadOfGliding() {
        val m = CarMotion()
        m.onFix(corner.pointAt(100.0), corner, 100.0, 20.0, 90f, 0)
        m.poseAt(1000)
        m.onFix(corner.pointAt(300.0), corner, 300.0, 20.0, 90f, 1000)
        assertEquals(300.0, m.poseAt(1000)!!.along!!, 0.01)
    }

    @Test
    fun offTheRouteItCarriesOnAlongTheHeading() {
        val m = CarMotion()
        val start = LatLon(25.2, 55.27)
        m.onFix(start, null, null, 10.0, 0f, 0) // north at 10 m/s
        val pose = m.poseAt(1000)!!
        assertNull(pose.along)
        val (east, north) = CarMotion.metres(start, pose.position)
        assertEquals(0.0, east, 0.01)
        assertEquals(10.0, north, 0.05)
    }

    @Test
    fun joiningTheRouteGlidesFromWhereTheCarWas() {
        val m = CarMotion()
        val beside = CarMotion.shift(corner.pointAt(200.0), 0.0, 12.0) // 12 m north of the road
        m.onFix(beside, null, null, 0.0, 90f, 0)
        m.onFix(corner.pointAt(200.0), corner, 200.0, 0.0, 90f, 1000)
        assertEquals(12.0, distanceMeters(m.poseAt(1000)!!.position, corner.pointAt(200.0)), 0.2)
        assertEquals(0.0, distanceMeters(m.poseAt(3000)!!.position, corner.pointAt(200.0)), 0.2)
    }

    // endregion

    // region HeadingSmoother

    @Test
    fun headingTakesTheShortWayRound() {
        val h = HeadingSmoother()
        h.update(350f, 0)
        var v = 350f
        for (t in 16L..2000L step 16) v = h.update(10f, t)
        assertEquals(10.0, v.toDouble(), 0.5)
        // It went through 0, never through 180.
        val h2 = HeadingSmoother()
        h2.update(350f, 0)
        for (t in 16L..2000L step 16) {
            val x = h2.update(10f, t)
            assertTrue(x >= 349.9f || x <= 10.1f)
        }
    }

    @Test
    fun headingSoaksUpWobbleAndTurnsAtASteadyPace() {
        val h = HeadingSmoother(maxDegreesPerSecond = 90.0)
        h.update(0f, 0)
        // A 90° turn can't happen in less than a second.
        assertTrue(abs(h.update(90f, 500)) <= 45.01f)
        val w = HeadingSmoother()
        w.update(90f, 0)
        // A 6° flicker for one frame barely moves it.
        assertEquals(90.0, w.update(96f, 16).toDouble(), 0.5)
    }

    @Test
    fun differenceIsSigned() {
        assertEquals(20.0, HeadingSmoother.difference(350.0, 10.0), 1e-9)
        assertEquals(-20.0, HeadingSmoother.difference(10.0, 350.0), 1e-9)
        assertEquals(180.0, abs(HeadingSmoother.difference(0.0, 180.0)), 1e-9)
    }

    // endregion

    // region ZoomPlanner

    @Test
    fun zoomFollowsSpeedWithoutSteps() {
        assertEquals(17.8, ZoomPlanner.forSpeed(10.0), 1e-9)
        assertEquals(16.1, ZoomPlanner.forSpeed(140.0), 1e-9)
        val mid = ZoomPlanner.forSpeed(67.5)
        assertTrue(mid < 17.2 && mid > 16.6)
        var last = 99.0
        for (kmh in 0..150) {
            val z = ZoomPlanner.forSpeed(kmh.toDouble())
            assertTrue(z <= last)
            assertTrue(last == 99.0 || last - z < 0.05) // no jumps from one km/h to the next
            last = z
        }
    }

    @Test
    fun zoomsOutToShowAComingTurn() {
        // 2 px per metre at zoom 17, 1000 px of room above the car: a turn 600 m ahead needs 1200 px.
        val far = ZoomPlanner.target(60.0, 600.0, 17.0, 2.0, 1000.0)
        assertTrue(far < ZoomPlanner.forSpeed(60.0))
        assertEquals(17.0 + Math.log(1000.0 / 1200.0) / Math.log(2.0), far, 1e-6)
        // Close enough to fit already: just the speed's zoom; right at the turn a little closer.
        assertEquals(ZoomPlanner.forSpeed(60.0), ZoomPlanner.target(60.0, 200.0, 17.0, 2.0, 1000.0), 1e-9)
        assertEquals(ZoomPlanner.forSpeed(60.0) + ZoomPlanner.AT_TURN_IN, ZoomPlanner.target(60.0, 50.0, 17.0, 2.0, 1000.0), 1e-9)
        // Never more than MAX_OUT further out, and nothing for turns far away.
        assertEquals(ZoomPlanner.forSpeed(60.0) - ZoomPlanner.MAX_OUT, ZoomPlanner.target(60.0, 790.0, 17.0, 0.05, 10.0), 1e-9)
        assertEquals(ZoomPlanner.forSpeed(60.0), ZoomPlanner.target(60.0, 2000.0, 17.0, 2.0, 1000.0), 1e-9)
    }

    @Test
    fun zoomGlides() {
        var z = 17.0
        z = ZoomPlanner.ease(z, 16.0, 16)
        assertTrue(z < 17.0 && z > 16.95)
        repeat(400) { z = ZoomPlanner.ease(z, 16.0, 16) }
        assertEquals(16.0, z, 0.01)
    }

    // endregion

    // region DeadReckoning

    @Test
    fun deadReckoningCarriesOnAndEasesOff() {
        val d = DeadReckoning()
        d.start(1000.0, 20.0, 0)
        val oneSecond = d.alongAt(1000)!!
        assertEquals(1020.0, oneSecond, 0.2)
        val thirty = d.alongAt(30_000)!!
        assertTrue(thirty < 1000 + 20 * 30 && thirty > 1000 + 0.8 * 20 * 30)
        assertTrue(d.speedAt(30_000) < 20.0)
    }

    @Test
    fun deadReckoningGivesUp() {
        val d = DeadReckoning()
        d.start(0.0, 30.0, 0)
        assertNotNull(d.alongAt(60_000))
        assertNull(d.alongAt(DeadReckoning.MAX_MS + 1))
        val far = DeadReckoning(maxMeters = 500.0)
        far.start(0.0, 30.0, 0)
        assertNull(far.alongAt(30_000)) // ~780 m
        // Standing still (a jam in the tunnel): nothing to estimate.
        val still = DeadReckoning()
        still.start(0.0, 0.5, 0)
        assertFalse(still.active)
        assertNull(still.alongAt(1000))
    }

    // endregion
}
