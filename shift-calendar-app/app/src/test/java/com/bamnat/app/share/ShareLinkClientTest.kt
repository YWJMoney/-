package com.bamnat.app.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** [SHR-02] Whatever answers at the share address, reading a link fails politely instead of crashing the app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShareLinkClientTest {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    @Volatile private var status = 200
    @Volatile private var body = ""
    private val id = "Ab3dEfGh5JkLmNoPqRsTuv"

    /** Answers every request with [status] and [body], one connection at a time. */
    @Before fun start() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                // The app may hang up halfway through a reply that is too long.
                runCatching {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val bytes = body.toByteArray()
                        s.getOutputStream().apply {
                            write("HTTP/1.1 $status X\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }
    }

    @After fun stop() = server.close()

    private val client get() = ShareLinkClient("http://127.0.0.1:${server.localPort}")

    /** The [ShareLinkException] reading fails with; any other exception fails the test, as it would crash the app. */
    private fun failure(status: Int, body: String): String? {
        this.status = status
        this.body = body
        return runBlocking { runCatching { client.read(id) }.exceptionOrNull() }?.let { (it as ShareLinkException).message }
    }

    @Test fun aPageThatIsNotTheServer() {
        assertEquals("bad_response", failure(200, "<html><body>와이파이 로그인</body></html>"))
        assertEquals("bad_response", failure(200, "{}"))
        assertEquals("bad_response", failure(200, """{"schedule":{"v":1},"expiresAt":"곧"}"""))
        assertEquals("too_large", failure(200, "x".repeat(300 * 1024)))
        assertEquals("http_502", failure(502, "<html>Bad gateway</html>"))
    }

    @Test fun theServersOwnAnswers() {
        assertEquals("not_found", failure(404, """{"error":"not_found"}"""))
        assertEquals(null, failure(200, """{"schedule":{"v":1,"title":"민지","types":[],"days":[]},"expiresAt":"2026-12-01T00:00:00Z","updatedAt":null}"""))
        assertEquals("민지", runBlocking { client.read(id) }.schedule.getString("title"))
    }
}
