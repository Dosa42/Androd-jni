package com.example.proxy

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.proxy.upstream.UpstreamNetworkBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ProxyService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val startTime = System.currentTimeMillis()
    private val totalRequestsCount = AtomicLong(0)
    private val totalFramesCount = AtomicLong(0)
    private val totalBytesCount = AtomicLong(0)
    private var lastBytesCount = 0L

    private val _stats = MutableStateFlow(
        ProxyServiceStats(
            isServiceRunning = true,
            isNativeLoaded = NativeProxyBridge.isNativeLibraryLoaded,
            nativeLibraryMode = if (NativeProxyBridge.isNativeLibraryLoaded) "C++ NDK libnative-proxy.so" else "Direct In-Process FFI Engine"
        )
    )
    val stats: StateFlow<ProxyServiceStats> = _stats.asStateFlow()

    private val _connectionCards = MutableStateFlow<Map<Long, ConnectionCardState>>(emptyMap())
    val connectionCards: StateFlow<Map<Long, ConnectionCardState>> = _connectionCards.asStateFlow()

    private val _logs = MutableStateFlow<List<FrameLog>>(emptyList())
    val logs: StateFlow<List<FrameLog>> = _logs.asStateFlow()

    private var metricsJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ProxyService onCreate (PID: ${android.os.Process.myPid()})")
        startForegroundServiceNotification()
        startMetricsTicker()
        initializeDefaultConnections()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ProxyService onDestroy")
        metricsJob?.cancel()
        serviceScope.cancel()
        _connectionCards.value.keys.forEach { reqId ->
            NativeProxyBridge.invokeCloseStream(reqId)
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "in_process_proxy_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "In-Process Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors direct JNI proxy requests, WebSocket, Socket.IO & SSE frames"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("In-Process FFI Proxy Active")
            .setContentText("Direct JNI Proxy routing WebSocket, Socket.IO & SSE frames")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMetricsTicker() {
        metricsJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val uptime = (System.currentTimeMillis() - startTime) / 1000
                val currentBytes = totalBytesCount.get()
                val deltaBytes = currentBytes - lastBytesCount
                lastBytesCount = currentBytes
                val throughputKbps = (deltaBytes * 8.0) / 1024.0

                val upstreamCount = _connectionCards.value.values.count { it.routingMode == RoutingMode.UPSTREAM_REMOTE }

                _stats.value = _stats.value.copy(
                    isServiceRunning = true,
                    uptimeSeconds = uptime,
                    totalRequests = totalRequestsCount.get(),
                    totalFrames = totalFramesCount.get(),
                    totalBytes = currentBytes,
                    currentThroughputKbps = throughputKbps,
                    activeStreamsCount = _connectionCards.value.size,
                    upstreamStreamsCount = upstreamCount
                )
            }
        }
    }

    /**
     * Initializes default demo connections:
     * 1. Socket.IO v4 Session (In-Process)
     * 2. RFC 6455 Duplex Echo WebSocket (In-Process)
     * 3. HTTP Server-Sent Events (SSE) telemetry stream (In-Process)
     * 4. External Remote Upstream WebSocket (wss://echo.websocket.events)
     */
    fun initializeDefaultConnections() {
        if (_connectionCards.value.isEmpty()) {
            openSession(ProtocolType.SOCKET_IO, "/socket.io/?EIO=4&transport=websocket", RoutingMode.IN_PROCESS_LOCAL)
            openSession(ProtocolType.WEBSOCKET, "/ws/echo", RoutingMode.IN_PROCESS_LOCAL)
            openSession(ProtocolType.HTTP_SSE, "/api/stream/telemetry", RoutingMode.IN_PROCESS_LOCAL)
            openSession(
                ProtocolType.WEBSOCKET,
                "/upstream/echo",
                RoutingMode.UPSTREAM_REMOTE,
                customUpstreamUrl = "wss://echo.websocket.events"
            )
        }
    }

    fun openSession(
        protocol: ProtocolType,
        endpoint: String,
        routingMode: RoutingMode = RoutingMode.IN_PROCESS_LOCAL,
        customUpstreamUrl: String? = null
    ): Long {
        val streamId = System.currentTimeMillis() + (1..999).random()
        totalRequestsCount.incrementAndGet()

        val actualUpstreamUrl = if (routingMode == RoutingMode.UPSTREAM_REMOTE) {
            customUpstreamUrl ?: when (protocol) {
                ProtocolType.WEBSOCKET -> "wss://echo.websocket.events"
                ProtocolType.HTTP_SSE -> "https://sse.dev/test"
                ProtocolType.SOCKET_IO -> "wss://echo.websocket.events"
            }
        } else null

        val card = ConnectionCardState(
            streamId = streamId,
            protocol = protocol,
            endpoint = endpoint,
            routingMode = routingMode,
            upstreamUrl = actualUpstreamUrl,
            status = ConnectionStatus.CONNECTING,
            detailInfo = if (routingMode == RoutingMode.UPSTREAM_REMOTE)
                "Connecting outward to $actualUpstreamUrl..."
            else
                "Handshaking over direct in-process JNI..."
        )

        _connectionCards.value = _connectionCards.value + (streamId to card)

        val jniCallback = object : ProxyJniCallback {
            override fun onSseChunk(streamId: Long, event: String, chunk: ByteArray, isLast: Boolean) {
                totalFramesCount.incrementAndGet()
                totalBytesCount.addAndGet(chunk.size.toLong())

                val chunkText = String(chunk, Charsets.UTF_8)
                updateCard(streamId) { current ->
                    current.copy(
                        status = if (isLast) ConnectionStatus.DISCONNECTED else ConnectionStatus.ACTIVE_STREAMING,
                        framesReceived = current.framesReceived + 1,
                        bytesTransferred = current.bytesTransferred + chunk.size,
                        lastPayload = chunkText.trim(),
                        detailInfo = "Event: $event (${chunk.size} bytes chunk)"
                    )
                }

                addLog(
                    protocol = ProtocolType.HTTP_SSE,
                    direction = Direction.INCOMING_JNI_CALLBACK,
                    opcodeName = "SSE_CHUNK",
                    rawLength = chunk.size,
                    payloadPreview = chunkText.take(120),
                    payload = chunk,
                    isUpstream = routingMode == RoutingMode.UPSTREAM_REMOTE
                )
            }

            override fun onWsFrame(connId: Long, opcode: Int, fin: Boolean, payload: ByteArray) {
                totalFramesCount.incrementAndGet()
                totalBytesCount.addAndGet(payload.size.toLong())

                val opcodeName = when (opcode) {
                    0x1 -> "TEXT"
                    0x2 -> "BINARY"
                    0x8 -> "CLOSE"
                    0x9 -> "PING"
                    0xA -> "PONG"
                    else -> "0x${opcode.toString(16)}"
                }

                val preview = if (opcode == 0x2) {
                    "[Binary: ${payload.size} bytes]"
                } else {
                    String(payload, Charsets.UTF_8).take(120)
                }

                val latency = if (opcode == 0xA || opcode == 0x9) 1L else 0L

                updateCard(connId) { current ->
                    current.copy(
                        status = ConnectionStatus.ACTIVE_STREAMING,
                        framesReceived = current.framesReceived + 1,
                        bytesTransferred = current.bytesTransferred + payload.size,
                        lastPayload = preview,
                        latencyMs = if (latency > 0) latency else current.latencyMs,
                        detailInfo = "Frame: $opcodeName | FIN=$fin | Size=${payload.size}B"
                    )
                }

                addLog(
                    protocol = protocol,
                    direction = Direction.INCOMING_JNI_CALLBACK,
                    opcodeName = opcodeName,
                    rawLength = payload.size,
                    payloadPreview = preview,
                    payload = payload,
                    isUpstream = routingMode == RoutingMode.UPSTREAM_REMOTE
                )
            }

            override fun onStatus(connId: Long, statusCode: Int, message: String) {
                Log.i(TAG, "onStatus: connId=$connId, statusCode=$statusCode, msg=$message")
                updateCard(connId) { current ->
                    current.copy(
                        status = if (statusCode == 200) ConnectionStatus.ACTIVE_STREAMING else ConnectionStatus.DISCONNECTED,
                        detailInfo = "Status $statusCode: $message"
                    )
                }
            }
        }

        // Call direct JNI function invocation (routing inward or outward)
        NativeProxyBridge.invokeProxyRequest(
            reqId = streamId,
            protocol = protocol.id,
            path = endpoint,
            headers = arrayOf("Host: proxy.service", "User-Agent: Android-FFI/1.0"),
            payload = null,
            callback = jniCallback,
            upstreamUrl = actualUpstreamUrl
        )

        return streamId
    }

    fun closeSession(streamId: Long) {
        NativeProxyBridge.invokeCloseStream(streamId)
        updateCard(streamId) { it.copy(status = ConnectionStatus.DISCONNECTED, detailInfo = "Closed by user") }
    }

    fun removeSession(streamId: Long) {
        closeSession(streamId)
        _connectionCards.value = _connectionCards.value - streamId
    }

    fun sendUpstreamFrame(streamId: Long, textPayload: String, isBinary: Boolean = false) {
        val card = _connectionCards.value[streamId] ?: return
        val rawData = textPayload.toByteArray(Charsets.UTF_8)

        totalFramesCount.incrementAndGet()
        totalBytesCount.addAndGet(rawData.size.toLong())

        updateCard(streamId) { current ->
            current.copy(
                framesSent = current.framesSent + 1,
                bytesTransferred = current.bytesTransferred + rawData.size
            )
        }

        addLog(
            protocol = card.protocol,
            direction = Direction.OUTGOING_PROXY_REQUEST,
            opcodeName = if (isBinary) "BINARY" else "TEXT",
            rawLength = rawData.size,
            payloadPreview = textPayload.take(120),
            payload = rawData,
            isUpstream = card.routingMode == RoutingMode.UPSTREAM_REMOTE
        )

        if (card.routingMode == RoutingMode.UPSTREAM_REMOTE) {
            // Forward directly outward to remote internet server
            if (isBinary) {
                UpstreamNetworkBridge.sendUpstreamBinary(streamId, rawData)
            } else {
                UpstreamNetworkBridge.sendUpstreamFrame(streamId, textPayload)
            }
        } else {
            // Encode and dispatch to local in-process engine
            val frameToSend = if (card.protocol == ProtocolType.WEBSOCKET) {
                val opcode = if (isBinary) 0x2 else 0x1
                NativeProxyEngine.encodeWsFrame(opcode, rawData, fin = true, mask = true)
            } else {
                rawData
            }
            NativeProxyEngine.processUpstreamFrame(streamId, frameToSend, isBinary)
        }
    }

    private fun updateCard(streamId: Long, transform: (ConnectionCardState) -> ConnectionCardState) {
        val map = _connectionCards.value.toMutableMap()
        val current = map[streamId]
        if (current != null) {
            map[streamId] = transform(current)
            _connectionCards.value = map
        }
    }

    private fun addLog(
        protocol: ProtocolType,
        direction: Direction,
        opcodeName: String,
        rawLength: Int,
        payloadPreview: String,
        payload: ByteArray,
        isUpstream: Boolean = false
    ) {
        val hex = payload.take(16).joinToString(" ") { String.format("%02X", it) } +
                if (payload.size > 16) " ..." else ""

        val newLog = FrameLog(
            protocol = protocol,
            direction = direction,
            opcodeName = opcodeName,
            rawLength = rawLength,
            payloadPreview = payloadPreview,
            hexDump = hex,
            isUpstream = isUpstream
        )

        val updated = (_logs.value + newLog).takeLast(100)
        _logs.value = updated
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    companion object {
        private const val TAG = "ProxyService"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
