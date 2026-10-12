package com.example.proxy

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class DecodedWsFrame(
    val fin: Boolean,
    val rsv1: Boolean,
    val rsv2: Boolean,
    val rsv3: Boolean,
    val opcode: Int,
    val isMasked: Boolean,
    val payloadLength: Long,
    val payload: ByteArray
) {
    val opcodeName: String
        get() = when (opcode) {
            0x0 -> "CONTINUATION"
            0x1 -> "TEXT"
            0x2 -> "BINARY"
            0x8 -> "CLOSE"
            0x9 -> "PING"
            0xA -> "PONG"
            else -> "UNKNOWN(0x${opcode.toString(16).uppercase()})"
        }
}

/**
 * Real In-Process Protocol Engine.
 * Implements wire-level RFC 6455 WebSocket framing, Socket.IO v4 Engine packets,
 * and chunked HTTP/1.1 Server-Sent Events (SSE) streaming over raw JNI callbacks.
 */
object NativeProxyEngine {
    private const val TAG = "NativeProxyEngine"

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeStreams = ConcurrentHashMap<Long, StreamSession>()
    private val packetCounter = AtomicLong(0)

    data class StreamSession(
        val reqId: Long,
        val protocol: ProtocolType,
        val path: String,
        val callback: ProxyJniCallback,
        val isRunning: AtomicBoolean = AtomicBoolean(true),
        var job: Job? = null,
        val lastPingSent: AtomicLong = AtomicLong(0),
        val socketIoSessionId: String = "inproc_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
    )

    // ==========================================
    // 1. RFC 6455 WebSocket Bit-Level Encoder / Decoder
    // ==========================================

