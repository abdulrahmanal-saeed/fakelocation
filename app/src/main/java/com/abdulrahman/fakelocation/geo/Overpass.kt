package com.abdulrahman.fakelocation.geo

import android.util.Log
import org.json.JSONObject
import java.io.IOException
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
            } catch (e: NoInternetException) {
                throw e // every other server would fail the same way
            } catch (e: Exception) {
                Log.w("Overpass", "Overpass server failed: $server", e)
                lastError = e
            }
        }
        throw lastError ?: IOException("No Overpass server answered")
    }

    private fun post(server: String, query: String): JSONObject = JSONObject(
        Http.request(
            server, "POST",
            body = "data=" + URLEncoder.encode(query, "UTF-8"),
            contentType = "application/x-www-form-urlencoded",
            readTimeoutMs = 40_000,
        ).requireOk()
    )
}
