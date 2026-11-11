package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FasterRouteTest {

    // Around Dubai: 0.001° of longitude is about 100 m, 0.001° of latitude about 110 m.
    private val lat = 25.2
    private val lon = 55.3

    private fun route(seconds: Double, vararg points: LatLon) = NavRoute(points.toList(), emptyList(), 0.0, seconds)

    /** 10 km straight east, a point every 100 m. */
    private fun highway(seconds: Double, fromKm: Double = 0.0) =
        route(seconds, *((fromKm * 10).toInt()..100).map { LatLon(lat, lon + it * 0.001) }.toTypedArray())

    /** Same ends as [highway], but along a road 1.1 km to the north. */
    private fun backRoad(seconds: Double, fromKm: Double = 0.0): NavRoute {
        val start = (fromKm * 10).toInt()
        val points = listOf(LatLon(lat, lon + start * 0.001)) +
            (start..100).map { LatLon(lat + 0.01, lon + it * 0.001) } +
            LatLon(lat, lon + 0.1)
        return route(seconds, *points.toTypedArray())
    }

    @Test
    fun sameRoadIsFullyShared() {
        assertEquals(1.0, FasterRoute.sharedFraction(highway(600.0), highway(700.0)), 0.01)
        // A fresh route from 4 km in still lies on the road being driven.
        assertEquals(1.0, FasterRoute.sharedFraction(highway(400.0, fromKm = 4.0), highway(700.0), fromAlong = 4000.0), 0.01)
    }

    @Test
    fun differentRoadIsBarelyShared() {
        assertTrue(FasterRoute.sharedFraction(backRoad(600.0), highway(600.0)) < 0.15)
    }

    @Test
    fun roadAlreadyDrivenDoesNotCount() {
        // The first 4 km of the highway are behind us: a route along them isn't "the same way" any more.
        val firstPart = route(200.0, *(0..30).map { LatLon(lat, lon + it * 0.001) }.toTypedArray())
        assertTrue(FasterRoute.sharedFraction(firstPart, highway(600.0), fromAlong = 4000.0) < 0.1)
    }

    @Test
    fun offersAClearlyQuickerWay() {
        val current = highway(900.0)
        // Traffic built up: the highway now takes 20 min from here, the back road 12.
        val offer = FasterRoute.pick(current, 0.0, 900.0, listOf(backRoad(720.0), highway(1200.0)))
        assertNotNull(offer)
        assertEquals(480.0, offer!!.savesSeconds, 0.1)
        assertEquals(720.0, offer.route.durationSeconds, 0.1)
    }

    @Test
    fun staysQuietWhenOurRoadIsStillBest() {
        val current = highway(900.0)
        assertNull(FasterRoute.pick(current, 0.0, 900.0, listOf(highway(880.0), backRoad(1000.0))))
        // Only our own road came back: nothing to compare with.
        assertNull(FasterRoute.pick(current, 0.0, 900.0, listOf(highway(880.0))))
        assertNull(FasterRoute.pick(current, 0.0, 900.0, emptyList()))
    }

    @Test
    fun smallSavingsAreNotWorthIt() {
        val current = highway(900.0)
        // One minute quicker: under the two-minute minimum.
        assertNull(FasterRoute.pick(current, 0.0, 900.0, listOf(highway(900.0), backRoad(840.0))))
        // Three minutes on a two-hour drive: under 7 % of the time left.
        assertNull(FasterRoute.pick(current, 0.0, 7200.0, listOf(highway(7200.0), backRoad(7020.0))))
    }

    @Test
    fun usesOurEstimateWhenOurRoadIsNotAmongTheFreshOnes() {
        val current = highway(900.0)
        val offer = FasterRoute.pick(current, 0.0, 900.0, listOf(backRoad(700.0)))
        assertEquals(200.0, offer!!.savesSeconds, 0.1)
    }

    @Test
    fun aDeclinedSavingIsNotOfferedAgain() {
        val current = highway(900.0)
        val fresh = listOf(highway(1200.0), backRoad(720.0)) // saves 8 min
        assertNull(FasterRoute.pick(current, 0.0, 900.0, fresh, alreadyDeclined = 480.0))
        // …until it grows by a good margin.
        assertNotNull(FasterRoute.pick(current, 0.0, 900.0, listOf(highway(1500.0), backRoad(720.0)), alreadyDeclined = 480.0))
    }

    @Test
    fun comparesFromWhereTheCarIs() {
        val current = highway(900.0)
        // 4 km in: fresh routes start here. The rest of the highway is jammed, the back road is clear.
        val offer = FasterRoute.pick(current, 4000.0, 540.0, listOf(backRoad(500.0, fromKm = 4.0), highway(900.0, fromKm = 4.0)))
        assertEquals(400.0, offer!!.savesSeconds, 0.1)
    }

    @Test
    fun nearDuplicateAlternativesAreDropped() {
        val a = highway(600.0)
        val kept = NavRouter.distinct(listOf(a, highway(610.0), backRoad(700.0), backRoad(5000.0)))
        assertEquals(2, kept.size)
        assertTrue(kept[0] === a)
        assertEquals(700.0, kept[1].durationSeconds, 0.1)
    }

    @Test
    fun atMostThreeWaysAreOffered() {
        fun road(n: Int) = route(600.0 + n, LatLon(lat, lon), LatLon(lat + 0.01 * n, lon + 0.05), LatLon(lat, lon + 0.1))
        assertEquals(NavRouter.MAX_OPTIONS, NavRouter.distinct((1..5).map(::road)).size)
    }
}
