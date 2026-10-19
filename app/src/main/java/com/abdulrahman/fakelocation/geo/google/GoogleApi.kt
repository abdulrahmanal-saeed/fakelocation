package com.abdulrahman.fakelocation.geo.google

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.abdulrahman.fakelocation.data.Prefs
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A Google API call failed. [message] is Google's own explanation (e.g. "API not enabled"). */
class GoogleApiException(val code: Int, message: String) : IOException(message)

/**
 * Small HTTP helper for the Google Maps Platform web APIs. Sends the user's key plus this app's package name
 * and signing certificate, so the key can be locked to this app in the Google Cloud console.
 */
class GoogleApi(private val context: Context) {

    val key: String get() = Prefs(context).googleApiKey

    fun post(url: String, body: JSONObject, fieldMask: String? = null): JSONObject =
        request(url, "POST", body.toString(), fieldMask)

    fun get(url: String): JSONObject = request(url, "GET", null, null)

    private fun request(url: String, method: String, body: String?, fieldMask: String?): JSONObject {
        if (key.isBlank()) throw GoogleApiException(0, "No Google API key")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("X-Goog-Api-Key", key)
            for ((name, value) in appHeaders(context)) connection.setRequestProperty(name, value)
            fieldMask?.let { connection.setRequestProperty("X-Goog-FieldMask", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                throw GoogleApiException(code, json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            }
            // The older web services (Geocoding) answer 200 with a status field.
            val status = json.optString("status")
            if (status.isNotEmpty() && status != "OK" && status != "ZERO_RESULTS") {
                throw GoogleApiException(code, json.optString("error_message").ifBlank { status })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Package name + SHA-1 of the signing certificate, the headers Google checks for Android-restricted keys. */
        fun appHeaders(context: Context): Map<String, String> =
            mapOf("X-Android-Package" to context.packageName, "X-Android-Cert" to signatureSha1(context))

        @SuppressLint("PackageManagerGetSignatures")
        fun signatureSha1(context: Context): String {
            val pm = context.packageManager
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
            } ?: return ""
            return MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }

        /** "123s" or "12.5s" → seconds. */
        fun seconds(duration: String?): Double = duration?.removeSuffix("s")?.toDoubleOrNull() ?: 0.0
    }
}
