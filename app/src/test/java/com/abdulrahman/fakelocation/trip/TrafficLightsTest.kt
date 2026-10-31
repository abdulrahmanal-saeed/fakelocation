package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Leg
import com.abdulrahman.fakelocation.data.LightRule
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RouteJson
import com.abdulrahman.fakelocation.data.RoutePath
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.TrafficLight
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.geo.TrafficLights
import com.abdulrahman.fakelocation.nav.CarMotion
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.RoadInfoFetcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLightsTest {

    private val start = LatLon(25.2, 55.27)

    /** Points every 200 m due east, 10 m/s, one leg from the first to the last. */
    private fun straight(points: Int): RoutePath {
        val pts = (0 until points).map { CarMotion.shift(start, 200.0 * it, 0.0) }
        return RoutePath(pts, List(points - 1) { 20.0 }, listOf(Leg(200.0 * (points - 1), 20.0 * (points - 1), 0, points - 1)))
    }

    private fun route(path: RoutePath, mode: TravelMode = TravelMode.CAR) = Route(
        42, "Test", mode,
        listOf(Stop(1, "A", path.points.first(), 0), Stop(2, "B", path.points.last(), 0)),
        path,
    )

    // region Stopping at a light

    @Test
    fun stopsAtTheLightWithABrakeAndAPullAway() {
        val path = straight(3).withStops(mapOf(1 to 30.0))
        // 40 s of driving, 30 s standing, and 40 m of braking and 40 m of pulling away take twice as long: +8 s.
        assertEquals(78.0, path.legs.single().durationSeconds, 0.05)
        assertEquals(78.0, path.segmentSeconds.sum(), 0.05)
        assertEquals(400.0, path.points.zipWithNext { a, b -> distanceMeters(a, b) }.sum(), 0.5)

        val trip = TripTimeline(listOf(Stop(1, "A", start, 0), Stop(2, "B", path.points.last(), 0)), path, natural = false)
        assertEquals(78_000.0, trip.totalMs.toDouble(), 50.0)
        // 16 s in: just reaching the start of the braking; 30 s in: standing at the light, facing east.
        val standing = trip.at(30_000)
        assertEquals(TripPhase.MOVING, standing.phase)
        assertEquals(0f, standing.speedMps, 1e-6f)
        assertEquals(90f, standing.bearing, 0.5f)
        assertEquals(0.0, distanceMeters(standing.position, CarMotion.shift(start, 200.0, 0.0)), 0.5)
        // Braking: slower than cruising.
        assertTrue(trip.at(20_000).speedMps < 9f)
        // Back to cruising speed after pulling away.
        assertEquals(10f, trip.at(70_000).speedMps, 0.1f)
    }

    @Test
    fun naturalMovementLeavesTheWaitAlone() {
        val path = straight(4).withStops(mapOf(2 to 45.0))
        val natural = TripTimeline.slowAtTurns(path)
        val still = path.points.zipWithNext().indexOfFirst { (a, b) -> distanceMeters(a, b) < 0.1 }
        assertEquals(45.0, natural.segmentSeconds[still], 1e-9)
        assertEquals(path.segmentSeconds.sum(), natural.segmentSeconds.sum(), 1e-6)
    }

    @Test
    fun noStopAtTheTripsOwnStopsOrTheEnds() {
        val path = straight(3)
        assertEquals(path, path.withStops(mapOf(0 to 30.0, 2 to 30.0)))
        assertEquals(path, path.withStops(emptyMap()))
    }

    // endregion

    // region Which lights are red

    @Test
    fun redLightsFollowTheRulesAndTheSetting() {
        val lights = (1 until 40).map { TrafficLight(it) }
        val r = route(straight(41).copy(lights = lights))
        assertTrue(r.redLights(0).isEmpty())
        assertEquals(39, r.redLights(100).size)
        val some = r.redLights(45).size
        assertTrue("$some of 39", some in 8..28)
        // Always the same for the same route.
        assertEquals(r.redLights(45), r.redLights(45))
        assertTrue(r.redLights(45).values.all { it in 15.0..90.0 })
        // Set by hand: always stop for 2 minutes, or never.
        val ruled = r.copy(path = r.path!!.copy(lights = listOf(TrafficLight(5, LightRule.STOP, 120), TrafficLight(6, LightRule.GO))))
        assertEquals(mapOf(5 to 120.0), ruled.redLights(0))
        assertEquals(mapOf(5 to 120.0), ruled.redLights(100))
        // Walking: no lights.
        assertTrue(route(straight(41).copy(lights = lights), TravelMode.WALK).redLights(100).isEmpty())
    }

    @Test
    fun theTripTakesLongerWithRedLights() {
        val r = route(straight(11).copy(lights = listOf(TrafficLight(5, LightRule.STOP, 60))))
        val plain = r.travelPath(0, 0)!!.durationSeconds
        assertEquals(200.0 + 60 + 8, plain, 0.05)
        // At double speed everything takes half as long, the light too.
        assertEquals(134.0, r.copy(speed = 2.0).travelPath(0, 0)!!.durationSeconds, 0.05)
    }

    // endregion

    // region Lights travel with the path

    @Test
    fun lightsFollowTheWayBackAndLaps() {
        val path = straight(5).copy(lights = listOf(TrafficLight(1), TrafficLight(3, LightRule.STOP, 30)))
        assertEquals(listOf(TrafficLight(1, LightRule.STOP, 30), TrafficLight(3)), path.reversed().lights)
        val twice = path.then(path)
        assertEquals(listOf(1, 3, 5, 7), twice.lights.map { it.point })
        val json = RouteJson.read(RouteJson.write(route(path)))
        assertEquals(path.lights, json.path!!.lights)
    }

    @Test
    fun findsTheLightsOnThePath() {
        val path = straight(6)
        val lights = listOf(
            CarMotion.shift(start, 400.0, 3.0), // on the road at point 2
            CarMotion.shift(start, 410.0, -6.0), // the same junction, other corner
            CarMotion.shift(start, 600.0, 40.0), // on a road alongside, not ours
            CarMotion.shift(start, 1000.0, 2.0), // at the last stop
            CarMotion.shift(start, 790.0, 4.0), // close to point 4
        )
        assertEquals(listOf(2, 4), TrafficLights.snap(path, lights).map { it.point })
        val json = JSONObject().put("elements", JSONArray()
            .put(JSONObject().put("type", "node").put("lat", 25.2).put("lon", 55.27))
            .put(JSONObject().put("type", "way")))
        assertEquals(1, TrafficLights.nodesOf(json).size)
    }

    // endregion

    // region Real navigation

    @Test
    fun roadInfoKeepsLightsApartFromCameras() {
        val road = NavRoute(straight(6).points, emptyList(), 1000.0, 100.0)
        fun node(highway: String, east: Double) = JSONObject().put("type", "node")
            .put("lat", CarMotion.shift(start, east, 2.0).lat).put("lon", CarMotion.shift(start, east, 2.0).lon)
            .put("tags", JSONObject().put("highway", highway))
        val json = JSONObject().put("elements", JSONArray()
            .put(node("traffic_signals", 300.0))
            .put(node("traffic_signals", 320.0))
            .put(node("traffic_signals", 700.0))
            .put(node("speed_camera", 500.0)))
        val info = RoadInfoFetcher.parse(road, json)
        assertEquals(1, info.camerasAlong.size)
        assertEquals(listOf(300.0, 700.0), info.lightsAlong.map { Math.round(it / 10.0) * 10.0 })
        // Just passed (a few metres) still counts as here; then the next one.
        assertEquals(390.0, info.nextLight(310.0)!!, 2.0)
        // A turn at 700 m has lights; one at 500 m doesn't.
        assertTrue(info.lightAt(710.0))
        assertFalse(info.lightAt(500.0))
        assertEquals(2, info.merge(info).lightsAlong.size)
    }

    // endregion
}
