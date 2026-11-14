package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Free place search and reverse lookup using OpenStreetMap's Nominatim service. */
internal object Nominatim {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var lastRequestAt = 0L
    private const val BASE = "https://nominatim.openstreetmap.org"

    suspend fun search(query: String, near: LatLon?, locale: MapLocale = MapLocale(Locale.getDefault().language.ifBlank { "en" }, null)): List<Place> = withContext(Dispatchers.IO) {
        val bias = near?.let {
            String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", it.lon - 0.5, it.lat + 0.5, it.lon + 0.5, it.lat - 0.5)
        }.orEmpty()
        val body = get("$BASE/search?format=jsonv2&limit=8&addressdetails=1&accept-language=${locale.language}$bias&q=" + URLEncoder.encode(query, "UTF-8"))
        val results = JSONArray(body)
        (0 until results.length()).map { i -> toPlace(results.getJSONObject(i)) }
    }

    /** Short name and address for a point, or null when nothing is known there. */
    suspend fun reverse(position: LatLon, locale: MapLocale): Place? = withContext(Dispatchers.IO) {
        val body = get(String.format(Locale.US, "$BASE/reverse?format=jsonv2&zoom=18&addressdetails=1&accept-language=${locale.language}&lat=%.6f&lon=%.6f", position.lat, position.lon))
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

    private suspend fun get(url: String): String = mutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        delay((lastRequestAt + 1_100L - now).coerceAtLeast(0L))
        lastRequestAt = android.os.SystemClock.elapsedRealtime()
        Http.get(url, connectTimeoutMs = 10_000, readTimeoutMs = 10_000)
    }
}
