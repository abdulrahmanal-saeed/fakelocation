package com.abdulrahman.fakelocation.drive

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.NavRouter
import com.abdulrahman.fakelocation.nav.RoadInfoFetcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TollsTest {

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(Tolls.DUBAI).toInstant().toEpochMilli()

    private val garhoud = Tolls.GATES.first { it.name == "Al Garhoud Bridge" }
    private val sheikhZayedBridge = Tolls.GATES.first { it.name == "Sheikh Zayed Bridge" }

    @Test
    fun salikPrices() {
        assertEquals(6.0, Tolls.price(garhoud, at("2026-10-19T08:30")), 0.0) // Monday morning peak
        assertEquals(4.0, Tolls.price(garhoud, at("2026-10-19T12:00")), 0.0) // off-peak
        assertEquals(6.0, Tolls.price(garhoud, at("2026-10-19T17:15")), 0.0) // evening peak
        assertEquals(4.0, Tolls.price(garhoud, at("2026-10-19T22:00")), 0.0)
        assertEquals(0.0, Tolls.price(garhoud, at("2026-10-19T03:00")), 0.0) // night
        assertEquals(4.0, Tolls.price(garhoud, at("2026-10-18T08:30")), 0.0) // Sunday
    }

    @Test
    fun darbPrices() {
        assertEquals(4.0, Tolls.price(sheikhZayedBridge, at("2026-10-19T07:30")), 0.0)
        assertEquals(4.0, Tolls.price(sheikhZayedBridge, at("2026-10-19T16:00")), 0.0)
        assertEquals(0.0, Tolls.price(sheikhZayedBridge, at("2026-10-19T11:00")), 0.0)
        assertEquals(0.0, Tolls.price(sheikhZayedBridge, at("2026-10-18T08:00")), 0.0) // Sunday free
        assertEquals(4.0, Tolls.price(sheikhZayedBridge.copy(allDay = true), at("2026-10-18T23:00")), 0.0)
    }

    /** A straight route through the given points, 50 m per second. */
    private fun line(vararg points: LatLon) = NavRoute(points.toList(), emptyList(), 0.0, 0.0)

    private fun passes(route: NavRoute, start: Long) = Tolls.passes(
        Tolls.GATES, start, { RoadInfoFetcher.alongOf(route, it, 25.0) }, { it / 20.0 },
    )

    @Test
    fun findsGatesOnTheRouteOnly() {
        // Across Al Garhoud bridge, south-west to north-east through the gantries.
        val across = line(LatLon(25.2290, 55.3343), LatLon(25.2330, 55.3370))
        val found = passes(across, at("2026-10-19T08:00"))
        assertEquals(listOf("Al Garhoud Bridge"), found.map { it.gate.name })
        assertEquals(6.0, Tolls.total(found), 0.0)
        // A road 300 m away doesn't pay.
        val parallel = line(LatLon(25.2290, 55.3373), LatLon(25.2330, 55.3400))
        assertTrue(passes(parallel, at("2026-10-19T08:00")).isEmpty())
    }

    @Test
    fun mamzarPairIsChargedOnce() {
        // Up Al Ittihad Road through Al Mamzar South then North within minutes.
        val route = line(LatLon(25.2800, 55.3580), LatLon(25.28600, 55.35910), LatLon(25.29614, 55.36130), LatLon(25.3000, 55.3620))
        val found = passes(route, at("2026-10-19T12:00"))
        assertEquals(listOf("Al Mamzar South", "Al Mamzar North"), found.map { it.gate.name })
        assertEquals(4.0, Tolls.total(found), 0.0)
    }

    @Test
    fun realOsrmRouteThroughDubai() {
        val route = NavRouter.parseOsrm(JSONObject(javaClass.classLoader!!.getResource("osrm_stops.json")!!.readText()))
        val found = passes(route, at("2026-10-19T12:00"))
        // Whatever gates it uses are in order along the way and priced off-peak.
        for ((a, b) in found.zipWithNext()) assertTrue(a.alongMeters <= b.alongMeters)
        found.forEach { assertEquals(4.0, it.priceAed, 0.0) }
    }

    @Test
    fun extraGatesSkipKnownOnes() {
        val extra = Tolls.extraGates(listOf(LatLon(25.23070, 55.33570) to "Al Garhoud", LatLon(24.80, 54.90) to "Ghantoot"))
        assertEquals(listOf("Ghantoot"), extra.map { it.name })
        assertEquals(TollSystem.DARB, extra.single().system)
    }

    @Test
    fun fuelCost() {
        // 25 km at 8 L/100 km and AED 4.28 → 2 L → AED 8.56
        assertEquals(8.56, Fuel.cost(25_000.0, 8.0, 4.28), 0.001)
        assertEquals("AED 8.56", Fuel.aed(8.56))
    }

    @Test
    fun parkingSmsAndJson() {
        assertEquals("A12345 365A 2", Parking.smsBody("a12345", "365a", 2))
        assertTrue(Parking.isZoneCode("365A"))
        assertTrue(Parking.isZoneCode("118C"))
        assertTrue(!Parking.isZoneCode("Al Wasl"))
        val p = Parking("365A", "A12345", LatLon(25.2, 55.27), "Al Wasl Rd", 1000L, 7_201_000L)
        assertEquals(p, Parking.fromJson(p.toJson()))
        assertEquals(p.copy(position = null, placeName = null), Parking.fromJson(p.copy(position = null, placeName = null).toJson()))
    }
}
