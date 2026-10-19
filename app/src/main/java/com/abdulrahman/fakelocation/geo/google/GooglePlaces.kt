package com.abdulrahman.fakelocation.geo.google

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import org.json.JSONObject
import java.util.Locale

/** Place search (Places API, Text Search) and address lookup (Geocoding API). */
object GooglePlaces {

    fun search(api: GoogleApi, query: String, near: LatLon?): List<Place> {
        val body = JSONObject().put("textQuery", query).put("languageCode", "en").put("regionCode", "AE").put("pageSize", 10)
        near?.let {
            body.put("locationBias", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", it.lat).put("longitude", it.lon))
                .put("radius", 50_000.0)))
        }
        val json = api.post(
            "https://places.googleapis.com/v1/places:searchText", body,
            "places.displayName,places.formattedAddress,places.location"
        )
        val places = json.optJSONArray("places") ?: return emptyList()
        return (0 until places.length()).map { i ->
            val p = places.getJSONObject(i)
            val location = p.getJSONObject("location")
            Place(
                LatLon(location.getDouble("latitude"), location.getDouble("longitude")),
                p.optJSONObject("displayName")?.optString("text"),
                p.optString("formattedAddress").ifBlank { null },
            )
        }
    }

    fun reverse(api: GoogleApi, position: LatLon): Place? {
        val json = api.get(
            String.format(
                Locale.US, "https://maps.googleapis.com/maps/api/geocode/json?latlng=%.6f,%.6f&language=en&region=ae&key=%s",
                position.lat, position.lon, api.key
            )
        )
        val result = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val components = result.optJSONArray("address_components")
        fun component(type: String): String? {
            if (components == null) return null
            for (i in 0 until components.length()) {
                val c = components.getJSONObject(i)
                val types = c.getJSONArray("types")
                if ((0 until types.length()).any { types.getString(it) == type }) return c.optString("long_name")
            }
            return null
        }
        val name = component("route") ?: component("neighborhood") ?: component("sublocality")
            ?: result.optString("formatted_address").substringBefore(',')
        val area = listOfNotNull(component("sublocality") ?: component("neighborhood"), component("locality"))
            .filter { it != name }.distinct().joinToString(", ")
        return Place(position, name, area.ifBlank { null })
    }
}