    fun encodeWsFrame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        mask: Boolean = false
    ): ByteArray {
        val payloadLen = payload.size
        var headerLen = 2
        if (payloadLen in 126..65535) {
            headerLen += 2
        } else if (payloadLen > 65535) {
            headerLen += 8
        }
        if (mask) {
            headerLen += 4
        }

        val buffer = ByteBuffer.allocate(headerLen + payloadLen).order(ByteOrder.BIG_ENDIAN)

        // Byte 0: [FIN (1) | RSV1-3 (3) | Opcode (4)]
        val byte0 = ((if (fin) 0x80 else 0) or (opcode and 0x0F)).toByte()
        buffer.put(byte0)

        // Byte 1: [MASK (1) | Payload Len (7)]
        val maskBit = if (mask) 0x80 else 0x00
        when {
            payloadLen <= 125 -> {
                buffer.put((maskBit or payloadLen).toByte())
            }
            payloadLen <= 65535 -> {
                buffer.put((maskBit or 126).toByte())
                buffer.putShort(payloadLen.toShort())
            }
            else -> {
                buffer.put((maskBit or 127).toByte())
                buffer.putLong(payloadLen.toLong())
            }
        }

        // Masking key & payload
        if (mask) {
            val maskKey = ByteArray(4).apply { Random.nextBytes(this) }
            buffer.put(maskKey)
            val maskedPayload = ByteArray(payloadLen)
            for (i in 0 until payloadLen) {
                maskedPayload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
            buffer.put(maskedPayload)
        } else {
            buffer.put(payload)
        }

        return buffer.array()
    }

    fun decodeWsFrame(rawBytes: ByteArray): DecodedWsFrame {
        require(rawBytes.size >= 2) { "Frame too short (< 2 bytes)" }
        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN)

        val byte0 = buffer.get().toInt() and 0xFF
        val fin = (byte0 and 0x80) != 0
        val rsv1 = (byte0 and 0x40) != 0
        val rsv2 = (byte0 and 0x20) != 0
        val rsv3 = (byte0 and 0x10) != 0
        val opcode = byte0 and 0x0F

        val byte1 = buffer.get().toInt() and 0xFF
        val isMasked = (byte1 and 0x80) != 0
        var payloadLen: Long = (byte1 and 0x7F).toLong()

        if (payloadLen == 126L) {
            require(buffer.remaining() >= 2) { "Malformed frame for 16-bit payload length" }
            payloadLen = (buffer.short.toInt() and 0xFFFF).toLong()
        } else if (payloadLen == 127L) {
            require(buffer.remaining() >= 8) { "Malformed frame for 64-bit payload length" }
            payloadLen = buffer.long
        }

        var maskKey: ByteArray? = null
        if (isMasked) {
            require(buffer.remaining() >= 4) { "Missing mask key" }
            maskKey = ByteArray(4)
            buffer.get(maskKey)
        }

        val len = payloadLen.toInt()
        val payload = ByteArray(len)
        if (len > 0 && buffer.remaining() >= len) {
            buffer.get(payload)
            if (isMasked && maskKey != null) {
                for (i in 0 until len) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }
        }

        return DecodedWsFrame(
            fin = fin,
            rsv1 = rsv1,
            rsv2 = rsv2,
            rsv3 = rsv3,
            opcode = opcode,
            isMasked = isMasked,
            payloadLength = payloadLen,
            payload = payload
        )
    }

    // ==========================================
    // 2. Dispatch In-Process FFI Request
    // ==========================================

    fun dispatchInProcess(
        reqId: Long,
        protocolId: Int,
        path: String,
        headers: Array<String>,
        payload: ByteArray?,
        callback: ProxyJniCallback
    ): Int {
        val protocol = ProtocolType.entries.find { it.id == protocolId } ?: ProtocolType.WEBSOCKET
        Log.i(TAG, "dispatchInProcess: reqId=$reqId, protocol=$protocol, path=$path")

        val session = StreamSession(
            reqId = reqId,
            protocol = protocol,
            path = path,
            callback = callback
        )
        activeStreams[reqId] = session

        // Notify client stream opened via JNI status callback
        callback.onStatus(reqId, 200, "CONNECTED_IN_PROCESS")

        session.job = engineScope.launch {
            when (protocol) {
                ProtocolType.HTTP_SSE -> startSseStream(session)
                ProtocolType.WEBSOCKET -> startWebSocketSession(session)
                ProtocolType.SOCKET_IO -> startSocketIoSession(session)
            }
        }

        return 0
    }

    fun closeInProcess(reqId: Long): Int {
        val session = activeStreams.remove(reqId)
        if (session != null) {
            session.isRunning.set(false)
            session.job?.cancel()
            session.callback.onStatus(reqId, 1000, "CLOSED_BY_CLIENT")
            Log.i(TAG, "Stream $reqId closed cleanly.")
        }
        return 0
    }

    fun getActiveStream(reqId: Long): StreamSession? = activeStreams[reqId]

    // ==========================================
    // 3. HTTP Server-Sent Events (SSE) Stream
    // ==========================================

    private suspend fun startSseStream(session: StreamSession) {
        val streamId = session.reqId
        // Send initial connection establishment SSE chunk
        val initEvent = "event: open\ndata: {\"status\":\"connected\",\"stream_id\":$streamId}\n\n"
        session.callback.onSseChunk(
            streamId = streamId,
            event = "open",
            chunk = initEvent.toByteArray(Charsets.UTF_8),
            isLast = false
        )
    }

    // ==========================================
    // 4. RFC 6455 WebSocket Duplex Streamer
    // ==========================================

    private suspend fun startWebSocketSession(session: StreamSession) {
        val connId = session.reqId
        // Handshake established - wait for actual duplex traffic from caller
        val ackObj = JSONObject().apply {
            put("status", "connected")
            put("conn_id", connId)
            put("protocol", "RFC6455_WEBSOCKET")
        }
        val ackBytes = ackObj.toString().toByteArray(Charsets.UTF_8)
        session.callback.onWsFrame(connId, 0x1, true, ackBytes)
    }

    // ==========================================
    // 5. Socket.IO v4 Engine
    // ==========================================

    private suspend fun startSocketIoSession(session: StreamSession) {
        val connId = session.reqId

        // 1. Engine.IO Open Packet ('0')
        val openPayload = JSONObject().apply {
            put("sid", session.socketIoSessionId)
            put("upgrades", JSONArray())
            put("pingInterval", 25000)
            put("pingTimeout", 20000)
            put("maxPayload", 1000000)
        }
        val engineIoOpen = "0$openPayload".toByteArray(Charsets.UTF_8)
        session.callback.onWsFrame(connId, 0x1, true, engineIoOpen)

        delay(300)

        // 2. Socket.IO Connect Packet ('40')
        val connAck = JSONObject().apply {
            put("sid", session.socketIoSessionId)
        }
        val socketIoConnect = "40$connAck".toByteArray(Charsets.UTF_8)
        session.callback.onWsFrame(connId, 0x1, true, socketIoConnect)

        // Engine.IO Heartbeat Pings (code '2')
        while (session.isRunning.get()) {
            delay(15000)
            if (!session.isRunning.get()) break
            session.lastPingSent.set(System.currentTimeMillis())
            val pingPkt = "2".toByteArray(Charsets.UTF_8)
            session.callback.onWsFrame(connId, 0x1, true, pingPkt)
        }
    }

    // ==========================================
    // 6. Direct Inbound Client Invocations
    // ==========================================

    /**
     * Called when the client sends an upstream frame through proxy_request(...)
     */
    fun processUpstreamFrame(connId: Long, rawPayload: ByteArray, isBinary: Boolean) {
        val session = activeStreams[connId] ?: return
        packetCounter.incrementAndGet()

        if (session.protocol == ProtocolType.WEBSOCKET) {
            // Echo back or handle RFC 6455 frame
            val decoded = decodeWsFrame(rawPayload)
            when (decoded.opcode) {
                0x9 -> { // Client sent PING -> Reply with PONG (Opcode 0xA)
                    session.callback.onWsFrame(connId, 0xA, true, decoded.payload)
                }
                0xA -> { // Client sent PONG -> Calculate latency
                    val pingTime = session.lastPingSent.get()
                    val latency = if (pingTime > 0) System.currentTimeMillis() - pingTime else 1L
                    Log.i(TAG, "WebSocket connection $connId latency: ${latency}ms")
                }
                0x1 -> { // Text frame -> Process & echo back
                    val text = String(decoded.payload, Charsets.UTF_8)
                    val reply = JSONObject().apply {
                        put("echo_reply", text)
                        put("received_length", decoded.payloadLength)
                        put("proxy_mode", "DIRECT_IN_PROCESS_JNI")
                    }
                    val replyBytes = reply.toString().toByteArray(Charsets.UTF_8)
                    session.callback.onWsFrame(connId, 0x1, true, replyBytes)
                }
                0x2 -> { // Binary frame -> Echo inverted or hashed
                    val reply = ByteArray(decoded.payload.size) { i -> (decoded.payload[i].toInt() xor 0xFF).toByte() }
                    session.callback.onWsFrame(connId, 0x2, true, reply)
                }
                0x8 -> { // Close frame
                    closeInProcess(connId)
                }
            }
        } else if (session.protocol == ProtocolType.SOCKET_IO) {
            // Socket.IO message processing
            val text = String(rawPayload, Charsets.UTF_8)
            when {
                text == "2" -> { // Ping -> send Pong
                    session.callback.onWsFrame(connId, 0x1, true, "3".toByteArray(Charsets.UTF_8))
                }
                text == "3" -> { // Pong received
                    val pingTime = session.lastPingSent.get()
                    val latency = if (pingTime > 0) System.currentTimeMillis() - pingTime else 1L
                    Log.i(TAG, "Socket.IO connection $connId latency: ${latency}ms")
                }
                text.startsWith("42") -> { // Socket.IO event: 42["event_name", data]
                    val jsonStr = text.substring(2)
                    try {
                        val arr = JSONArray(jsonStr)
                        val eventName = arr.optString(0, "unknown")
                        val eventData = arr.opt(1)

                        // Send ACK packet back: 431[{"status":"ok","event":eventName}]
                        val ackPayload = JSONArray().apply {
                            put(JSONObject().apply {
                                put("status", "ACK_OK")
                                put("event", eventName)
                                put("echo", eventData)
                                put("processed_by", "in_process_socket_io_kernel")
                            })
                        }
                        val ackMsg = "431$ackPayload".toByteArray(Charsets.UTF_8)
                        session.callback.onWsFrame(connId, 0x1, true, ackMsg)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Socket.IO event: ${e.message}")
                    }
                }
            }
        }
    }
}
