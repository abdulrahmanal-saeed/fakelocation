package com.abdulrahman.fakelocation.geo

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** The phone has no working internet connection (the server's name can't even be looked up). */
class NoInternetException(cause: Throwable) : IOException("No internet connection", cause)

/** The server answered with a non-2xx status. [body] is whatever it sent back (often its own error text). */
class HttpException(val code: Int, val body: String) :
    IOException("HTTP $code" + body.trim().take(200).let { if (it.isEmpty()) "" else ": $it" })

/**
 * The one place the app talks HTTP. Every call carries the app's User-Agent and timeouts. A call that couldn't
 * connect (so nothing was sent yet) is tried once more, and no network at all comes back as [NoInternetException].
 * Blocking: call from a background thread.
 */
object Http {
    const val USER_AGENT = "FakeLocation-personal-app/0.2"

    class Response(val code: Int, val body: String, private val headers: Map<String?, List<String>>) {
        val ok get() = code in 200..299

        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        /** The body of a 2xx answer, otherwise [HttpException]. */
        fun requireOk(): String = if (ok) body else throw HttpException(code, body)
    }

    fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        followRedirects: Boolean = true,
        maxChars: Int = Int.MAX_VALUE,
    ): Response {
        var retried = false
        while (true) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = followRedirects
                connection.setRequestProperty("User-Agent", USER_AGENT)
                for ((name, value) in headers) connection.setRequestProperty(name, value)
                if (body != null) {
                    connection.doOutput = true
                    contentType?.let { connection.setRequestProperty("Content-Type", it) }
                }
                try {
                    connection.connect()
                } catch (e: UnknownHostException) {
                    throw NoInternetException(e)
                } catch (e: IOException) {
                    val canRetry = e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException
                    if (canRetry && !retried) {
                        retried = true
                        continue
                    }
                    throw e
                }
                if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                return Response(code, stream?.readUpTo(maxChars).orEmpty(), connection.headerFields)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** GET that returns the body of a 2xx answer and throws [IOException] for anything else. */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
    ): String = request(url, headers = headers, connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs).requireOk()

    private fun InputStream.readUpTo(maxChars: Int): String = bufferedReader().use { reader ->
        if (maxChars == Int.MAX_VALUE) return@use reader.readText()
        val buffer = CharArray(maxChars)
        var total = 0
        while (total < maxChars) {
            val read = reader.read(buffer, total, maxChars - total)
            if (read < 0) break
            total += read
        }
        String(buffer, 0, total)
    }
}
