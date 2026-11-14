package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object WayShiftApi {
    val configured: Boolean get() = BuildConfig.WAYSHIFT_API_URL.startsWith("https://")

    suspend fun request(path: String, body: JSONObject? = null, token: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            check(configured)
            val headers = if (token == null) emptyMap() else mapOf("Authorization" to "Bearer $token")
            val response = Http.request(
                BuildConfig.WAYSHIFT_API_URL.trimEnd('/') + path,
                method = if (body == null) "GET" else "POST",
                body = body?.toString(), contentType = "application/json", headers = headers,
                connectTimeoutMs = 6_000, readTimeoutMs = 9_000, followRedirects = false, maxChars = 200_000,
            )
            JSONObject(response.requireOk())
        }
}
