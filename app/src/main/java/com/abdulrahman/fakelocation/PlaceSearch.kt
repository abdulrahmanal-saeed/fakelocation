package com.abdulrahman.fakelocation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Place(val name: String, val position: LatLon)

/** Free place search using OpenStreetMap's Nominatim service. */
object PlaceSearch {
    suspend fun search(query: String, userAgent: String): List<Place> = withContext(Dispatchers.IO) {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=json&limit=5&q=" +
                URLEncoder.encode(query, "UTF-8")
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", userAgent)
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(body)
            (0 until results.length()).map { i ->
                val item = results.getJSONObject(i)
                Place(
                    item.getString("display_name"),
                    LatLon(item.getString("lat").toDouble(), item.getString("lon").toDouble())
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
