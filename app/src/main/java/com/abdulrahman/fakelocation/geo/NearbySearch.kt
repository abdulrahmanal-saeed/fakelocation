package com.abdulrahman.fakelocation.geo

import android.content.Context
import android.util.Log
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.geo.google.GoogleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Quick "what's near" categories in search. */
enum class PlaceCategory(
    val label: Int,
    val icon: Int,
    val googleTypes: List<String>,
    /** Overpass filters, each becomes `nwr<filter>(around:…)`. */
    val osmFilters: List<String>,
    val radiusMeters: Int,
) {
    FUEL(R.string.cat_fuel, R.drawable.ic_cat_fuel, listOf("gas_station"), listOf("[amenity=fuel]"), 5000),
    PARKING(R.string.cat_parking, R.drawable.ic_cat_parking, listOf("parking"), listOf("[amenity=parking]"), 2000),
    BUS(R.string.cat_bus, R.drawable.ic_cat_bus, listOf("bus_stop", "bus_station"), listOf("[highway=bus_stop]", "[amenity=bus_station]"), 1500),
    METRO(R.string.cat_metro, R.drawable.ic_cat_metro, listOf("subway_station", "light_rail_station"), listOf("[railway=station][station=subway]", "[railway=station][station=light_rail]"), 6000),
    EV(R.string.cat_ev, R.drawable.ic_cat_ev, listOf("electric_vehicle_charging_station"), listOf("[amenity=charging_station]"), 6000),
}

/** Nearby places of one category: Google Places when Google search is on, otherwise OpenStreetMap. Nearest first. */
object NearbySearch {

    suspend fun find(context: Context, category: PlaceCategory, center: LatLon): List<Place> = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val places = if (prefs.useGoogleSearch && prefs.hasGoogleKey) {
            runCatching { google(GoogleApi(context), category, center) }
                .onFailure { Log.w("NearbySearch", "Google nearby failed, using OpenStreetMap", it) }
                .getOrNull()
        } else {
            null
        } ?: osm(category, center, context.getString(category.label))
        places.sortedBy { distanceMeters(center, it.position) }.take(25)
    }

    private fun google(api: GoogleApi, category: PlaceCategory, center: LatLon): List<Place> {
        val body = JSONObject()
            .put("includedTypes", JSONArray(category.googleTypes))
            .put("maxResultCount", 20)
            .put("rankPreference", "DISTANCE")
            .put("languageCode", "en")
            .put("regionCode", "AE")
            .put("locationRestriction", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", center.lat).put("longitude", center.lon))
                .put("radius", category.radiusMeters.toDouble())))
        val json = api.post(
            "https://places.googleapis.com/v1/places:searchNearby", body,
            "places.displayName,places.formattedAddress,places.location"
        )
        val arr = json.optJSONArray("places") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val loc = p.getJSONObject("location")
            Place(LatLon(loc.getDouble("latitude"), loc.getDouble("longitude")), p.optJSONObject("displayName")?.optString("text"), p.optString("formattedAddress").ifBlank { null })
        }
    }

    private fun osm(category: PlaceCategory, center: LatLon, fallbackName: String): List<Place> {
        val around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", category.radiusMeters, center.lat, center.lon)
        val query = "[out:json][timeout:20];(" + category.osmFilters.joinToString("") { "nwr$it$around;" } + ");out center tags 80;"
        return parse(Overpass.query(query), fallbackName)
    }

    fun parse(json: JSONObject, fallbackName: String): List<Place> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        return (0 until elements.length()).mapNotNull { i ->
            val e = elements.getJSONObject(i)
            val (lat, lon) = when {
                e.has("lat") -> e.getDouble("lat") to e.getDouble("lon")
                e.has("center") -> e.getJSONObject("center").let { it.getDouble("lat") to it.getDouble("lon") }
                else -> return@mapNotNull null
            }
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val name = listOf("name:en", "name", "brand", "operator").firstNotNullOfOrNull { tags.optString(it).ifBlank { null } } ?: fallbackName
            val address = listOfNotNull(tags.optString("addr:street").ifBlank { null }, tags.optString("addr:city").ifBlank { null })
                .joinToString(", ").ifBlank { null }
            Place(LatLon(lat, lon), name, address)
        }
    }
}
