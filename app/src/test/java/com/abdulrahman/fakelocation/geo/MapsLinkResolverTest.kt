package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.geo.TestServer.Reply
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shared text and links that resolve without the internet, plus short links followed through a local server. */
class MapsLinkResolverTest {

    private val server = TestServer().apply {
        // maps.app.goo.gl-style short link: two hops, the second one to a full place URL.
        on("/short") { redirect("/hop") }
        on("/hop") { redirect("/maps/place/Burj+Khalifa/@25.1972,55.2744,17z/data=!3d25.197197!4d55.2743764") }
        // A short link whose last stop is a page with the coordinates inside it.
        on("/page") { Reply(200, """<html><meta content="https://maps.google.com/maps?center=25.0805%2C55.1403&amp;zoom=15"></html>""") }
        on("/nothing") { Reply(200, "<html>no location here</html>") }
        on("/gone") { Reply(404) }
    }
    private val base = server.base

    @After
    fun stop() = server.close()

    @Test
    fun plainCoordinatesAndGeoLinks() = runBlocking {
        val place = MapsLinkResolver.resolve("25.2048, 55.2708")!!
        assertEquals(25.2048, place.position.lat, 1e-9)
        assertEquals(55.2708, place.position.lon, 1e-9)

        val geo = MapsLinkResolver.resolve("geo:25.1,55.2?q=25.1,55.2(Office)")!!
        assertEquals(25.1, geo.position.lat, 1e-9)
    }

    @Test
    fun sharedGoogleMapsTextKeepsTheName() = runBlocking {
        val place = MapsLinkResolver.resolve("Dubai Mall\nhttps://www.google.com/maps/place/Dubai+Mall/@25.1985,55.2796,17z")!!
        assertEquals("Dubai Mall", place.name)
        assertEquals(25.1985, place.position.lat, 1e-9)
    }

    @Test
    fun shortLinkIsFollowedToThePlacePin() = runBlocking {
        val place = MapsLinkResolver.resolve("$base/short")!!
        assertEquals(25.197197, place.position.lat, 1e-9) // the pin (!3d!4d), not the viewport (@)
        assertEquals(55.2743764, place.position.lon, 1e-9)
        assertEquals("Burj Khalifa", place.name)
    }

    @Test
    fun coordinatesAreReadFromTheFinalPage() = runBlocking {
        val place = MapsLinkResolver.resolve("$base/page")!!
        assertEquals(25.0805, place.position.lat, 1e-9)
        assertEquals(55.1403, place.position.lon, 1e-9)
    }

    @Test
    fun linkWithoutALocation() = runBlocking {
        assertNull(MapsLinkResolver.resolve("$base/nothing"))
        assertNull(MapsLinkResolver.resolve("$base/gone"))
    }

    private fun redirect(to: String) = Reply(302, headers = mapOf("Location" to to))
}
