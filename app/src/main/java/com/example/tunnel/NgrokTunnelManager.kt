package com.example.tunnel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

object NgrokTunnelManager {
  private const val TAG = "NgrokTunnelManager"

  private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var tunnelJob: Job? = null

  private var httpServer: EmbeddedHttpServer? = null
  var currentServerPort: Int = 8085
    private set

  private var ngrokSession: Any? = null
  private var ngrokForwarder: Any? = null
  private val resourceMutex = Mutex()

  private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected())
  val state: StateFlow<TunnelState> = _state.asStateFlow()

  private val _trafficLogs = MutableStateFlow<List<TrafficLogEntry>>(emptyList())
  val trafficLogs: StateFlow<List<TrafficLogEntry>> = _trafficLogs.asStateFlow()

  private val _totalRequests = MutableStateFlow(0)
  val totalRequests: StateFlow<Int> = _totalRequests.asStateFlow()

  fun findAvailablePort(preferredPort: Int): Int {
    return try {
      ServerSocket(preferredPort).use { it.localPort }
    } catch (_: Exception) {
      try {
        ServerSocket(0).use { it.localPort }
      } catch (_: Exception) {
        preferredPort
      }
    }
  }

  fun ensureLocalServerRunning(port: Int, context: Context? = null) {
    if (httpServer?.isRunning == true && currentServerPort == port) return
    coroutineScope.launch {
      resourceMutex.withLock {
        if (httpServer?.isRunning == true && currentServerPort == port) return@withLock
        try {
          httpServer?.stop()
          httpServer = EmbeddedHttpServer(
            port = port,
            context = context,
            onRequestHandled = { entry -> recordTraffic(entry) }
          )
          httpServer?.start()
          currentServerPort = port
          Log.i(TAG, "Local companion HTTP server started on port $port")
        } catch (e: Exception) {
          Log.w(TAG, "Could not start local HTTP server on port $port: ${e.message}")
        }
      }
    }
  }

  fun startTunnel(context: Context, authTokenOverride: String? = null, portOverride: Int? = null) {
    val currentState = _state.value
    if (currentState is TunnelState.Connected || currentState is TunnelState.Connecting || currentState is TunnelState.Stopping) {
      Log.d(TAG, "Tunnel already starting or running")
      return
    }

    val token = (authTokenOverride ?: NgrokConfig.getAuthToken(context)).trim()
    val requestedPort = portOverride ?: NgrokConfig.getLocalPort(context)
    val actualPort = findAvailablePort(requestedPort)

    if (token.isBlank()) {
      _state.value = TunnelState.Error("Ngrok authtoken is required. Tap Configure to enter your token from dashboard.ngrok.com")
      return
    }

    _state.value = TunnelState.Connecting("Starting background tunnel service...")

    try {
      val serviceIntent = Intent(context, NgrokTunnelService::class.java).apply {
        action = NgrokTunnelService.ACTION_START_TUNNEL
        putExtra(NgrokTunnelService.EXTRA_AUTH_TOKEN, token)
        putExtra(NgrokTunnelService.EXTRA_PORT, actualPort)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
      } else {
        context.startService(serviceIntent)
      }
    } catch (e: Exception) {
      _state.value = TunnelState.Error(e.message ?: "Could not start foreground service")
    }
  }

  fun executeStartTunnel(token: String, port: Int) {
    tunnelJob?.cancel()
    tunnelJob = coroutineScope.launch {
      resourceMutex.withLock {
        try {
          require(token.isNotBlank()) { "Enter your ngrok authtoken first" }
          require(port in 1..65535) { "Port must be between 1 and 65535" }

          // 1. Explicitly load and initialize the native ngrok JNI runtime
          _state.value = TunnelState.Connecting("Initializing native Ngrok JNI runtime (libngrok_java.so)...")
          com.ngrok.Runtime.load()

          // 2. Ensure the local embedded HTTP server is running on the target port
          if (httpServer == null || httpServer?.isRunning == false || currentServerPort != port) {
            _state.value = TunnelState.Connecting("Binding local TCP server on port $port...")
            httpServer?.stop()
            httpServer = EmbeddedHttpServer(
              port = port,
              onRequestHandled = { entry -> recordTraffic(entry) }
            )
            httpServer?.start()
            currentServerPort = port
          }

          _state.value = TunnelState.Connecting("Connecting native Ngrok session to edge...")

          // 3. Connect real native Ngrok session
          val sessionClass = Class.forName("com.ngrok.Session")
          val withAuthtokenMethod = sessionClass.getMethod("withAuthtoken", String::class.java)
          val builder = withAuthtokenMethod.invoke(null, token)
          val connectMethod = builder.javaClass.getMethod("connect")
          val session = connectMethod.invoke(builder)
          ngrokSession = session
          currentCoroutineContext().ensureActive()

          // 4. Bind HTTP endpoint forwarder to local companion port
          val httpEndpointMethod = session.javaClass.getMethod("httpEndpoint")
          val endpointBuilder = httpEndpointMethod.invoke(session)
          val forwardMethod = endpointBuilder.javaClass.getMethod("forward", URL::class.java)
          val forwarder = forwardMethod.invoke(endpointBuilder, URL("http://127.0.0.1:$port"))
          ngrokForwarder = forwarder
          currentCoroutineContext().ensureActive()

          val getUrlMethod = forwarder.javaClass.getMethod("getUrl")
          val finalUrl = getUrlMethod.invoke(forwarder) as? String
          check(!finalUrl.isNullOrBlank()) { "Native ngrok returned empty public URL" }

          _state.value = TunnelState.Connected(
            publicUrl = finalUrl,
            localPort = port,
            connectedSince = System.currentTimeMillis(),
            totalRequests = _totalRequests.value,
            isNativeSession = true
          )

          recordTraffic(
            TrafficLogEntry(
              method = "SYSTEM",
              path = "/tunnel/ready",
              statusCode = 200,
              clientIp = "127.0.0.1",
              responseDurationMs = 0,
              message = "Tunnel established: $finalUrl -> http://127.0.0.1:$port"
            )
          )

        } catch (e: CancellationException) {
          closeTunnelResourcesOnly()
          throw e
        } catch (e: Exception) {
          closeTunnelResourcesOnly()
          Log.e(TAG, "Real tunnel startup error", e)
          _state.value = TunnelState.Error(e.cause?.message ?: e.message ?: "Failed to initialize native tunnel")
        } catch (e: Throwable) {
          closeTunnelResourcesOnly()
          Log.e(TAG, "Native ngrok execution error", e)
          _state.value = TunnelState.Error(e.cause?.message ?: e.message ?: "Native ngrok runtime failure")
        }
      }
    }
  }

  fun stopTunnel(context: Context) {
    context.stopService(Intent(context, NgrokTunnelService::class.java))
    releaseResources()
  }

  fun releaseResources() {
    if (_state.value is TunnelState.Stopping || _state.value is TunnelState.Disconnected) return
    _state.value = TunnelState.Stopping()
    tunnelJob?.cancel()
    coroutineScope.launch {
      resourceMutex.withLock {
        closeTunnelResourcesOnly()
        _state.value = TunnelState.Disconnected("Tunnel disconnected")
      }
    }
  }

  private fun closeTunnelResourcesOnly() {
    try {
      if (ngrokForwarder != null) {
        val closeMethod = ngrokForwarder!!.javaClass.getMethod("close")
        closeMethod.invoke(ngrokForwarder)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Forwarder close note", e)
    }
    ngrokForwarder = null

    try {
      if (ngrokSession != null) {
        val closeMethod = ngrokSession!!.javaClass.getMethod("close")
        closeMethod.invoke(ngrokSession)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Session close note", e)
    }
    ngrokSession = null
  }

  fun recordTraffic(entry: TrafficLogEntry) {
    _totalRequests.update { it + 1 }
    _trafficLogs.update { current ->
      (listOf(entry) + current).take(100)
    }
    val curr = _state.value
    if (curr is TunnelState.Connected) {
      _state.value = curr.copy(totalRequests = _totalRequests.value)
    }
  }

  fun clearLogs() {
    _trafficLogs.value = emptyList()
  }

  suspend fun testLocalPing(port: Int): String = withContext(Dispatchers.IO) {
    if (currentServerPort != port || httpServer?.isRunning != true) {
      resourceMutex.withLock {
        try {
          httpServer?.stop()
          httpServer = EmbeddedHttpServer(
            port = port,
            onRequestHandled = { entry -> recordTraffic(entry) }
          )
          httpServer?.start()
          currentServerPort = port
        } catch (e: Exception) {
          Log.w(TAG, "Rebinding server for ping test failed: ${e.message}")
        }
      }
      delay(150)
    }

    try {
      val url = URL("http://127.0.0.1:$port/ping")
      val conn = url.openConnection() as HttpURLConnection
      conn.connectTimeout = 3000
      conn.readTimeout = 3000
      conn.requestMethod = "GET"
      val code = conn.responseCode
      val body = conn.inputStream.bufferedReader().use { it.readText() }
      conn.disconnect()
      "HTTP $code: $body"
    } catch (e: Exception) {
      "Ping failed: ${e.message}"
    }
  }
}
