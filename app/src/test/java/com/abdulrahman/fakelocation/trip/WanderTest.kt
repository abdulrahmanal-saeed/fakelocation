package com.abdulrahman.fakelocation.trip

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.data.moved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WanderTest {

    private val mall = LatLon(25.1985, 55.2796)

    @Test
    fun movedGoesTheRightWayAndDistance() {
        val north = mall.moved(0.0, 1000.0)
        assertEquals(1000.0, distanceMeters(mall, north), 0.5)
        assertTrue(north.lat > mall.lat)
        assertEquals(mall.lon, north.lon, 1e-9)
        val east = mall.moved(90.0, 250.0)
        assertEquals(250.0, distanceMeters(mall, east), 0.5)
        assertTrue(east.lon > mall.lon)
        assertEquals(90.0, TripTimeline.bearing(mall, east).toDouble(), 0.1)
        // Joystick at a walk for one tick: 1.4 m/s for half a second.
        assertEquals(0.7, distanceMeters(mall, mall.moved(225.0, 0.7)), 0.01)
    }

    @Test
    fun walkStaysNearbyAndComesBack() {
        val stops = Wander.stops(mall, "Dubai Mall", radius = 500.0, minutes = 60, random = Random(7)) { "Spot $it" }
        assertEquals("Dubai Mall", stops.first().name)
        assertEquals(mall, stops.first().position)
        assertEquals(mall, stops.last().position)
        assertEquals(stops.size, stops.map { it.id }.toSet().size)
        val spots = stops.subList(1, stops.lastIndex)
        assertTrue(spots.size in 2..12)
        for (spot in spots) {
            assertTrue(distanceMeters(mall, spot.position) <= 500.5)
            assertTrue(spot.waitMinutes in 1..5)
        }
        // Consecutive spots aren't on top of each other.
        for (i in 1 until spots.size) assertTrue(distanceMeters(spots[i - 1].position, spots[i].position) >= 500 * 0.35 - 1)
        assertEquals("Spot 1", spots.first().name)
    }

    @Test
    fun longerOrSmallerWalksHaveMoreSpots() {
        val short = Wander.stops(mall, "M", 1000.0, 30, Random(1)) { "$it" }
        val long = Wander.stops(mall, "M", 200.0, 120, Random(1)) { "$it" }
        assertTrue(long.size > short.size)
        assertEquals(12 + 2, Wander.stops(mall, "M", 100.0, 600, Random(1)) { "$it" }.size)
    }
}
