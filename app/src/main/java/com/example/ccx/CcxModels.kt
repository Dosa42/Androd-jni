package com.example.ccx

import java.util.UUID

enum class AiProtocol(val displayName: String, val defaultEndpoint: String) {
    OPENAI_COMPATIBLE("OpenAI Chat API", "/v1/chat/completions"),
    CLAUDE_MESSAGES("Claude Messages API", "/v1/messages"),
    CUSTOM_REST("Custom REST API", "/api/generate")
}

data class CcxChannel(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val protocol: AiProtocol,
    val targetUrl: String,
    val apiKey: String = "",
    val defaultModel: String = "default-model",
    val isEnabled: Boolean = true,
    val priority: Int = 1,
    val latencyMs: Long = 0L,
    val totalRequests: Long = 0L,
    val lastStatus: Int = 200,
    val isOnline: Boolean = true,
    val lastCheckedTimestamp: Long = 0L
)

data class TranslationResult(
    val id: String = UUID.randomUUID().toString().take(8),
    val sourceProtocol: AiProtocol,
    val targetProtocol: AiProtocol,
    val inputPayload: String,
    val translatedPayload: String,
    val executionTimeMs: Long,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

data class CcxGatewayStats(
    val isNativeJniLoaded: Boolean,
    val isLocalDaemonRunning: Boolean,
    val daemonPort: Int = 8095,
    val totalChannels: Int = 0,
    val activeChannels: Int = 0,
    val totalTranslations: Long = 0L,
    val avgLatencyMs: Double = 0.0
)
