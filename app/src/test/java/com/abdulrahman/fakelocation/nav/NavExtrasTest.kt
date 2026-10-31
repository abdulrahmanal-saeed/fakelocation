package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.Drive
import com.abdulrahman.fakelocation.data.DriveLog
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.TravelMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Other ways to go, the night map and the drive log. */
class NavExtrasTest {

    private fun json(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test
    fun osrmOtherWays() {
        val answer = json("osrm_steps.json")
        val routes = answer.getJSONArray("routes")
        // The same answer with a second, longer way added after the first.
        val other = JSONObject(routes.getJSONObject(0).toString()).put("distance", routes.getJSONObject(0).getDouble("distance") * 1.2)
            .put("duration", routes.getJSONObject(0).getDouble("duration") * 1.3)
        routes.put(other)
        val all = NavRouter.parseOsrmAll(answer)
        assertEquals(2, all.size)
        assertEquals(NavRouter.parseOsrm(answer).distanceMeters, all.first().distanceMeters, 0.0)
        assertTrue(all[1].durationSeconds > all[0].durationSeconds)

        // A broken other way is skipped; the main one is kept.
        routes.put(JSONObject().put("legs", JSONArray()))
        assertEquals(2, NavRouter.parseOsrmAll(answer).size)
    }

    @Test
    fun valhallaOtherWays() {
        val answer = json("valhalla_stops.json")
        answer.put("alternates", JSONArray().put(JSONObject().put("trip", answer.getJSONObject("trip"))).put(JSONObject().put("error", "x")))
        assertEquals(2, NavRouter.parseValhallaAll(answer).size)
    }

    @Test
    fun sameWayTwiceIsShownOnce() {
        val route = NavRouter.parseOsrm(json("osrm_steps.json"))
        val almost = route.copy(distanceMeters = route.distanceMeters * 1.005, durationSeconds = route.durationSeconds * 1.004)
        val other = route.copy(distanceMeters = route.distanceMeters * 1.3)
        assertEquals(listOf(route, other), NavRouter.distinct(listOf(route, almost, other)))
        assertEquals(3, NavRouter.distinct(List(5) { i -> route.copy(distanceMeters = route.distanceMeters * (1 + i * 0.1)) }).size)
    }

    // region Night map

    private val dubai = LatLon(25.2048, 55.2708)
    private fun at(text: String) = LocalDateTime.parse(text).atZone(ZoneId.of("Asia/Dubai")).toInstant().toEpochMilli()

    @Test
    fun sunInDubai() {
        // 31 October: sunrise about 06:20, sunset about 17:45 local time.
        assertTrue(NightMap.sunElevation(dubai, at("2026-10-31T12:00")) > 45)
        assertTrue(NightMap.sunElevation(dubai, at("2026-10-31T21:00")) < -20)
        assertTrue(NightMap.sunElevation(dubai, at("2026-10-31T07:00")) > 0)
        assertTrue(NightMap.sunElevation(dubai, at("2026-10-31T05:30")) < 0)
        assertTrue(NightMap.sunElevation(dubai, at("2026-10-31T17:30")) > 0)
        assertTrue(NightMap.sunElevation(dubai, at("2026-10-31T18:15")) < -2)
    }

    @Test
    fun nightMapModes() {
        val noon = at("2026-10-31T12:00")
        val night = at("2026-10-31T22:00")
        assertFalse(NightMap.isNight(NightMapMode.AUTO, dubai, noon))
        assertTrue(NightMap.isNight(NightMapMode.AUTO, dubai, night))
        assertTrue(NightMap.isNight(NightMapMode.ALWAYS, dubai, noon))
        assertFalse(NightMap.isNight(NightMapMode.OFF, dubai, night))
        // No position yet: Dubai's sun is close enough.
        assertTrue(NightMap.isNight(NightMapMode.AUTO, null, night))
    }

    // endregion

    // region Drive log

    private fun drive(start: String, minutes: Int, km: Double, tolls: Double, fuel: Double) =
        Drive(at(start), at(start), at(start) + minutes * 60_000L, "Work", TravelMode.CAR, km * 1000, tolls, if (tolls > 0) 1 else 0, fuel)

    @Test
    fun monthlyTotals() {
        val drives = listOf(
            drive("2026-10-30T07:30", 30, 25.0, 4.0, 8.5),
            drive("2026-10-02T17:00", 45, 30.0, 6.0, 10.0),
            drive("2026-09-29T08:00", 20, 12.0, 0.0, 4.0),
        )
        val months = DriveLog.months(drives, ZoneId.of("Asia/Dubai"))
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.month })
        val october = months.first()
        assertEquals(2, october.drives)
        assertEquals(55_000.0, october.meters, 0.0)
        assertEquals(10.0, october.tollsAed, 0.0)
        assertEquals(18.5, october.fuelAed, 1e-9)
        assertEquals(75 * 60.0, october.seconds, 0.0)
        assertEquals(50.0, drives.first().averageKmh, 1e-9)
    }

    @Test
    fun driveSurvivesJson() {
        val d = drive("2026-10-30T07:30", 30, 25.0, 4.0, 8.5)
        assertEquals(d, DriveLog.read(DriveLog.write(d)))
        assertEquals(TravelMode.WALK, DriveLog.read(DriveLog.write(d.copy(mode = TravelMode.WALK))).mode)
    }

    // endregion
}
