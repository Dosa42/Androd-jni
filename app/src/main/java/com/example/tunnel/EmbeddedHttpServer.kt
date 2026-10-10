package com.example.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmbeddedHttpServer(
  val port: Int = 8085,
  private val context: Context? = null,
  private val onRequestHandled: (TrafficLogEntry) -> Unit = {}
) {
  private val tag = "EmbeddedHttpServer"
  private var serverSocket: ServerSocket? = null
  private var serverJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO)
  private val startTime = System.currentTimeMillis()

  @Volatile
  var isRunning = false
    private set

  fun start() {
    if (isRunning) return
    try {
      serverSocket = ServerSocket(port).apply {
        reuseAddress = true
      }
      isRunning = true
      Log.i(tag, "Embedded HTTP Server listening on port $port")

      serverJob = scope.launch {
        while (isActive && isRunning) {
          try {
            val clientSocket = serverSocket?.accept() ?: break
            launch(Dispatchers.IO) {
              handleClient(clientSocket)
            }
          } catch (e: Exception) {
            if (isRunning) {
              Log.w(tag, "Server accept exception: ${e.message}")
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Failed to start server on port $port: ${e.message}", e)
      isRunning = false
      throw e
    }
  }

  private fun handleClient(socket: Socket) {
    val startMs = System.currentTimeMillis()
    val clientIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"
    var method = "GET"
    var path = "/"
    var statusCode = 200
    val headers = mutableMapOf<String, String>()
    var body = ""

    try {
      socket.use { s ->
        s.soTimeout = 15000
        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
        val output = s.getOutputStream()

        val requestLine = reader.readLine()
        if (requestLine.isNullOrBlank()) {
          return
        }

        val parts = requestLine.split(" ")
        if (parts.size >= 2) {
          method = parts[0].uppercase(Locale.US)
          path = parts[1]
        }

        // Read headers
        var contentLength = 0
        var line: String?
        while (reader.readLine().also { line = it } != null) {
          if (line.isNullOrBlank()) break
          val colonIdx = line!!.indexOf(':')
          if (colonIdx > 0) {
            val key = line!!.substring(0, colonIdx).trim()
            val value = line!!.substring(colonIdx + 1).trim()
            headers[key] = value
            if (key.equals("content-length", ignoreCase = true)) {
              contentLength = value.toIntOrNull() ?: 0
            }
          }
        }

        // Read body if payload is present
        if (contentLength > 0 && contentLength < 1000000) {
          val charBuf = CharArray(contentLength)
          var readTotal = 0
          while (readTotal < contentLength) {
            val r = reader.read(charBuf, readTotal, contentLength - readTotal)
            if (r == -1) break
            readTotal += r
          }
          body = String(charBuf, 0, readTotal)
        }

        // Handle CORS Preflight
        if (method == "OPTIONS") {
          sendCorsPreflight(output)
          statusCode = 204
          return
        }

        val cleanPath = path.substringBefore("?")
        val hostHeader = headers["Host"] ?: headers["host"] ?: "127.0.0.1:$port"
        val isSecure = headers["X-Forwarded-Proto"] == "https" || hostHeader.contains("ngrok")
        val effectiveBaseUrl = (if (isSecure) "https://" else "http://") + hostHeader

        // Route handling
        when {
          // 1. Raw 1-Click Userscript Distribution
          cleanPath == "/Proxy-Redirect.user.js" ||
          cleanPath == "/userscript/Proxy-Redirect.user.js" ||
          cleanPath == "/userscript/ngrok-agent-bridge.user.js" -> {
            statusCode = 200
            val dynamicScript = UserscriptSource.SCRIPT_CONTENT
              .replace("http://127.0.0.1:8085/Proxy-Redirect.user.js", "$effectiveBaseUrl/Proxy-Redirect.user.js")
            sendScriptResponse(output, dynamicScript)
          }

          // 2. Userscript Handshake
          cleanPath == "/api/bridge/handshake" && method == "POST" -> {
            var scriptVersion = "26.10.10"
            var browser = "Tampermonkey Browser"
            var pageUrl = ""
            var pageTitle = ""

            try {
              if (body.isNotBlank()) {
                val json = JSONObject(body)
                scriptVersion = json.optString("script_version", "26.10.10")
                browser = json.optString("browser", "Tampermonkey Browser")
                pageUrl = json.optString("page_url", "")
                pageTitle = json.optString("page_title", "")
              }
            } catch (_: Exception) {}

            val sessionId = BridgeSyncManager.registerHandshake(
              scriptVersion = scriptVersion,
              browser = browser,
              pageUrl = pageUrl,
              pageTitle = pageTitle
            )

            statusCode = 200
            val jsonResp = JSONObject().apply {
              put("status", "synchronized")
              put("app", "InProcess Proxy Ngrok Agent")
              put("version", "1.0.0")
              put("session_id", sessionId)
              put("server_timestamp", System.currentTimeMillis())
              put("heartbeat_interval_ms", 3000)
              put("proxy_config", ReverseProxyManager.getFullConfigJson())
              if (context != null) {
                put("phone_telemetry", PhoneDeviceManager.getTelemetryJson(context))
              }
            }.toString()
            sendResponse(output, 200, "application/json", jsonResp)
          }

          // 3. Heartbeat
          cleanPath == "/api/bridge/heartbeat" -> {
            var sessionId = ""
            var currentUrl = ""
            try {
              if (body.isNotBlank()) {
                val json = JSONObject(body)
                sessionId = json.optString("session_id", "")
                currentUrl = json.optString("current_url", "")
              }
            } catch (_: Exception) {}

            BridgeSyncManager.registerHeartbeat(sessionId, currentUrl)
            statusCode = 200
            val jsonResp = JSONObject().apply {
              put("status", "active")
              put("server_time", System.currentTimeMillis())
            }.toString()
            sendResponse(output, 200, "application/json", jsonResp)
          }

          // 4. Bridge Status Check
          cleanPath == "/api/bridge/status" -> {
            statusCode = 200
            val isSynced = BridgeSyncManager.isSynced.value
            val client = BridgeSyncManager.clientInfo.value
            val jsonResp = JSONObject().apply {
              put("is_synced", isSynced)
              put("port", port)
              if (client != null) {
                put("browser", client.browser)
                put("page_url", client.pageUrl)
                put("latency_ms", client.latencyMs)
                put("last_heartbeat", client.lastHeartbeatTimestamp)
              }
            }.toString()
            sendResponse(output, 200, "application/json", jsonResp)
          }

          // 5. Phone Device Telemetry API
          cleanPath == "/api/phone/status" || cleanPath == "/api/phone/telemetry" -> {
            statusCode = 200
            val json = if (context != null) {
              PhoneDeviceManager.getTelemetryJson(context)
            } else {
              JSONObject().apply {
                put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
              }
            }
            sendResponse(output, 200, "application/json", json.toString(2))
          }

          // 6. Reverse Proxy Configuration API
          cleanPath == "/api/proxy/config" -> {
            statusCode = 200
            val json = ReverseProxyManager.getFullConfigJson().toString(2)
            sendResponse(output, 200, "application/json", json)
          }

          // 7. Dynamic Reverse Proxy Forwarding Execution
          cleanPath == "/proxy" || cleanPath == "/proxy/forward" -> {
            val query = if (path.contains("?")) path.substringAfter("?") else ""
            var targetUrl = ""
            query.split("&").forEach { param ->
              val kv = param.split("=")
              if (kv.isNotEmpty() && kv[0] == "url") {
                targetUrl = if (kv.size > 1) {
                  try { URLDecoder.decode(kv[1], "UTF-8") } catch (_: Exception) { kv[1] }
                } else ""
              }
            }

            if (targetUrl.isBlank()) {
              statusCode = 400
              sendResponse(output, 400, "application/json", """{"error": "Missing 'url' query parameter. Example: /proxy?url=https://api.example.com"}""")
            } else {
              val proxyResp = ReverseProxyManager.forwardHttpRequest(
                method = method,
                targetUrl = targetUrl,
                incomingHeaders = headers,
                requestBody = if (body.isNotBlank()) body else null,
                clientIp = clientIp
              )
              statusCode = proxyResp.statusCode
              sendRawProxyResponse(output, proxyResp.statusCode, proxyResp.headers, proxyResp.body, proxyResp.contentType)
            }
          }

          // 8. Ping
          cleanPath == "/ping" -> {
            statusCode = 200
            val json = JSONObject().apply {
              put("status", "pong")
              put("uptime_seconds", (System.currentTimeMillis() - startTime) / 1000)
              put("port", port)
              put("bridge_synced", BridgeSyncManager.isSynced.value)
              put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
              put("timestamp", System.currentTimeMillis())
            }.toString(2)
            sendResponse(output, 200, "application/json", json)
          }

          // 9. Diagnostics Status
          cleanPath == "/status" -> {
            statusCode = 200
            val runtime = java.lang.Runtime.getRuntime()
            val json = JSONObject().apply {
              put("service", "Android InProcess JNI Proxy & Ngrok Tunnel Server")
              put("status", "online")
              put("port", port)
              put("bridge_synced", BridgeSyncManager.isSynced.value)
              put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
              put("uptime_ms", System.currentTimeMillis() - startTime)
              put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
              put("android_version", Build.VERSION.RELEASE)
              put("sdk_int", Build.VERSION.SDK_INT)
              put("memory_free_mb", runtime.freeMemory() / (1024 * 1024))
              put("memory_total_mb", runtime.totalMemory() / (1024 * 1024))
            }.toString(2)
            sendResponse(output, 200, "application/json", json)
          }

          // 10. Echo
          cleanPath == "/echo" -> {
            statusCode = 200
            val json = JSONObject().apply {
              put("method", method)
              put("path", path)
              put("client_ip", clientIp)
              put("headers", JSONObject(headers as Map<*, *>))
              put("body", body)
              put("timestamp", System.currentTimeMillis())
            }.toString(2)
            sendResponse(output, 200, "application/json", json)
          }

          // 11. Headers
          cleanPath == "/headers" -> {
            statusCode = 200
            val json = JSONObject(headers as Map<*, *>).toString(2)
            sendResponse(output, 200, "application/json", json)
          }

          // 12. Rich Phone Remote Web Portal
          else -> {
            statusCode = 200
            val isSynced = BridgeSyncManager.isSynced.value
            val bridgeStatusBadge = if (isSynced)
              """<span style="background: #10B981; color: #064E3B; padding: 4px 12px; border-radius: 999px; font-weight: bold; font-size: 13px;">🟢 SYNCHRONIZED</span>"""
            else
              """<span style="background: #F59E0B; color: #78350F; padding: 4px 12px; border-radius: 999px; font-weight: bold; font-size: 13px;">🟡 WAITING FOR PAIRING</span>"""

            val phoneStats = if (context != null) PhoneDeviceManager.getTelemetry(context) else null
            val batteryText = if (phoneStats != null) "${phoneStats.batteryPercent}% (${phoneStats.chargingType})" else "100%"
            val networkText = phoneStats?.networkType ?: "Active"
            val ipText = phoneStats?.localIpAddresses?.firstOrNull() ?: "127.0.0.1"
            val storageText = if (phoneStats != null) "${phoneStats.storageFreeGb} GB free / ${phoneStats.storageTotalGb} GB" else "Available"
            val ramText = if (phoneStats != null) "${phoneStats.ramFreeMb} MB free / ${phoneStats.ramTotalMb} MB" else "Available"
            val uptimeText = phoneStats?.uptimeFormatted ?: "Running"

            val html = """
              <!DOCTYPE html>
              <html lang="en">
              <head>
                <meta charset="utf-8">
                <title>Ngrok Native Android Gateway Portal</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                  * { box-sizing: border-box; }
                  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0A0F16; color: #E2E8F0; margin: 0; padding: 16px; }
                  .container { max-width: 860px; margin: 0 auto; background: #131D2A; border-radius: 18px; padding: 26px; box-shadow: 0 20px 40px rgba(0,0,0,0.6); border: 1px solid #223548; }
                  .header { display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #223548; padding-bottom: 18px; margin-bottom: 20px; flex-wrap: wrap; gap: 10px; }
                  h1 { margin: 0; color: #38BDF8; font-size: 22px; display: flex; align-items: center; gap: 8px; }
                  .subtitle { color: #94A3B8; font-size: 13px; margin-top: 4px; }
                  .hero-card { background: linear-gradient(135deg, #0A0F16 0%, #172230 100%); border: 2px dashed #0284C7; border-radius: 14px; padding: 20px; text-align: center; margin: 18px 0; }
                  .install-btn { display: inline-block; background: #10B981; color: #064E3B; font-weight: 800; font-size: 16px; padding: 12px 26px; border-radius: 10px; text-decoration: none; box-shadow: 0 4px 16px rgba(16,185,129,0.3); }
                  .section-title { font-size: 14px; font-weight: bold; color: #38BDF8; text-transform: uppercase; letter-spacing: 0.8px; margin: 20px 0 10px 0; }
                  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
                  .card { background: #0A0F16; border-radius: 12px; padding: 14px; border: 1px solid #223548; }
                  .card-label { color: #94A3B8; font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 4px; }
                  .card-value { color: #F1F5F9; font-weight: bold; font-size: 14px; }
                  .endpoint-btn { display: inline-block; background: #1E293B; color: #38BDF8; padding: 6px 12px; border-radius: 6px; text-decoration: none; font-family: monospace; font-size: 12px; margin: 4px; }
                </style>
              </head>
              <body>
                <div class="container">
                  <div class="header">
                    <div>
                      <h1>📱 Ngrok Native Android Gateway Portal</h1>
                      <div class="subtitle">Unified Access: Live Telemetry &bull; In-Process Proxy &bull; Public Tunnel Ingress</div>
                    </div>
                    <div>$bridgeStatusBadge</div>
                  </div>

                  <div class="hero-card">
                    <div style="font-size: 15px; font-weight: bold; color: #F8FAFC; margin-bottom: 6px;">⚡ Companion Userscript Bridge</div>
                    <p style="font-size: 13px; color: #94A3B8; margin-bottom: 16px;">Bidirectional pairing with Android phone server & in-process proxy engine.</p>
                    <a href="$effectiveBaseUrl/Proxy-Redirect.user.js" class="install-btn">⚡ 1-Click Install Userscript</a>
                  </div>

                  <div class="section-title">📱 Live Phone Device Telemetry</div>
                  <div class="grid">
                    <div class="card">
                      <div class="card-label">Device Model</div>
                      <div class="card-value">${Build.MANUFACTURER} ${Build.MODEL}</div>
                    </div>
                    <div class="card">
                      <div class="card-label">Android OS</div>
                      <div class="card-value">Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})</div>
                    </div>
                    <div class="card">
                      <div class="card-label">Battery Level</div>
                      <div class="card-value" style="color: #10B981;">$batteryText</div>
                    </div>
                    <div class="card">
                      <div class="card-label">Network Transport</div>
                      <div class="card-value">$networkText</div>
                    </div>
                    <div class="card">
                      <div class="card-label">Device IP</div>
                      <div class="card-value" style="font-family: monospace; font-size: 12px;">$ipText</div>
                    </div>
                    <div class="card">
                      <div class="card-label">System Uptime</div>
                      <div class="card-value">$uptimeText</div>
                    </div>
                  </div>

                  <div class="section-title">🌐 Live Gateway Endpoints</div>
                  <div style="background: #0A0F16; padding: 14px; border-radius: 12px; border: 1px solid #223548;">
                    <a class="endpoint-btn" href="$effectiveBaseUrl/api/phone/status">/api/phone/status</a>
                    <a class="endpoint-btn" href="$effectiveBaseUrl/api/proxy/config">/api/proxy/config</a>
                    <a class="endpoint-btn" href="$effectiveBaseUrl/ping">/ping</a>
                    <a class="endpoint-btn" href="$effectiveBaseUrl/status">/status</a>
                    <a class="endpoint-btn" href="$effectiveBaseUrl/headers">/headers</a>
                    <a class="endpoint-btn" href="$effectiveBaseUrl/echo">/echo</a>
                  </div>
                </div>
              </body>
              </html>
            """.trimIndent()
            sendResponse(output, 200, "text/html; charset=UTF-8", html)
          }
        }
      }
    } catch (e: Exception) {
      Log.w(tag, "Client request error: ${e.message}")
    } finally {
      val durationMs = System.currentTimeMillis() - startMs
      val entry = TrafficLogEntry(
        method = method,
        path = path,
        statusCode = statusCode,
        clientIp = clientIp,
        responseDurationMs = durationMs,
        headers = headers,
        requestBody = if (body.isNotBlank()) body else null
      )
      onRequestHandled(entry)
    }
  }

  private fun sendScriptResponse(output: OutputStream, scriptContent: String) {
    val bytes = scriptContent.toByteArray(Charsets.UTF_8)
    val headers = "HTTP/1.1 200 OK\r\n" +
      "Content-Type: text/javascript; charset=UTF-8\r\n" +
      "Content-Disposition: inline; filename=\"Proxy-Redirect.user.js\"\r\n" +
      "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
      "Connection: close\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Content-Length: ${bytes.size}\r\n\r\n"
    output.write(headers.toByteArray(Charsets.UTF_8))
    output.write(bytes)
    output.flush()
  }

  private fun sendCorsPreflight(output: OutputStream) {
    val headers = "HTTP/1.1 204 No Content\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS, PATCH\r\n" +
      "Access-Control-Allow-Headers: *\r\n" +
      "Access-Control-Max-Age: 86400\r\n" +
      "Content-Length: 0\r\n" +
      "Connection: close\r\n\r\n"
    output.write(headers.toByteArray(Charsets.UTF_8))
    output.flush()
  }

  private fun sendResponse(output: OutputStream, status: Int, contentType: String, content: String) {
    val bytes = content.toByteArray(Charsets.UTF_8)
    val statusText = when (status) {
      200 -> "200 OK"
      204 -> "204 No Content"
      400 -> "400 Bad Request"
      404 -> "404 Not Found"
      502 -> "502 Bad Gateway"
      else -> "$status OK"
    }
    val headers = "HTTP/1.1 $statusText\r\n" +
      "Content-Type: $contentType\r\n" +
      "Content-Length: ${bytes.size}\r\n" +
      "Connection: close\r\n" +
      "Access-Control-Allow-Origin: *\r\n\r\n"
    output.write(headers.toByteArray(Charsets.UTF_8))
    output.write(bytes)
    output.flush()
  }

  private fun sendRawProxyResponse(
    output: OutputStream,
    status: Int,
    headers: Map<String, String>,
    body: String,
    contentType: String
  ) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val statusText = when (status) {
      200 -> "200 OK"
      201 -> "201 Created"
      204 -> "204 No Content"
      400 -> "400 Bad Request"
      404 -> "404 Not Found"
      502 -> "502 Bad Gateway"
      else -> "$status Proxy Response"
    }

    val headerBuilder = StringBuilder("HTTP/1.1 $statusText\r\n")
    headerBuilder.append("Content-Type: $contentType\r\n")
    headerBuilder.append("Content-Length: ${bytes.size}\r\n")
    headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
    headers.forEach { (k, v) ->
      val kl = k.lowercase(Locale.US)
      if (kl != "content-type" && kl != "content-length" && kl != "connection") {
        headerBuilder.append("$k: $v\r\n")
      }
    }
    headerBuilder.append("Connection: close\r\n\r\n")

    output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
    output.write(bytes)
    output.flush()
  }

  fun stop() {
    isRunning = false
    try {
      serverSocket?.close()
    } catch (_: Exception) {}
    serverSocket = null
    serverJob?.cancel()
    serverJob = null
    Log.i(tag, "Embedded HTTP Server stopped")
  }
}
