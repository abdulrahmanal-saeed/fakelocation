package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.geo.TestServer.Reply
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Runs [Http] against a small local server, so no internet is needed. */
class HttpTest {

    private val server = TestServer().apply {
        on("/ok") { Reply(200, "hello") }
        on("/echo") { "${it.method} ${it.header("User-Agent")} ${it.header("Content-Type")} ${it.body}".let { text -> Reply(200, text) } }
        on("/missing") { Reply(404, """{"message":"no route"}""") }
        on("/empty-error") { Reply(502) }
        on("/moved") { Reply(302, headers = mapOf("Location" to "/ok")) }
        on("/big") { Reply(200, "x".repeat(10_000)) }
    }
    private val base = server.base

    @After
    fun stop() = server.close()

    @Test
    fun getReturnsBodyAndSendsUserAgent() {
        assertEquals("hello", Http.get("$base/ok"))
        assertEquals("GET ${Http.USER_AGENT} null ", Http.get("$base/echo"))
    }

    @Test
    fun postSendsBodyAndContentType() {
        val response = Http.request("$base/echo", "POST", body = "data=1", contentType = "application/x-www-form-urlencoded")
        assertEquals("POST ${Http.USER_AGENT} application/x-www-form-urlencoded data=1", response.body)
    }

    @Test
    fun headersOverrideTheUserAgent() {
        val response = Http.request("$base/echo", headers = mapOf("User-Agent" to "Browser"))
        assertTrue(response.body, response.body.startsWith("GET Browser "))
    }

    @Test
    fun errorBodyIsKeptAndRequireOkThrows() {
        val response = Http.request("$base/missing")
        assertEquals(404, response.code)
        assertEquals("""{"message":"no route"}""", response.body)
        try {
            response.requireOk()
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(404, e.code)
            assertTrue(e.message!!.contains("no route"))
        }
        try {
            Http.get("$base/empty-error")
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals("HTTP 502", e.message)
        }
    }

    @Test
    fun redirectsCanBeLeftUnfollowed() {
        assertEquals("hello", Http.get("$base/moved"))
        val response = Http.request("$base/moved", followRedirects = false)
        assertEquals(302, response.code)
        assertEquals("/ok", response.header("location"))
        assertNull(response.header("X-Nothing"))
    }

    @Test
    fun bodyIsCutAtMaxChars() {
        assertEquals(1234, Http.request("$base/big", maxChars = 1234).body.length)
        assertEquals(10_000, Http.request("$base/big").body.length)
    }

    @Test(expected = NoInternetException::class)
    fun unknownHostMeansNoInternet() {
        Http.get("http://no-such-host.invalid/")
    }

    @Test(expected = ConnectException::class)
    fun refusedConnectionIsThrownAfterTheRetry() {
        val port = ServerSocket(0).use { it.localPort } // a free port: nothing listens there
        Http.get("http://127.0.0.1:$port/")
    }

    @Test
    fun anAnswerIsNeverRetried() {
        val hits = AtomicInteger()
        server.on("/boom") { hits.incrementAndGet(); Reply(500, "boom") }
        assertEquals(500, Http.request("$base/boom").code)
        assertEquals(1, hits.get())
    }
}
