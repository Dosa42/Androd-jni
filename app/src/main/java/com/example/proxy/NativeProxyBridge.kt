package com.example.proxy

import android.util.Log

/**
 * Direct In-Process FFI & JNI Bridge.
 * Exposes native C++ function entry points and handles in-process JNI dispatching.
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
     * Dispatches to C++ JNI when libnative-proxy.so is loaded, or directly to the
     * in-process engine maintaining identical JNI contracts and callback delivery.
     */
    fun invokeProxyRequest(
        reqId: Long,
        protocol: Int,
        path: String,
        headers: Array<String>,
        payload: ByteArray?,
        callback: ProxyJniCallback
    ): Int {
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
