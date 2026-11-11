package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.drive.TollPass
import com.abdulrahman.fakelocation.drive.Tolls
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteOptionTest {

    private val gate = Tolls.GATES.first()

    private fun option(seconds: Double, meters: Double, vararg tollsAed: Double) = RouteOption(
        NavRoute(listOf(LatLon(25.2, 55.3), LatLon(25.2, 55.4)), emptyList(), meters, seconds),
        tollsAed.map { TollPass(gate, 0.0, it) },
    )

    @Test
    fun fastestAndWhatTheOthersOffer() {
        val tags = RouteOption.tags(listOf(
            option(900.0, 12_000.0, 4.0, 4.0), // quickest, two gates
            option(1100.0, 14_000.0),          // no gates
            option(1000.0, 13_000.0, 4.0),     // one gate less
            option(1200.0, 9_000.0, 4.0, 4.0), // shorter
            option(1300.0, 12_500.0, 4.0, 4.0),
        ))
        assertEquals(
            listOf(RouteOption.Tag.FASTEST, RouteOption.Tag.NO_TOLLS, RouteOption.Tag.CHEAPER, RouteOption.Tag.SHORTER, RouteOption.Tag.OTHER),
            tags,
        )
    }

    @Test
    fun fastestNeedNotBeFirst() {
        val tags = RouteOption.tags(listOf(option(1000.0, 10_000.0), option(800.0, 12_000.0)))
        assertEquals(listOf(RouteOption.Tag.SHORTER, RouteOption.Tag.FASTEST), tags)
        assertTrue(RouteOption.tags(emptyList()).isEmpty())
    }

    @Test
    fun tollTotal() {
        assertEquals(10.0, option(900.0, 1000.0, 4.0, 6.0).tollAed, 0.0)
        assertEquals(0.0, option(900.0, 1000.0).tollAed, 0.0)
    }

    private fun json(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test
    fun osrmAlternativesAreAllRead() {
        val json = json("osrm_steps.json")
        val routes = json.getJSONArray("routes")
        val second = JSONObject(routes.getJSONObject(0).toString()).put("duration", 9999.0)
        routes.put(second)
        routes.put(JSONObject()) // a broken alternative must not cost us the others
        val parsed = NavRouter.parseOsrmRoutes(json)
        assertEquals(2, parsed.size)
        assertEquals(9999.0, parsed[1].durationSeconds, 0.0)
        assertEquals(parsed[0].points, parsed[1].points)
        // parseOsrm still gives the first one.
        assertEquals(parsed[0].durationSeconds, NavRouter.parseOsrm(json).durationSeconds, 0.0)
    }

    @Test
    fun valhallaAlternatesAreRead() {
        val json = json("valhalla_stops.json")
        val trip = JSONObject(json.getJSONObject("trip").toString())
        trip.getJSONObject("summary").put("time", 4321.0)
        json.put("alternates", org.json.JSONArray().put(JSONObject().put("trip", trip)).put(JSONObject()))
        val parsed = NavRouter.parseValhallaRoutes(json)
        assertEquals(2, parsed.size)
        assertEquals(4321.0, parsed[1].durationSeconds, 0.0)
    }

    @Test
    fun googleAlternativesAreRead() {
        val line = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        fun route(duration: String) = """{"distanceMeters":1000,"duration":"$duration","polyline":{"encodedPolyline":"$line"},
            "legs":[{"steps":[{"polyline":{"encodedPolyline":"$line"},"navigationInstruction":{"maneuver":"DEPART","instructions":"Head north"}}]}]}"""
        val parsed = NavRouter.parseGoogleRoutes(JSONObject("""{"routes":[${route("600s")},${route("750s")},{}]}"""), trafficAware = true)
        assertEquals(listOf(600.0, 750.0), parsed.map { it.durationSeconds })
        assertTrue(parsed.all { it.trafficAware })
    }
}

/** A real answer from the Valhalla server: Downtown Dubai to Dubai Marina, with two alternates. */
class ValhallaAlternatesTest {

    private val routes = NavRouter.parseValhallaRoutes(
        JSONObject(javaClass.classLoader!!.getResource("valhalla_alternates.json")!!.readText())
    )

    @Test
    fun threeDifferentWays() {
        assertEquals(listOf(2174.0, 2125.0, 2576.0), routes.map { Math.round(it.durationSeconds).toDouble() })
        for (r in routes) {
            assertEquals(Maneuver.ARRIVE, r.steps.last().maneuver)
            assertEquals(r.distanceMeters, r.length, r.distanceMeters * 0.01)
        }
        // They really are different roads, so all three survive.
        assertEquals(3, NavRouter.distinct(routes).size)
        assertTrue(FasterRoute.sharedFraction(routes[2], routes[0]) < 0.9)
    }

    @Test
    fun taggedByTimeAndTolls() {
        val noon = java.time.LocalDateTime.parse("2026-10-19T12:00").atZone(Tolls.DUBAI).toInstant().toEpochMilli()
        val options = routes.map { r ->
            RouteOption(r, Tolls.passes(Tolls.GATES, noon,
                alongOf = { RoadInfoFetcher.alongOf(r, it, 25.0) },
                secondsTo = { along -> r.durationSeconds * along / r.length }))
        }
        val tags = RouteOption.tags(options)
        assertEquals(RouteOption.Tag.FASTEST, tags[1])
        // The quickest of the three goes through Salik gates; the first one was found without any.
        assertTrue(options[1].tolls.isNotEmpty())
        assertTrue(options[0].tolls.isEmpty())
        assertEquals(RouteOption.Tag.NO_TOLLS, tags[0])
    }
}
