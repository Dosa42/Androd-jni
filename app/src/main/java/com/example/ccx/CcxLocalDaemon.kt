package com.example.ccx

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

class CcxLocalDaemon(
    val port: Int = 8095
) {
    private val tag = "CcxLocalDaemon"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port).apply { reuseAddress = true }
            isRunning = true
            CcxGatewayManager.setDaemonRunning(true, port)
            Log.i(tag, "CCX Local AI Daemon listening on port $port")

            serverJob = scope.launch {
                while (isActive && isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) Log.w(tag, "Accept exception: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            isRunning = false
            CcxGatewayManager.setDaemonRunning(false, port)
            throw e
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val output = s.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = if (parts.isNotEmpty()) parts[0].uppercase(Locale.US) else "GET"
                val rawPath = if (parts.size >= 2) parts[1] else "/"
                val path = rawPath.substringBefore("?")

                // Read headers
                var contentLength = 0
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) break
                    val colon = line!!.indexOf(':')
                    if (colon > 0) {
                        val k = line!!.substring(0, colon).trim()
                        val v = line!!.substring(colon + 1).trim()
                        headers[k] = v
                        if (k.equals("content-length", ignoreCase = true)) {
                            contentLength = v.toIntOrNull() ?: 0
                        }
                    }
                }

                // Read body
                var body = ""
                if (contentLength > 0 && contentLength < 5000000) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = reader.read(buf, read, contentLength - read)
                        if (r == -1) break
                        read += r
                    }
                    body = String(buf, 0, read)
                }

                // CORS Preflight
                if (method == "OPTIONS") {
                    sendCorsPreflight(output)
                    return
                }

                when {
                    // 1. OpenAI Chat Completions Endpoint
                    path == "/v1/chat/completions" && method == "POST" -> {
                        val activeChannels = CcxGatewayManager.channels.value.filter { it.isEnabled }
                        val bestChannel = activeChannels.minByOrNull { it.priority }

                        val responseJson = if (bestChannel != null && bestChannel.protocol == AiProtocol.CLAUDE_MESSAGES) {
                            // Translate OpenAI input -> Claude upstream format
                            val translation = CcxProtocolTranslator.openAiToClaude(body)
                            JSONObject().apply {
                                put("id", "ccx-chatcmpl-${System.currentTimeMillis()}")
                                put("object", "chat.completion")
                                put("created", System.currentTimeMillis() / 1000)
                                put("model", "ccx-translated-claude")
                                put("choices", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("index", 0)
                                        put("message", JSONObject().apply {
                                            put("role", "assistant")
                                            put("content", "CCX AI Gateway translated payload: Claude model ${translation.targetProtocol.displayName} ready for dispatch.")
                                        })
                                        put("finish_reason", "stop")
                                    })
                                })
                            }.toString()
                        } else {
                            // Native OpenAI response
                            JSONObject().apply {
                                put("id", "ccx-chatcmpl-${System.currentTimeMillis()}")
                                put("object", "chat.completion")
                                put("created", System.currentTimeMillis() / 1000)
                                put("model", bestChannel?.defaultModel ?: "ccx-openai")
                                put("choices", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("index", 0)
                                        put("message", JSONObject().apply {
                                            put("role", "assistant")
                                            put("content", "CCX Gateway loopback response received. Upstream channel: ${bestChannel?.name ?: "Local"}")
                                        })
                                        put("finish_reason", "stop")
                                    })
                                })
                            }.toString()
                        }
                        sendResponse(output, 200, "application/json", responseJson)
                    }

                    // 2. Claude Messages Endpoint
                    path == "/v1/messages" && method == "POST" -> {
                        val activeChannels = CcxGatewayManager.channels.value.filter { it.isEnabled }
                        val bestChannel = activeChannels.minByOrNull { it.priority }

                        val responseJson = JSONObject().apply {
                            put("id", "msg_${System.currentTimeMillis()}")
                            put("type", "message")
                            put("role", "assistant")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "CCX Native Claude Messages endpoint responding. Active channel: ${bestChannel?.name ?: "Internal"}")
                                })
                            })
                            put("model", bestChannel?.defaultModel ?: "claude-3-5-sonnet")
                            put("stop_reason", "end_turn")
                        }.toString()
                        sendResponse(output, 200, "application/json", responseJson)
                    }

                    // 3. Channels API
                    path == "/api/channels" -> {
                        val channels = CcxGatewayManager.channels.value
                        val arr = JSONArray()
                        channels.forEach { ch ->
                            arr.put(JSONObject().apply {
                                put("id", ch.id)
                                put("name", ch.name)
                                put("protocol", ch.protocol.name)
                                put("target_url", ch.targetUrl)
                                put("is_online", ch.isOnline)
                                put("latency_ms", ch.latencyMs)
                            })
                        }
                        sendResponse(output, 200, "application/json", arr.toString(2))
                    }

                    // 4. Ping
                    path == "/ping" -> {
                        val resp = """{"status": "pong", "service": "CCX Local AI Daemon", "port": $port}"""
                        sendResponse(output, 200, "application/json", resp)
                    }

                    else -> {
                        sendResponse(output, 404, "application/json", """{"error": "Endpoint not found on CCX AI Daemon"}""")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Client error: ${e.message}")
        }
    }

    private fun sendCorsPreflight(output: OutputStream) {
        val headers = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sendResponse(output: OutputStream, status: Int, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val statusText = if (status == 200) "200 OK" else "$status Error"
        val headers = "HTTP/1.1 $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        CcxGatewayManager.setDaemonRunning(false, port)
        Log.i(tag, "CCX Local AI Daemon stopped")
    }
}
