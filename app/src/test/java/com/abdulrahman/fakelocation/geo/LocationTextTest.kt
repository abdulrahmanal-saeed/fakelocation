package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationTextTest {

    @Test
    fun plainCoordinates() {
        assertEquals(LatLon(30.0444, 31.2357), LocationText.parseCoordinates("30.0444, 31.2357"))
        assertEquals(LatLon(30.0444, 31.2357), LocationText.parseCoordinates(" 30.0444 31.2357 "))
        assertEquals(LatLon(-33.86, 151.2), LocationText.parseCoordinates("geo:-33.86,151.2"))
        assertNull(LocationText.parseCoordinates("Cairo Tower"))
        assertNull(LocationText.parseCoordinates("120.0, 31.0"))
    }

    @Test
    fun placeUrlPrefersPinOverViewport() {
        val url = "https://www.google.com/maps/place/Cairo+Tower/@30.0459,31.2218,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d30.0459751!4d31.2242983!16s"
        assertEquals(LatLon(30.0459751, 31.2242983), LocationText.parseMapsUrl(url))
        assertEquals("Cairo Tower", LocationText.extractName(url))
    }

    @Test
    fun queryAndViewportUrls() {
        assertEquals(LatLon(30.05, 31.24), LocationText.parseMapsUrl("https://maps.google.com/?q=30.05,31.24"))
        assertEquals(LatLon(30.05, 31.24), LocationText.parseMapsUrl("https://www.google.com/maps/search/?api=1&query=30.05%2C31.24"))
        assertEquals(LatLon(29.97, 31.13), LocationText.parseMapsUrl("https://www.google.com/maps/@29.97,31.13,15z"))
        assertEquals(LatLon(30.1, 31.3), LocationText.parseMapsUrl("geo:30.1,31.3?q=30.1,31.3(Home)"))
    }

    @Test
    fun consentRedirectWrapsTheRealUrl() {
        val url = "https://consent.google.com/m?continue=https://www.google.com/maps/place/X/%4030.01,31.02,17z/data%3D!3d30.011!4d31.022&gl=EG"
        assertEquals(LatLon(30.011, 31.022), LocationText.parseMapsUrl(url))
    }

    @Test
    fun sharedTextFromGoogleMaps() {
        val shared = "Cairo Tower\nZamalek, Cairo Governorate\nhttps://maps.app.goo.gl/AbCdEf123."
        assertEquals("https://maps.app.goo.gl/AbCdEf123", LocationText.extractUrl(shared))
        assertEquals("Cairo Tower", LocationText.extractName(shared))
        assertNull(LocationText.parseMapsUrl("https://maps.app.goo.gl/AbCdEf123"))
    }
}
