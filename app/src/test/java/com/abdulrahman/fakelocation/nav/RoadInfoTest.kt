package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadInfoTest {

    private val route = NavRouter.parseOsrm(JSONObject(javaClass.classLoader!!.getResource("osrm_steps.json")!!.readText()))

    @Test
    fun readsLanesAtJunctions() {
        val dubai = NavRouter.parseOsrm(JSONObject(javaClass.classLoader!!.getResource("osrm_lanes.json")!!.readText()))
        val withLanes = dubai.steps.first { it.lanes.isNotEmpty() }
        assertEquals(6, withLanes.lanes.size)
        assertEquals(listOf(true, true, false, false, false, false), withLanes.lanes.map { it.valid })
        assertEquals(listOf("left"), withLanes.lanes.first().directions)
        assertTrue(dubai.steps.any { it.road.isNotBlank() })
    }

    @Test
    fun maxspeedValues() {
        assertEquals(60, RoadInfoFetcher.parseMaxspeed("60"))
        assertEquals(48, RoadInfoFetcher.parseMaxspeed("30 mph"))
        assertNull(RoadInfoFetcher.parseMaxspeed("EG:urban"))
        assertNull(RoadInfoFetcher.parseMaxspeed("none"))
    }

    @Test
    fun limitsFollowTheMappedRoad() {
        // A mapped road that covers the first 30 points of the route, with an 80 limit.
        val way = LimitedWay(80, route.points.subList(0, 30))
        val limits = RoadInfoFetcher.assignLimits(route, listOf(way))
        assertEquals(80, limits[5])
        assertEquals(0, limits[200])
    }

    @Test
    fun camerasOnTheRoute() {
        val onRoute = route.points[100]
        val offRoute = LatLon(onRoute.lat + 0.01, onRoute.lon)
        val along = RoadInfoFetcher.camerasAlong(route, listOf(offRoute, onRoute))
        assertEquals(1, along.size)
        assertEquals(route.along[100], along[0], 15.0)
        val info = RoadInfo(IntArray(0), along)
        assertEquals(along[0] - 50.0, info.nextCamera(50.0)!!, 0.01)
    }
}
