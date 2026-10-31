package com.abdulrahman.fakelocation.drive

import com.abdulrahman.fakelocation.data.LatLon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingTest {

    private val parking = Parking("365A", "Dubai A 12345", LatLon(25.2048, 55.2708), "Al Wasl Rd", 1_000L, 3_601_000L)

    @Test
    fun jsonRoundTrip() {
        assertEquals(parking, Parking.fromJson(parking.toJson()))
    }

    @Test
    fun jsonWithoutPositionOrPlace() {
        val bare = parking.copy(position = null, placeName = null)
        assertEquals(bare, Parking.fromJson(bare.toJson()))
    }

    @Test
    fun unreadableJsonIsNull() {
        assertNull(Parking.fromJson("not json"))
        assertNull(Parking.fromJson("""{"plate":"A 1"}""")) // no zone or times
    }

    @Test
    fun smsBodyForDubai() {
        assertEquals("A12345 365A 2", Parking.smsBody("a12345", "365a", 2))
        assertEquals("7275", Parking.DUBAI_SMS_NUMBER)
    }

    @Test
    fun zoneCodes() {
        for (code in listOf("365A", "365a", " 312C ", "21", "1234", "100AB")) assertTrue(code, Parking.isZoneCode(code))
        for (code in listOf("", "A", "A365", "12345", "365ABC", "36 5A", "zone")) assertFalse(code, Parking.isZoneCode(code))
    }

    @Test
    fun zonesFromOpenStreetMap() {
        val json = JSONObject(
            """
            {"elements":[
              {"tags":{"amenity":"parking","parking:zone":"365a"}},
              {"tags":{"amenity":"parking","ref":"P3"}},
              {"tags":{"amenity":"parking","zone":"312C","ref":"365A"}},
              {"tags":{"amenity":"parking","name":"Mall parking"}},
              {"type":"node"}
            ]}
            """.trimIndent()
        )
        // Upper-cased, only real zone codes, no duplicates, in the order found.
        assertEquals(listOf("365A", "312C"), ParkingZones.parse(json))
        assertEquals(emptyList<String>(), ParkingZones.parse(JSONObject("{}")))
    }
}
