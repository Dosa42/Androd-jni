package com.example.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class BridgeClientInfo(
    val sessionId: String,
    val scriptVersion: String,
    val browser: String,
    val pageUrl: String,
    val pageTitle: String,
    val connectedAt: Long = System.currentTimeMillis(),
    val lastHeartbeatTimestamp: Long = System.currentTimeMillis(),
    val latencyMs: Long = 0
)

object UserscriptSource {
    const val SCRIPT_CONTENT: String = """// ==UserScript==
// @name         InProcess Proxy & Ngrok Agent Bridge
// @namespace    http://tampermonkey.net/
// @version      26.10.10
// @description  Bidirectional Android InProcess & Ngrok Agent Bridge
// @author       AI Studio
// @match        *://*/*
// @grant        GM_xmlhttpRequest
// @connect      127.0.0.1
// @connect      localhost
// @connect      *
// @run-at       document-start
// ==/UserScript==

(function() {
    'use strict';
    const BRIDGE_URL = "http://127.0.0.1:8085/api/bridge/handshake";
    console.log("[NgrokBridge] InProcess Proxy Userscript Initialized");
})();
"""
}

object BridgeSyncManager {
    private val _isSynced = MutableStateFlow(false)
    val isSynced: StateFlow<Boolean> = _isSynced.asStateFlow()

    private val _clientInfo = MutableStateFlow<BridgeClientInfo?>(null)
    val clientInfo: StateFlow<BridgeClientInfo?> = _clientInfo.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun registerHandshake(
        scriptVersion: String,
        browser: String,
        pageUrl: String,
        pageTitle: String
    ): String {
        val sessionId = UUID.randomUUID().toString().take(8)
        _clientInfo.value = BridgeClientInfo(
            sessionId = sessionId,
            scriptVersion = scriptVersion,
            browser = browser,
            pageUrl = pageUrl,
            pageTitle = pageTitle
        )
        _isSynced.value = true
        addLog("HANDSHAKE", "Registered $browser session $sessionId on $pageUrl")
        return sessionId
    }

    fun registerHeartbeat(sessionId: String, currentUrl: String) {
        val current = _clientInfo.value
        if (current != null && current.sessionId == sessionId) {
            val now = System.currentTimeMillis()
            _clientInfo.value = current.copy(
                lastHeartbeatTimestamp = now,
                pageUrl = if (currentUrl.isNotBlank()) currentUrl else current.pageUrl,
                latencyMs = now - current.lastHeartbeatTimestamp
            )
            _isSynced.value = true
        }
    }

    fun addLog(tag: String, message: String) {
        val entry = "[$tag] $message"
        _logs.value = (_logs.value + entry).takeLast(50)
    }
}

data class ProxyResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
    val contentType: String = "application/json"
)

object ReverseProxyManager {
    fun getFullConfigJson(): JSONObject {
        return JSONObject().apply {
            put("status", "active")
            put("engine", "Android InProcess JNI & Ngrok Forwarder")
            put("allow_cors", true)
            put("version", "1.0.0")
        }
    }

    fun handleReportOfflineInstance(context: android.content.Context, instanceUrl: String) {
        BridgeSyncManager.addLog("OFFLINE_REPORT", "Reported offline instance: $instanceUrl")
    }

    fun forwardHttpRequest(
        method: String,
        targetUrl: String,
        incomingHeaders: Map<String, String>,
        requestBody: String?,
        clientIp: String
    ): ProxyResponse {
        return try {
            val url = URL(targetUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true

            incomingHeaders.forEach { (k, v) ->
                val lower = k.lowercase()
                if (lower != "host" && lower != "content-length" && lower != "connection") {
                    conn.setRequestProperty(k, v)
                }
            }

            if (requestBody != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                conn.doOutput = true
                conn.outputStream.use { os ->
                    os.write(requestBody.toByteArray(Charsets.UTF_8))
                }
            }

            val statusCode = conn.responseCode
            val isSuccess = statusCode in 200..399
            val stream = if (isSuccess) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val respBody = stream.bufferedReader().use { it.readText() }

            val outHeaders = mutableMapOf<String, String>()
            conn.headerFields.forEach { (k, v) ->
                if (k != null && v.isNotEmpty()) {
                    outHeaders[k] = v[0]
                }
            }
            val contentType = conn.contentType ?: "text/plain; charset=UTF-8"
            conn.disconnect()

            ProxyResponse(statusCode, outHeaders, respBody, contentType)
        } catch (e: Exception) {
            ProxyResponse(
                statusCode = 502,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"error": "Reverse proxy failed: ${e.message}"}""",
                contentType = "application/json"
            )
        }
    }
}
