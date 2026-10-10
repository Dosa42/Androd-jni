package com.example.tunnel

sealed interface TunnelState {
  data class Disconnected(val reason: String? = null) : TunnelState
  data class Connecting(val stage: String) : TunnelState
  data class Connected(
    val publicUrl: String,
    val localPort: Int,
    val connectedSince: Long = System.currentTimeMillis(),
    val totalRequests: Int = 0,
    val isNativeSession: Boolean = true
  ) : TunnelState
  data class Stopping(val message: String = "Stopping tunnel...") : TunnelState
  data class Error(val errorMessage: String, val canRetry: Boolean = true) : TunnelState
}

data class TrafficLogEntry(
  val id: Long = System.currentTimeMillis() + (0..999).random(),
  val timestamp: Long = System.currentTimeMillis(),
  val method: String,
  val path: String,
  val statusCode: Int,
  val clientIp: String,
  val responseDurationMs: Long,
  val headers: Map<String, String> = emptyMap(),
  val requestBody: String? = null,
  val message: String? = null
)
