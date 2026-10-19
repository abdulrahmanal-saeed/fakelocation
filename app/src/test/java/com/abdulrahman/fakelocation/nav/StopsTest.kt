package com.abdulrahman.fakelocation.nav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routes with an in-between stop (two legs), from OSRM and Valhalla. */
class StopsTest {

    private fun json(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    private fun check(route: NavRoute) {
        assertEquals(1, route.stopPoints.size)
        val stopAlong = route.stopsAlong.single()
        assertTrue(stopAlong > 0 && stopAlong < route.length)
        // One "reached stop 1" instruction exactly at the stop, and the final arrival at the end.
        val reached = route.steps.filter { it.text == "You have reached stop 1" }
        assertEquals(1, reached.size)
        assertEquals(route.stopPoints.single(), reached.single().pointIndex)
        assertEquals(Maneuver.ARRIVE, route.steps.last().maneuver)
        assertEquals(1, route.steps.count { it.maneuver == Maneuver.DEPART })
        for ((a, b) in route.steps.zipWithNext()) assertTrue(a.pointIndex <= b.pointIndex)
        for ((a, b) in route.points.zipWithNext()) assertTrue(a != b)
        assertEquals(route.distanceMeters, route.length, route.distanceMeters * 0.02)
    }

    @Test
    fun osrmWithAStop() = check(NavRouter.parseOsrm(json("osrm_stops.json")))

    @Test
    fun valhallaWithAStop() {
        val route = NavRouter.parseValhalla(json("valhalla_stops.json"))
        check(route)
        // English instructions, roundabouts folded into one step.
        assertTrue(route.steps.any { it.maneuver == Maneuver.ROUNDABOUT || it.maneuver == Maneuver.LEFT || it.maneuver == Maneuver.RIGHT })
        assertTrue(route.steps.none { it.text.isBlank() })
    }

    @Test
    fun trackerCountsTheStop() {
        val route = NavRouter.parseOsrm(json("osrm_stops.json"))
        val tracker = NavTracker(route)
        val before = tracker.update(route.points[route.stopPoints.single() / 2])
        assertTrue(before.alongMeters < route.stopsAlong.single())
        assertTrue(!before.arrived)
        val after = tracker.update(route.points[route.stopPoints.single() + 3])
        assertTrue(after.alongMeters > route.stopsAlong.single())
        assertTrue(!after.arrived)
    }
}
