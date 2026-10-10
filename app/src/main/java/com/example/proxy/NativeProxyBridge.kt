package com.example.proxy

import android.util.Log
import com.example.proxy.upstream.UpstreamNetworkBridge

/**
 * Direct In-Process FFI & JNI Bridge.
 * Exposes native C++ function entry points and routes requests either through the
 * in-process local protocol engine or outward to external remote servers.
 */
object NativeProxyBridge {
    private const val TAG = "NativeProxyBridge"

    var isNativeLibraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("native-proxy")
            isNativeLibraryLoaded = true
            Log.i(TAG, "Successfully loaded libnative-proxy.so via System.loadLibrary")
            nativeInit()
        } catch (e: UnsatisfiedLinkError) {
            isNativeLibraryLoaded = false
            Log.w(TAG, "libnative-proxy.so not found on filesystem; using in-process Direct FFI engine: ${e.message}")
        }
    }

    /**
     * Native C++ JNI declaration
     */
    external fun nativeInit(): Int

    /**
     * Native C++ proxyRequest declaration
     */
    external fun proxyRequest(
        reqId: Long,
        protocol: Int,
        path: String,
        headers: Array<String>,
        payload: ByteArray?,
        callback: ProxyJniCallback
    ): Int

    /**
     * Native C++ closeStream declaration
     */
    external fun closeStream(reqId: Long): Int

    /**
     * In-process entry point invoked by Android clients.
     * If upstreamUrl or a full URL is provided, forwards out to the external internet server.
     * Otherwise dispatches to the in-process local FFI engine.
     */
    fun invokeProxyRequest(
        reqId: Long,
        protocol: Int,
        path: String,
        headers: Array<String>,
        payload: ByteArray?,
        callback: ProxyJniCallback,
        upstreamUrl: String? = null
    ): Int {
        val targetUrl = upstreamUrl ?: if (
            path.startsWith("ws://") ||
            path.startsWith("wss://") ||
            path.startsWith("http://") ||
            path.startsWith("https://")
        ) path else null

        if (targetUrl != null) {
            val proto = ProtocolType.entries.find { it.id == protocol } ?: ProtocolType.WEBSOCKET
            Log.i(TAG, "Routing proxy request outward to upstream: $targetUrl (protocol=$proto)")
            return when (proto) {
                ProtocolType.HTTP_SSE -> {
                    UpstreamNetworkBridge.openSse(reqId, targetUrl, callback)
                    0
                }
                ProtocolType.WEBSOCKET, ProtocolType.SOCKET_IO -> {
                    UpstreamNetworkBridge.openWebSocket(reqId, targetUrl, proto, callback)
                    0
                }
            }
        }

        return if (isNativeLibraryLoaded) {
            try {
                proxyRequest(reqId, protocol, path, headers, payload, callback)
            } catch (e: Throwable) {
                Log.e(TAG, "Native proxyRequest failed, falling back to in-process FFI: ${e.message}")
                NativeProxyEngine.dispatchInProcess(reqId, protocol, path, headers, payload, callback)
            }
        } else {
            NativeProxyEngine.dispatchInProcess(reqId, protocol, path, headers, payload, callback)
        }
    }

    fun invokeCloseStream(reqId: Long): Int {
        if (UpstreamNetworkBridge.isUpstream(reqId)) {
            UpstreamNetworkBridge.closeUpstream(reqId)
            return 0
        }
        return if (isNativeLibraryLoaded) {
            try {
                closeStream(reqId)
            } catch (e: Throwable) {
                NativeProxyEngine.closeInProcess(reqId)
            }
        } else {
            NativeProxyEngine.closeInProcess(reqId)
        }
    }
}
