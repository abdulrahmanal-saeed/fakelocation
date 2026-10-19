package com.abdulrahman.fakelocation.geo

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** OpenStreetMap's query service (Overpass). Public servers can be busy, so several are tried in turn. */
object Overpass {
    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** Blocking: call from a background thread. */
    fun query(query: String): JSONObject {
        var lastError: Exception? = null
        for (server in servers) {
            try {
                return post(server, query)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No Overpass server answered")
    }

    private fun post(server: String, query: String): JSONObject {
        val connection = URL(server).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 40_000
            connection.setRequestProperty("User-Agent", Nominatim.USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (connection.responseCode !in 200..299) throw IOException("Overpass HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
