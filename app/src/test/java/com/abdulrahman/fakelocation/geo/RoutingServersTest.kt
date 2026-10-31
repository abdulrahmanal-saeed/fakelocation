package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingServersTest {

    @Test
    fun publicServersWhenNoneIsSet() {
        assertEquals(
            listOf(
                "https://routing.openstreetmap.de/routed-car/route/v1/driving",
                "https://router.project-osrm.org/route/v1/driving",
            ),
            RoutingServers.osrm("", TravelMode.CAR),
        )
        assertEquals(listOf("https://routing.openstreetmap.de/routed-foot/route/v1/foot"), RoutingServers.osrm("", TravelMode.WALK))
        assertEquals(listOf("https://valhalla1.openstreetmap.de/route"), RoutingServers.valhalla(" "))
    }

    @Test
    fun ownServerComesFirst() {
        assertEquals("http://10.0.0.5:5000/route/v1/driving", RoutingServers.osrm("http://10.0.0.5:5000/", TravelMode.CAR).first())
        assertEquals(3, RoutingServers.osrm("osrm.example.com", TravelMode.CAR).size)
        // One OSRM server serves one profile (car), so walking keeps the public foot server.
        assertEquals(1, RoutingServers.osrm("osrm.example.com", TravelMode.WALK).size)
        assertEquals(
            listOf("https://valhalla.example.com/route", "https://valhalla1.openstreetmap.de/route"),
            RoutingServers.valhalla("valhalla.example.com/route"),
        )
        // The public server typed in by hand isn't tried twice.
        assertEquals(1, RoutingServers.valhalla("https://valhalla1.openstreetmap.de").size)
    }

    @Test
    fun typedAddresses() {
        assertEquals("https://osrm.example.com", RoutingServers.normalize("  osrm.example.com/ "))
        assertEquals("http://192.168.1.20:8002", RoutingServers.normalize("http://192.168.1.20:8002/route/"))
        assertEquals("https://example.com/osrm", RoutingServers.normalize("https://example.com/osrm"))
        assertNull(RoutingServers.normalize(""))
        assertNull(RoutingServers.normalize("ftp://example.com"))
        assertNull(RoutingServers.normalize("https://"))
        assertNull(RoutingServers.normalize("https:"))
        assertEquals("https://localhost:5000", RoutingServers.normalize("localhost:5000"))
        assertNull(RoutingServers.normalize("not a server"))
        assertNull(RoutingServers.normalize("https://example.com/route?x=1"))
        assertTrue(RoutingServers.isValid(""))
        assertFalse(RoutingServers.isValid("not a server"))
    }

    @Test
    fun labels() {
        assertEquals("osrm.example.com", RoutingServers.label("https://osrm.example.com/"))
        assertEquals("10.0.0.5:5000", RoutingServers.label("http://10.0.0.5:5000"))
        assertEquals("example.com/osrm", RoutingServers.label("example.com/osrm"))
        assertNull(RoutingServers.label(""))
    }
}
