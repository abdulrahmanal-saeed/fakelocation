package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavExtras2Test {

    /** 5 km straight east. */
    private val start = LatLon(25.2, 55.27)
    private val road = NavRoute(listOf(start, CarMotion.shift(start, 5000.0, 0.0)), emptyList(), 5000.0, 300.0)

    // region Off the route

    @Test
    fun wrongWayIsSpottedInTwoFixes() {
        val d = OffRouteDetector()
        // Took an exit heading north while the route goes east: 25 m away and climbing.
        assertFalse(d.update(25.0, 5f, 60.0, 0f, 90f))
        assertTrue(d.update(35.0, 5f, 60.0, 5f, 90f))
    }

    @Test
    fun aClearFixFarAwayIsEnoughOnItsOwn() {
        val d = OffRouteDetector()
        assertTrue(d.update(80.0, 8f, 50.0, 90f, 90f))
    }

    @Test
    fun aPoorSignalNeedsThreeFixes() {
        val d = OffRouteDetector()
        assertFalse(d.update(80.0, 40f, 50.0, null, 90f))
        assertFalse(d.update(80.0, 40f, 50.0, null, 90f))
        assertTrue(d.update(80.0, 40f, 50.0, null, 90f))
    }

    @Test
    fun noFalseAlarms() {
        val d = OffRouteDetector()
        // Slow (a car park, a U-turn on the spot): the GPS heading means little.
        repeat(5) { assertFalse(d.update(30.0, 5f, 8.0, 270f, 90f)) }
        // Right next to the route going another way (a jumpy heading on the right road).
        repeat(5) { assertFalse(d.update(10.0, 5f, 60.0, 180f, 90f)) }
        // The GPS isn't sure enough to tell 25 m from the road.
        repeat(5) { assertFalse(d.update(25.0, 30f, 60.0, 0f, 90f)) }
        // Going the right way a bit off the line (wide road, parallel lane).
        repeat(5) { assertFalse(d.update(30.0, 5f, 60.0, 80f, 90f)) }
        // Back on the route resets it.
        d.update(80.0, 40f, 50.0, null, 90f)
        d.update(80.0, 40f, 50.0, null, 90f)
        d.update(5.0, 5f, 50.0, 90f, 90f)
        assertFalse(d.update(80.0, 40f, 50.0, null, 90f))
    }

    // endregion

    // region Average speed checks

    private fun relation(vararg members: Pair<String, LatLon>, maxspeed: String = "120") = JSONObject()
        .put("type", "relation")
        .put("tags", JSONObject().put("type", "enforcement").put("enforcement", "average_speed").put("maxspeed", maxspeed))
        .put("members", JSONArray(members.map { (role, p) -> JSONObject().put("type", "node").put("role", role).put("lat", p.lat).put("lon", p.lon) }))

    @Test
    fun readsAZoneFromOpenStreetMap() {
        val zone = RoadInfoFetcher.averageZone(
            road,
            relation("from" to CarMotion.shift(start, 1000.0, 8.0), "device" to CarMotion.shift(start, 1000.0, 12.0), "to" to CarMotion.shift(start, 4000.0, -5.0)),
        )!!
        assertEquals(1000.0, zone.fromAlong, 5.0)
        assertEquals(4000.0, zone.toAlong, 5.0)
        assertEquals(120, zone.limitKmh)
    }

    @Test
    fun camerasAloneMarkTheEnds() {
        val zone = RoadInfoFetcher.averageZone(road, relation("device" to CarMotion.shift(start, 3000.0, 0.0), "device" to CarMotion.shift(start, 1500.0, 0.0)))!!
        assertEquals(1500.0, zone.fromAlong, 5.0)
        assertEquals(3000.0, zone.toAlong, 5.0)
    }

    @Test
    fun ignoresZonesTheOtherWayOrOffTheRoute() {
        // Runs west: the opposite carriageway's check.
        assertNull(RoadInfoFetcher.averageZone(road, relation("from" to CarMotion.shift(start, 4000.0, 0.0), "to" to CarMotion.shift(start, 1000.0, 0.0))))
        // On another road 500 m away.
        assertNull(RoadInfoFetcher.averageZone(road, relation("from" to CarMotion.shift(start, 1000.0, 500.0), "to" to CarMotion.shift(start, 4000.0, 500.0))))
        // Not an average speed check.
        val other = relation("from" to start, "to" to CarMotion.shift(start, 3000.0, 0.0))
        other.getJSONObject("tags").put("enforcement", "maxspeed")
        assertNull(RoadInfoFetcher.averageZone(road, other))
    }

    @Test
    fun zonesComeThroughTheParserAndMerge() {
        val json = JSONObject().put("elements", JSONArray().put(relation("from" to CarMotion.shift(start, 1000.0, 0.0), "to" to CarMotion.shift(start, 2000.0, 0.0))))
        val info = RoadInfoFetcher.parse(road, json)
        assertEquals(1, info.averageZones.size)
        val merged = info.merge(info)
        assertEquals(1, merged.averageZones.size)
    }

    @Test
    fun averageSpeedThroughAZone() {
        val check = AverageSpeedCheck()
        val zones = listOf(AverageZone(1000.0, 4000.0, 100))
        assertNull(check.update(zones, 900.0, 0))
        val entered = check.update(zones, 1000.0, 1000)!!
        assertTrue(entered.entered)
        assertNull(entered.averageKmh) // too early to say
        assertEquals(3000.0, entered.leftMeters, 1e-9)
        // 1 km in 30 s = 120 km/h: over the 100 limit.
        val later = check.update(zones, 2000.0, 31_000)!!
        assertFalse(later.entered)
        assertEquals(120, later.averageKmh)
        assertTrue(later.over)
        // Slowing down brings the average under it again: 2 km in 80 s = 90 km/h.
        val slower = check.update(zones, 3000.0, 81_000)!!
        assertEquals(90, slower.averageKmh)
        assertFalse(slower.over)
        assertNull(check.update(zones, 4000.0, 100_000))
        // Entering again starts afresh.
        assertNotNull(check.update(zones, 1200.0, 200_000)?.takeIf { it.entered })
    }

    // endregion
}
