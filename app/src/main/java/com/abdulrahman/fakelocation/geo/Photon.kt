package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import org.json.JSONObject
import java.net.URLEncoder

internal object Photon {
    fun search(endpoint: String, query: String, near: LatLon?, locale: MapLocale): List<Place> {
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" }.orEmpty()
        return fetch(endpoint, "api/?q=${URLEncoder.encode(query, "UTF-8")}&limit=10$bias", locale)
    }

    fun reverse(endpoint: String, point: LatLon, locale: MapLocale): Place? =
        fetch(endpoint, "reverse?lat=${point.lat}&lon=${point.lon}&limit=1", locale)
            .firstOrNull()?.copy(position = point)

    private fun fetch(endpoint: String, query: String, locale: MapLocale): List<Place> {
        val url = "${endpoint.trimEnd('/')}/$query"
        val json = JSONObject(Http.get(url, headers = mapOf("Accept-Language" to locale.language)))
        val features = json.optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val feature = features.optJSONObject(i) ?: return@mapNotNull null
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val properties = feature.optJSONObject("properties") ?: return@mapNotNull null
            val lon = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val name = listOf("name", "street", "city").firstNotNullOfOrNull { properties.optString(it).ifBlank { null } }
            val address = listOf("street", "city", "state", "country")
                .map { properties.optString(it) }.filter { it.isNotBlank() && it != name }.distinct()
            Place(LatLon(lat, lon), name, address.joinToString(", ").ifBlank { null })
        }
    }
}
