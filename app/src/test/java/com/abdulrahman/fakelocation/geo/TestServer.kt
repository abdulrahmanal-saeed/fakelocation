package com.abdulrahman.fakelocation.geo

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny HTTP/1.1 server on localhost for tests (the JDK's HttpServer isn't on Android's unit-test classpath).
 * Each path has a handler that turns the request into a reply; unknown paths get 404.
 */
class TestServer : AutoCloseable {

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers[name.lowercase()]
    }

    class Reply(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = HashMap<String, (Request) -> Reply>()

    val base = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use(::serve) }
            }
        }
    }

    fun on(path: String, handler: (Request) -> Reply) {
        synchronized(routes) { routes[path] = handler }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = input.readLine() ?: return
        val (method, path) = requestLine.split(" ").let { it[0] to it[1] }
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = String(ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) })
        val handler = synchronized(routes) { routes[path.substringBefore('?')] }
        val reply = handler?.invoke(Request(method, path, headers, body)) ?: Reply(404)
        val bytes = reply.body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 ${reply.code} X\r\n")
            for ((name, value) in reply.headers) append("$name: $value\r\n")
            append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun InputStream.readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    override fun close() = socket.close()
}
