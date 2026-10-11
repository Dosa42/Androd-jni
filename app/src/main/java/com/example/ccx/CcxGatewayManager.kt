package com.example.ccx

import android.content.Context
import android.util.Log
import com.example.proxy.NativeProxyBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.HttpURLConnection
import java.net.URL

object CcxGatewayManager {
    private const val TAG = "CcxGatewayManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthCheckJob: Job? = null

    private val _channels = MutableStateFlow<List<CcxChannel>>(
        listOf(
            CcxChannel(
                id = "chan-ollama",
                name = "Local Ollama Node",
                protocol = AiProtocol.OPENAI_COMPATIBLE,
                targetUrl = "http://127.0.0.1:11434/v1/chat/completions",
                defaultModel = "llama3:latest",
                priority = 1,
                isEnabled = true
            ),
            CcxChannel(
                id = "chan-openai",
                name = "OpenAI Chat Upstream",
                protocol = AiProtocol.OPENAI_COMPATIBLE,
                targetUrl = "https://api.openai.com/v1/chat/completions",
                defaultModel = "gpt-4o",
                priority = 2,
                isEnabled = true
            ),
            CcxChannel(
                id = "chan-claude",
                name = "Anthropic Claude Upstream",
                protocol = AiProtocol.CLAUDE_MESSAGES,
                targetUrl = "https://api.anthropic.com/v1/messages",
                defaultModel = "claude-3-5-sonnet-20240620",
                priority = 3,
                isEnabled = true
            )
        )
    )
    val channels: StateFlow<List<CcxChannel>> = _channels.asStateFlow()

    private val _recentTranslations = MutableStateFlow<List<TranslationResult>>(emptyList())
    val recentTranslations: StateFlow<List<TranslationResult>> = _recentTranslations.asStateFlow()

    private val _stats = MutableStateFlow(
        CcxGatewayStats(
            isNativeJniLoaded = NativeProxyBridge.isNativeLibraryLoaded,
            isLocalDaemonRunning = false,
            daemonPort = 8095
        )
    )
    val stats: StateFlow<CcxGatewayStats> = _stats.asStateFlow()

    init {
        startPeriodicHealthChecks()
    }

    fun addChannel(
        name: String,
        protocol: AiProtocol,
        targetUrl: String,
        apiKey: String,
        defaultModel: String,
        priority: Int = 1
    ): CcxChannel {
        val newChannel = CcxChannel(
            name = name,
            protocol = protocol,
            targetUrl = targetUrl.trim(),
            apiKey = apiKey.trim(),
            defaultModel = defaultModel.trim(),
            priority = priority,
            isEnabled = true
        )
        _channels.update { it + newChannel }
        probeChannel(newChannel.id)
        updateStats()
        return newChannel
    }

    fun updateChannel(updated: CcxChannel) {
        _channels.update { list ->
            list.map { if (it.id == updated.id) updated else it }
        }
        updateStats()
    }

    fun removeChannel(id: String) {
        _channels.update { list -> list.filter { it.id != id } }
        updateStats()
    }

    fun toggleChannel(id: String) {
        _channels.update { list ->
            list.map {
                if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it
            }
        }
        updateStats()
    }

    fun probeChannel(channelId: String) {
        scope.launch {
            val channel = _channels.value.find { it.id == channelId } ?: return@launch
            val startMs = System.currentTimeMillis()
            var status = 0
            var online = false

            try {
                val url = URL(channel.targetUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "OPTIONS"
                if (channel.apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer ${channel.apiKey}")
                }
                status = conn.responseCode
                online = status in 200..499 // Responding
                conn.disconnect()
            } catch (e: Exception) {
                status = 0
                online = false
            }

            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

            _channels.update { list ->
                list.map {
                    if (it.id == channelId) {
                        it.copy(
                            latencyMs = latency,
                            lastStatus = status,
                            isOnline = online,
                            lastCheckedTimestamp = System.currentTimeMillis()
                        )
                    } else it
                }
            }
            updateStats()
        }
    }

    fun probeAllChannels() {
        _channels.value.forEach { probeChannel(it.id) }
    }

    fun executeTranslationAndForward(
        inputPayload: String,
        sourceProtocol: AiProtocol,
        targetProtocol: AiProtocol,
        targetModel: String? = null
    ): TranslationResult {
        val result = when {
            sourceProtocol == AiProtocol.CLAUDE_MESSAGES && targetProtocol == AiProtocol.OPENAI_COMPATIBLE -> {
                CcxProtocolTranslator.claudeToOpenAi(inputPayload, targetModel)
            }
            sourceProtocol == AiProtocol.OPENAI_COMPATIBLE && targetProtocol == AiProtocol.CLAUDE_MESSAGES -> {
                CcxProtocolTranslator.openAiToClaude(inputPayload, targetModel)
            }
            else -> {
                TranslationResult(
                    sourceProtocol = sourceProtocol,
                    targetProtocol = targetProtocol,
                    inputPayload = inputPayload,
                    translatedPayload = inputPayload,
                    executionTimeMs = 1L,
                    isSuccess = true
                )
            }
        }

        _recentTranslations.update { (listOf(result) + it).take(20) }
        updateStats()
        return result
    }

    private fun startPeriodicHealthChecks() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(60_000)
                probeAllChannels()
            }
        }
    }

    private fun updateStats() {
        val all = _channels.value
        val enabled = all.filter { it.isEnabled }
        val avgLatency = if (enabled.isNotEmpty()) {
            enabled.map { it.latencyMs }.average()
        } else 0.0

        _stats.update {
            it.copy(
                isNativeJniLoaded = NativeProxyBridge.isNativeLibraryLoaded,
                totalChannels = all.size,
                activeChannels = enabled.size,
                totalTranslations = _recentTranslations.value.size.toLong(),
                avgLatencyMs = avgLatency
            )
        }
    }

    fun setDaemonRunning(running: Boolean, port: Int) {
        _stats.update {
            it.copy(isLocalDaemonRunning = running, daemonPort = port)
        }
    }
}
