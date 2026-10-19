package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns text shared from Google Maps (or any maps link / coordinates) into a place.
 * Short links (maps.app.goo.gl) are followed hop by hop until a URL with coordinates shows up.
 */
object MapsLinkResolver {

    suspend fun resolve(sharedText: String): Place? = withContext(Dispatchers.IO) {
        val text = sharedText.trim()
        val name = LocationText.extractName(text)
        LocationText.parseCoordinates(text)?.let { return@withContext Place(it, name) }

        val link = LocationText.extractUrl(text) ?: text.takeIf { it.startsWith("geo:") }
        if (link != null) {
            LocationText.parseMapsUrl(link)?.let { return@withContext Place(it, name ?: LocationText.extractName(link)) }
            if (link.startsWith("http")) {
                followRedirects(link)?.let { return@withContext it.copy(name = it.name ?: name) }
            }
        }
        // Last resort: look the place name up by text.
        name?.let { Nominatim.search(it, null).firstOrNull() }
    }

    private fun followRedirects(start: String): Place? {
        var current = start
        repeat(MAX_HOPS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept-Language", "en")
            }
            try {
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    current = URL(URL(current), location).toString()
                    LocationText.parseMapsUrl(current)?.let { return Place(it, LocationText.extractName(current)) }
                    return@repeat
                }
                if (code == 200) {
                    val page = connection.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(MAX_PAGE_CHARS)
                        val read = reader.read(buffer)
                        if (read > 0) String(buffer, 0, read) else ""
                    }
                    return LocationText.parseMapsUrl(page)?.let { Place(it, LocationText.extractName(current)) }
                }
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private const val MAX_HOPS = 6
    private const val MAX_PAGE_CHARS = 400_000
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
}
