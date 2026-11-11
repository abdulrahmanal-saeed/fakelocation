package com.abdulrahman.fakelocation.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SunTest {

    private fun at(text: String, zone: String): Long = LocalDateTime.parse(text).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private val dubaiLat = 25.2048
    private val dubaiLon = 55.2708

    @Test
    fun dubaiDayAndNight() {
        assertFalse(Sun.isNight(dubaiLat, dubaiLon, at("2026-11-11T12:00", "Asia/Dubai")))
        assertTrue(Sun.isNight(dubaiLat, dubaiLon, at("2026-11-11T23:30", "Asia/Dubai")))
        assertTrue(Sun.isNight(dubaiLat, dubaiLon, at("2026-11-11T03:00", "Asia/Dubai")))
    }

    @Test
    fun switchesAroundSunsetAndSunrise() {
        // Dubai, 11 November: sunrise about 06:30, sunset about 17:33.
        assertFalse(Sun.isNight(dubaiLat, dubaiLon, at("2026-11-11T17:00", "Asia/Dubai")))
        assertTrue(Sun.isNight(dubaiLat, dubaiLon, at("2026-11-11T18:00", "Asia/Dubai")))
        assertTrue(Sun.isNight(dubaiLat, dubaiLon, at("2026-11-11T06:00", "Asia/Dubai")))
        assertFalse(Sun.isNight(dubaiLat, dubaiLon, at("2026-11-11T07:00", "Asia/Dubai")))
        // Midsummer: sunset about 19:12.
        assertFalse(Sun.isNight(dubaiLat, dubaiLon, at("2026-06-21T18:45", "Asia/Dubai")))
        assertTrue(Sun.isNight(dubaiLat, dubaiLon, at("2026-06-21T19:40", "Asia/Dubai")))
    }

    @Test
    fun sunIsOnTheHorizonAtSunset() {
        // Within a degree or so of the horizon at the published sunset time.
        assertEquals(0.0, Sun.elevation(dubaiLat, dubaiLon, at("2026-11-11T17:33", "Asia/Dubai")), 1.5)
        assertEquals(0.0, Sun.elevation(dubaiLat, dubaiLon, at("2026-06-21T19:12", "Asia/Dubai")), 1.5)
    }

    @Test
    fun highAtNoonInSummerLowInWinter() {
        val summer = Sun.elevation(dubaiLat, dubaiLon, at("2026-06-21T12:20", "Asia/Dubai"))
        val winter = Sun.elevation(dubaiLat, dubaiLon, at("2026-12-21T12:20", "Asia/Dubai"))
        assertEquals(88.2, summer, 1.5) // 90 − 25.2 + 23.4
        assertEquals(41.4, winter, 1.5) // 90 − 25.2 − 23.4
    }

    @Test
    fun otherPlaces() {
        // Cairo, same moment as a Dubai evening: still daylight there two time zones west.
        val moment = at("2026-06-21T19:40", "Asia/Dubai") // 17:40 in Cairo, sunset about 19:59
        assertFalse(Sun.isNight(30.0444, 31.2357, moment))
        // Polar night in Tromsø in December, midnight sun in June.
        assertTrue(Sun.isNight(69.65, 18.96, at("2026-12-21T12:00", "Europe/Oslo")))
        assertFalse(Sun.isNight(69.65, 18.96, at("2026-06-21T00:30", "Europe/Oslo")))
    }
}
