package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Free place search and reverse lookup using OpenStreetMap's Nominatim service. */
object Nominatim {
    private const val BASE = "https://nominatim.openstreetmap.org"
    const val USER_AGENT = "FakeLocation-personal-app/0.2"

    suspend fun search(query: String, near: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        val bias = near?.let {
            String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", it.lon - 0.5, it.lat + 0.5, it.lon + 0.5, it.lat - 0.5)
        }.orEmpty()
        val body = get("$BASE/search?format=jsonv2&limit=8&addressdetails=1&accept-language=en$bias&q=" + URLEncoder.encode(query, "UTF-8"))
        val results = JSONArray(body)
        (0 until results.length()).map { i -> toPlace(results.getJSONObject(i)) }
    }

    /** Short name and address for a point, or null when nothing is known there. */
    suspend fun reverse(position: LatLon): Place? = withContext(Dispatchers.IO) {
        val body = get(String.format(Locale.US, "$BASE/reverse?format=jsonv2&zoom=18&addressdetails=1&accept-language=en&lat=%.6f&lon=%.6f", position.lat, position.lon))
        val json = JSONObject(body)
        if (json.has("error")) null else toPlace(json).copy(position = position)
    }

    private fun toPlace(item: JSONObject): Place {
        val address = item.optJSONObject("address")
        val name = item.optString("name").takeIf { it.isNotBlank() }
            ?: address?.let { a -> listOf("road", "neighbourhood", "suburb").firstNotNullOfOrNull { a.optString(it).takeIf(String::isNotBlank) } }
            ?: item.optString("display_name").substringBefore(',')
        val area = address?.let { a ->
            listOf("suburb", "neighbourhood", "city_district", "city", "town", "village", "state")
                .mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
                .filter { it != name }
                .distinct()
                .take(2)
                .joinToString(", ")
        }.orEmpty().ifBlank { item.optString("display_name").substringAfter(", ", "") }
        return Place(LatLon(item.getString("lat").toDouble(), item.getString("lon").toDouble()), name, area.ifBlank { null })
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
