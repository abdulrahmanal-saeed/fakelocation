package com.abdulrahman.fakelocation.nav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {

    // Google's documentation polyline: three points.
    private val line = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun jamFromSpeedReadingIntervals() {
        val json = JSONObject(
            """
            {"routes":[{"distanceMeters":1000,"duration":"600s",
              "polyline":{"encodedPolyline":"$line"},
              "travelAdvisory":{"speedReadingIntervals":[
                {"startPolylinePointIndex":0,"endPolylinePointIndex":1,"speed":"NORMAL"},
                {"startPolylinePointIndex":1,"endPolylinePointIndex":2,"speed":"TRAFFIC_JAM"}]},
              "legs":[{"steps":[{"polyline":{"encodedPolyline":"$line"},"navigationInstruction":{"maneuver":"DEPART","instructions":"Head north"}}]}]}]}
            """.trimIndent()
        )
        val route = NavRouter.parseGoogle(json, trafficAware = true)
        val jam = route.traffic.single()
        assertEquals(Traffic.JAM, jam.level)
        assertEquals(route.along[1], jam.fromAlong, 1.0)
        assertEquals(route.length, jam.toAlong, 1.0)
        assertTrue(route.trafficAware)
    }
}
