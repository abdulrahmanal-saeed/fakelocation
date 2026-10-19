package com.abdulrahman.fakelocation.drive

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.geo.Overpass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** Parking zone codes mapped in OpenStreetMap near a spot (only some car parks have them). */
object ParkingZones {

    suspend fun near(position: LatLon): List<String> = withContext(Dispatchers.IO) {
        val q = String.format(
            Locale.US,
            "[out:json][timeout:15];nwr(around:250,%.6f,%.6f)[amenity=parking];out tags 40;", position.lat, position.lon,
        )
        parse(Overpass.query(q))
    }

    fun parse(json: JSONObject): List<String> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        val zones = LinkedHashSet<String>()
        for (i in 0 until elements.length()) {
            val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
            for (key in listOf("parking:zone", "zone", "ref", "parking:ref")) {
                val value = tags.optString(key).trim().uppercase(Locale.US)
                if (Parking.isZoneCode(value)) zones += value
            }
        }
        return zones.toList()
    }
}
