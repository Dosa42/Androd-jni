package com.example.proxy

enum class ProtocolType(val id: Int, val displayName: String) {
    HTTP_SSE(1, "HTTP SSE"),
    WEBSOCKET(2, "RFC 6455 WebSocket"),
    SOCKET_IO(3, "Socket.IO v4")
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    ACTIVE_STREAMING,
    PAUSED,
    ERROR
}

data class FrameLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val protocol: ProtocolType,
    val direction: Direction,
    val opcodeName: String,
    val rawLength: Int,
    val payloadPreview: String,
    val hexDump: String
)

enum class Direction {
    INCOMING_JNI_CALLBACK,
    OUTGOING_PROXY_REQUEST
}

data class ConnectionCardState(
    val streamId: Long,
    val protocol: ProtocolType,
    val endpoint: String,
    val status: ConnectionStatus,
    val activeSince: Long = System.currentTimeMillis(),
    val framesReceived: Long = 0,
    val framesSent: Long = 0,
    val bytesTransferred: Long = 0,
    val latencyMs: Long = 0,
    val detailInfo: String = "",
    val lastPayload: String = ""
)

data class ProxyServiceStats(
    val isServiceRunning: Boolean = false,
    val isNativeLoaded: Boolean = false,
    val nativeLibraryMode: String = "Direct In-Process FFI Engine",
    val pid: Int = android.os.Process.myPid(),
    val uptimeSeconds: Long = 0,
    val totalRequests: Long = 0,
    val totalFrames: Long = 0,
    val totalBytes: Long = 0,
    val currentThroughputKbps: Double = 0.0,
    val activeStreamsCount: Int = 0
)
