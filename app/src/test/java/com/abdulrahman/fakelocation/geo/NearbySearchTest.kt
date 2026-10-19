package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbySearchTest {
    @Test
    fun readsNodesAndAreasFromOverpass() {
        val json = JSONObject(
            """{"elements":[
              {"type":"node","lat":25.2,"lon":55.27,"tags":{"amenity":"fuel","brand":"ENOC","addr:street":"Sheikh Zayed Rd"}},
              {"type":"way","center":{"lat":25.21,"lon":55.28},"tags":{"amenity":"parking","name":"موقف","name:en":"Mall Parking"}},
              {"type":"node","lat":25.22,"lon":55.29,"tags":{"highway":"bus_stop"}}
            ]}"""
        )
        val places = NearbySearch.parse(json, "Bus stops")
        assertEquals(3, places.size)
        assertEquals("ENOC", places[0].name)
        assertEquals("Sheikh Zayed Rd", places[0].address)
        assertEquals("Mall Parking", places[1].name)
        assertEquals(LatLon(25.21, 55.28), places[1].position)
        assertEquals("Bus stops", places[2].name)
    }
}
