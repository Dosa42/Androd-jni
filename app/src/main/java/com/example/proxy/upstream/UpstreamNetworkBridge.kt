package com.example.proxy.upstream

import android.util.Log
import com.example.proxy.ProtocolType
import com.example.proxy.ProxyJniCallback
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class UpstreamSession(val connId: Long, val url: String, val protocol: ProtocolType) {
    abstract fun sendText(text: String): Boolean
    abstract fun sendBinary(bytes: ByteArray): Boolean
    abstract fun close()
}

class UpstreamWebSocketSession(
    connId: Long,
    url: String,
    protocol: ProtocolType,
    private val webSocket: WebSocket
) : UpstreamSession(connId, url, protocol) {
    override fun sendText(text: String): Boolean = webSocket.send(text)
    override fun sendBinary(bytes: ByteArray): Boolean = webSocket.send(bytes.toByteString())
    override fun close() {
        try {
            webSocket.close(1000, "Client closed")
        } catch (e: Exception) {
            // Ignore
        }
    }
}

class UpstreamSseSession(
    connId: Long,
    url: String,
    private val call: Call,
    private val job: Job
) : UpstreamSession(connId, url, ProtocolType.HTTP_SSE) {
    override fun sendText(text: String): Boolean = false
    override fun sendBinary(bytes: ByteArray): Boolean = false
    override fun close() {
        job.cancel()
        call.cancel()
    }
}

/**
 * Real Outbound Internet Forwarding Bridge.
 * Receives in-process proxy requests and forwards them out to external remote
 * WebSocket, Socket.IO, and SSE servers over the internet via OkHttp, streaming
 * all remote frames directly back into in-process JNI callbacks.
 */
object UpstreamNetworkBridge {
    private const val TAG = "UpstreamNetworkBridge"

    private val bridgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for long-lived WebSockets & SSE
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val activeUpstreamSessions = ConcurrentHashMap<Long, UpstreamSession>()

    fun isUpstream(connId: Long): Boolean = activeUpstreamSessions.containsKey(connId)

    fun getSession(connId: Long): UpstreamSession? = activeUpstreamSessions[connId]

    /**
     * Bridges an in-process proxy request to a remote WebSocket server (e.g., wss://echo.websocket.events).
     * Dispatches all remote frames to the raw JNI callback in-process.
     */
    fun openWebSocket(
        connId: Long,
        targetUrl: String,
        protocol: ProtocolType = ProtocolType.WEBSOCKET,
        callback: ProxyJniCallback
    ): UpstreamSession {
        Log.i(TAG, "Opening external upstream WebSocket: $targetUrl (connId=$connId)")

        val request = Request.Builder()
            .url(targetUrl)
            .addHeader("User-Agent", "InProcess-FFI-Proxy/1.0 (Android)")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Upstream WS Connected: $targetUrl (HTTP ${response.code})")
                callback.onStatus(connId, 200, "UPSTREAM_CONNECTED: $targetUrl")

                // If Socket.IO over upstream, we might receive handshake packets directly from the remote server
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Deliver raw remote text frame directly to JNI callback
                val rawBytes = text.toByteArray(Charsets.UTF_8)
                callback.onWsFrame(connId, 0x1, true, rawBytes)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Deliver raw remote binary frame directly to JNI callback
                callback.onWsFrame(connId, 0x2, true, bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Upstream WS Closing: code=$code reason=$reason")
                callback.onWsFrame(connId, 0x8, true, reason.toByteArray(Charsets.UTF_8))
                callback.onStatus(connId, code, "UPSTREAM_CLOSING: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Upstream WS Closed: code=$code reason=$reason")
                callback.onStatus(connId, code, "UPSTREAM_CLOSED")
                activeUpstreamSessions.remove(connId)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Upstream WS Failure on $targetUrl: ${t.message}")
                val errCode = response?.code ?: 502
                callback.onStatus(connId, errCode, "UPSTREAM_ERR: ${t.message ?: "Connection failure"}")
                activeUpstreamSessions.remove(connId)
            }
        }

        val ws = okHttpClient.newWebSocket(request, listener)
        val session = UpstreamWebSocketSession(connId, targetUrl, protocol, ws)
        activeUpstreamSessions[connId] = session
        return session
    }

    /**
     * Bridges an in-process proxy request to a remote HTTP Server-Sent Events stream.
     * Streams incoming event blocks into the JNI callback onSseChunk.
     */
    fun openSse(
        streamId: Long,
        targetUrl: String,
        callback: ProxyJniCallback
    ): UpstreamSession {
        Log.i(TAG, "Opening external upstream SSE stream: $targetUrl (streamId=$streamId)")

        val request = Request.Builder()
            .url(targetUrl)
            .addHeader("Accept", "text/event-stream")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("User-Agent", "InProcess-FFI-Proxy/1.0 (Android)")
            .build()

        val call = okHttpClient.newCall(request)

        val job = bridgeScope.launch {
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    callback.onStatus(streamId, response.code, "UPSTREAM_HTTP_ERR: ${response.code}")
                    return@launch
                }

                callback.onStatus(streamId, 200, "UPSTREAM_SSE_STREAMING")

                val body = response.body
                if (body == null) {
                    callback.onStatus(streamId, 500, "EMPTY_UPSTREAM_BODY")
                    return@launch
                }

                val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
                var currentEvent = "message"
                val chunkBuffer = StringBuilder()

                while (isActive) {
                    val line = reader.readLine() ?: break
                    chunkBuffer.append(line).append("\n")

                    if (line.startsWith("event:")) {
                        currentEvent = line.substring(6).trim()
                    }

                    // SSE event boundary is indicated by an empty line "\n"
                    if (line.isEmpty()) {
                        val chunkBytes = chunkBuffer.toString().toByteArray(Charsets.UTF_8)
                        callback.onSseChunk(streamId, currentEvent, chunkBytes, false)
                        chunkBuffer.clear()
                        currentEvent = "message"
                    }
                }

                // Stream ended
                callback.onSseChunk(streamId, "end", "event: end\ndata: [STREAM_FINISHED]\n\n".toByteArray(Charsets.UTF_8), true)
                callback.onStatus(streamId, 1000, "UPSTREAM_SSE_COMPLETED")
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Upstream SSE stream exception: ${e.message}")
                    callback.onStatus(streamId, 502, "UPSTREAM_SSE_FAIL: ${e.message}")
                }
            } finally {
                activeUpstreamSessions.remove(streamId)
            }
        }

        val session = UpstreamSseSession(streamId, targetUrl, call, job)
        activeUpstreamSessions[streamId] = session
        return session
    }

    fun sendUpstreamFrame(connId: Long, text: String): Boolean {
        val session = activeUpstreamSessions[connId] ?: return false
        return session.sendText(text)
    }

    fun sendUpstreamBinary(connId: Long, bytes: ByteArray): Boolean {
        val session = activeUpstreamSessions[connId] ?: return false
        return session.sendBinary(bytes)
    }

    fun closeUpstream(connId: Long) {
        val session = activeUpstreamSessions.remove(connId)
        session?.close()
    }
}
