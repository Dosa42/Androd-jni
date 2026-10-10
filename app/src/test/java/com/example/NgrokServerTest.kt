package com.example

import com.example.tunnel.EmbeddedHttpServer
import com.example.tunnel.NgrokTunnelManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NgrokServerTest {

    @Test
    fun testEmbeddedHttpServerPingAndDiagnostics() {
        val freePort = NgrokTunnelManager.findAvailablePort(8090)
        val server = EmbeddedHttpServer(port = freePort)

        try {
            server.start()
            assertTrue("Server should report isRunning=true", server.isRunning)

            // Test /ping endpoint
            val pingUrl = URL("http://127.0.0.1:$freePort/ping")
            val conn = pingUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            val responseCode = conn.responseCode
            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            assertEquals(200, responseCode)
            assertTrue("Body should contain pong", responseBody.contains("\"status\": \"pong\""))
            assertTrue("Body should contain port", responseBody.contains("\"port\": $freePort"))

            // Test /status endpoint
            val statusUrl = URL("http://127.0.0.1:$freePort/status")
            val statusConn = statusUrl.openConnection() as HttpURLConnection
            statusConn.connectTimeout = 3000
            statusConn.requestMethod = "GET"

            assertEquals(200, statusConn.responseCode)
            val statusBody = statusConn.inputStream.bufferedReader().use { it.readText() }
            statusConn.disconnect()
            assertTrue(statusBody.contains("\"status\": \"online\""))

        } finally {
            server.stop()
            assertFalse("Server should report isRunning=false after stop", server.isRunning)
        }
    }

    @Test
    fun testFindAvailablePort() {
        val port = NgrokTunnelManager.findAvailablePort(8085)
        assertTrue("Port must be in valid range", port in 1024..65535)
    }
}
