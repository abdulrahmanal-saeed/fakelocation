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
        val way = MappedWay(80, route.points.subList(0, 30))
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

    @Test
    fun turnLanesTag() {
        val lanes = RoadInfoFetcher.lanesFor("left|through|through;right|right", Maneuver.RIGHT)!!
        assertEquals(listOf(false, false, true, true), lanes.map { it.valid })
        assertEquals(listOf("straight", "right"), lanes[2].directions)
        // Going straight on: the through lanes.
        assertEquals(listOf(false, true, true, false), RoadInfoFetcher.lanesFor("left|through|through;right|right", Maneuver.STRAIGHT)!!.map { it.valid })
        // A tag with no lane our way is ignored.
        assertNull(RoadInfoFetcher.lanesFor("left|left", Maneuver.RIGHT))
    }

    @Test
    fun lanesBeforeATurn() {
        val step = route.steps.withIndex().first { it.value.maneuver == Maneuver.RIGHT || it.value.maneuver == Maneuver.LEFT }
        val at = route.along[step.value.pointIndex]
        // The approach road, drawn in the direction we drive.
        val way = MappedWay(0, route.slice(at - 120, at), lanesForward = "left|through|right")
        val lanes = RoadInfoFetcher.stepLanes(route, listOf(way), 0.0, Double.MAX_VALUE)[step.index]!!
        assertEquals(3, lanes.size)
        assertEquals(1, lanes.count { it.valid })
        // Mapped the other way round, the forward tag doesn't apply.
        val reversed = MappedWay(0, route.slice(at - 120, at).reversed(), lanesForward = "left|through|right")
        assertNull(RoadInfoFetcher.stepLanes(route, listOf(reversed), 0.0, Double.MAX_VALUE)[step.index])
    }

    @Test
    fun simplifyKeepsShape() {
        val simple = RoadInfoFetcher.simplify(route.points, 5.0)
        assertTrue(simple.size < route.points.size)
        assertEquals(route.points.first(), simple.first())
        assertEquals(route.points.last(), simple.last())
        route.points.forEach { p ->
            val near = simple.zipWithNext().minOf { (a, b) ->
                val r = NavRoute(listOf(a, b), emptyList(), 0.0, 0.0)
                RoadInfoFetcher.alongOf(r, p, 1000.0)?.let { com.abdulrahman.fakelocation.data.distanceMeters(r.pointAt(it), p) } ?: 1000.0
            }
            assertTrue(near < 8.0)
        }
    }

    @Test
    fun chunksMerge() {
        val a = RoadInfo(intArrayOf(50, 0, 0), listOf(100.0), coveredTo = 20_000.0)
        val b = RoadInfo(intArrayOf(0, 0, 80), listOf(30_000.0), stepLanes = mapOf(3 to listOf(Lane(listOf("left"), true))), coveredTo = 40_000.0)
        val m = a.merge(b)
        assertEquals(listOf(50, 0, 80), m.limits.toList())
        assertEquals(listOf(100.0, 30_000.0), m.camerasAlong)
        assertEquals(40_000.0, m.coveredTo, 0.0)
        assertEquals(1, m.stepLanes.size)
    }

    @Test
    fun sliceAndPointAt() {
        val half = route.length / 2
        val slice = route.slice(half, half + 500)
        val len = slice.zipWithNext().sumOf { (a, b) -> com.abdulrahman.fakelocation.data.distanceMeters(a, b) }
        assertEquals(500.0, len, 2.0)
        assertEquals(route.points.first(), route.pointAt(0.0))
    }
}
