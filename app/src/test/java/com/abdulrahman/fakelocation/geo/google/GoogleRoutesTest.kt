package com.abdulrahman.fakelocation.geo.google

import com.abdulrahman.fakelocation.data.LatLon
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class GoogleRoutesTest {

    @Test
    fun decodesGoogleExample() {
        // Example from Google's polyline documentation.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453)), points)
    }

    @Test
    fun buildsPathWithTrafficTimes() {
        val a = LatLon(30.0600, 31.3300)
        val b = LatLon(30.0610, 31.3200)
        val c = LatLon(30.0620, 31.3100)
        val d = LatLon(30.0500, 31.3100)
        val json = JSONObject().put("routes", JSONArray().put(JSONObject().put("legs", JSONArray()
            .put(leg(distance = 1900, duration = "300s", steps = listOf(listOf(a, b) to "60s", listOf(b, c) to "60s")))
            .put(leg(distance = 1300, duration = "90s", steps = listOf(listOf(c, d) to "90s")))
        )))
        val path = GoogleRoutes.parse(json, trafficAware = true)

        assertEquals(listOf(a, b, c, d), path.points)
        assertEquals(3, path.segmentSeconds.size)
        assertEquals(2, path.legs.size)
        assertEquals(0, path.legs[0].firstPoint)
        assertEquals(2, path.legs[0].lastPoint)
        assertEquals(2, path.legs[1].firstPoint)
        assertEquals(3, path.legs[1].lastPoint)
        // Leg 1 was 120 s without traffic but 300 s with it: the pieces are stretched to match.
        assertEquals(300.0, path.segmentSeconds[0] + path.segmentSeconds[1], 0.01)
        assertEquals(90.0, path.segmentSeconds[2], 0.01)
        assertTrue(path.trafficAware)
    }

    private fun leg(distance: Int, duration: String, steps: List<Pair<List<LatLon>, String>>) =
        JSONObject().put("distanceMeters", distance).put("duration", duration).put("steps", JSONArray(steps.map { (shape, time) ->
            JSONObject().put("staticDuration", time).put("polyline", JSONObject().put("encodedPolyline", encode(shape)))
        }))

    private fun encode(points: List<LatLon>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.lat * 1e5).roundToLong()
            val lon = (p.lon * 1e5).roundToLong()
            for (delta in listOf(lat - lastLat, lon - lastLon)) {
                var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
                while (v >= 0x20) {
                    out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
                    v = v shr 5
                }
                out.append((v + 63).toInt().toChar())
            }
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }
}
