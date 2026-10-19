package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTest {

    private val route = NavRouter.parseOsrm(JSONObject(javaClass.classLoader!!.getResource("osrm_steps.json")!!.readText()))

    @Test
    fun parsesStepsInOrder() {
        assertEquals(Maneuver.DEPART, route.steps.first().maneuver)
        assertEquals(Maneuver.ARRIVE, route.steps.last().maneuver)
        assertEquals(route.points.lastIndex, route.steps.last().pointIndex)
        for ((a, b) in route.steps.zipWithNext()) assertTrue(a.pointIndex <= b.pointIndex)
        // Total length matches what the router said, within a few metres.
        assertEquals(route.distanceMeters, route.length, 30.0)
        assertTrue(route.steps.any { it.text.startsWith("At the end of the road, turn right") })
    }

    @Test
    fun followsAlongTheRoute() {
        val tracker = NavTracker(route)
        val start = tracker.update(route.points.first())
        assertEquals(0.0, start.alongMeters, 1.0)
        assertFalse(start.arrived)
        assertNotNull(start.nextStep)

        var lastAlong = -1.0
        for (i in 0 until route.points.size step 5) {
            val p = tracker.update(route.points[i])
            assertTrue(p.offRouteMeters < 1.0)
            assertTrue(p.alongMeters >= lastAlong - 1)
            assertTrue(p.metersToNextStep >= 0)
            lastAlong = p.alongMeters
        }
        val end = tracker.update(route.points.last())
        assertTrue(end.arrived)
        assertEquals(0.0, end.remainingMeters, 1.0)
    }

    @Test
    fun noticesGoingOffRoute() {
        val tracker = NavTracker(route)
        val p = route.points[20]
        val away = tracker.update(LatLon(p.lat + 0.003, p.lon)) // ~330 m north
        assertTrue(away.offRouteMeters > NavTracker.OFF_ROUTE_METERS)
    }

    @Test
    fun instructionTexts() {
        assertEquals("Turn left onto Tahrir St", Instructions.fromOsrm("turn", "left", "Tahrir St", 0).text)
        assertEquals("At the roundabout, take the second exit", Instructions.fromOsrm("roundabout", "right", "", 2).text)
        assertEquals("Keep right", Instructions.fromOsrm("fork", "slight right", "", 0).spoken)
        // Voice skips Arabic street names, the screen keeps them.
        val arabic = Instructions.fromOsrm("turn", "right", "شارع الطيران", 0)
        assertEquals("Turn right onto شارع الطيران", arabic.text)
        assertEquals("Turn right", arabic.spoken)
    }
}
