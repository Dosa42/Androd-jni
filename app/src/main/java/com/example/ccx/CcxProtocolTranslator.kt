package com.example.ccx

import org.json.JSONArray
import org.json.JSONObject

object CcxProtocolTranslator {

    fun claudeToOpenAi(claudePayload: String, targetModelOverride: String? = null): TranslationResult {
        val startMs = System.currentTimeMillis()
        return try {
            val claudeJson = JSONObject(claudePayload)
            val openAiJson = JSONObject()

            val model = targetModelOverride ?: when (val m = claudeJson.optString("model", "default")) {
                "claude-3-5-sonnet", "claude-3-opus", "claude-3-sonnet" -> "gpt-4o"
                "claude-3-haiku" -> "gpt-4o-mini"
                else -> m
            }
            openAiJson.put("model", model)

            val openAiMessages = JSONArray()

            // Extract system instruction if present in Claude payload
            val systemPrompt = claudeJson.optString("system", "")
            if (systemPrompt.isNotBlank()) {
                val sysMsg = JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                openAiMessages.put(sysMsg)
            }

            // Convert messages array
            val claudeMessages = claudeJson.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until claudeMessages.length()) {
                val cMsg = claudeMessages.getJSONObject(i)
                val role = cMsg.optString("role", "user")
                val contentObj = cMsg.opt("content")

                val normalizedContent = when (contentObj) {
                    is String -> contentObj
                    is JSONArray -> {
                        // Extract text parts from Claude block content
                        val sb = StringBuilder()
                        for (j in 0 until contentObj.length()) {
                            val part = contentObj.optJSONObject(j)
                            if (part != null && part.optString("type") == "text") {
                                sb.append(part.optString("text"))
                            }
                        }
                        sb.toString()
                    }
                    else -> contentObj?.toString() ?: ""
                }

                val oMsg = JSONObject().apply {
                    put("role", role)
                    put("content", normalizedContent)
                }
                openAiMessages.put(oMsg)
            }

            openAiJson.put("messages", openAiMessages)

            if (claudeJson.has("max_tokens")) {
                openAiJson.put("max_tokens", claudeJson.getInt("max_tokens"))
            }
            if (claudeJson.has("temperature")) {
                openAiJson.put("temperature", claudeJson.getDouble("temperature"))
            }
            if (claudeJson.has("stream")) {
                openAiJson.put("stream", claudeJson.getBoolean("stream"))
            }

            val elapsed = System.currentTimeMillis() - startMs
            TranslationResult(
                sourceProtocol = AiProtocol.CLAUDE_MESSAGES,
                targetProtocol = AiProtocol.OPENAI_COMPATIBLE,
                inputPayload = claudePayload,
                translatedPayload = openAiJson.toString(2),
                executionTimeMs = elapsed,
                isSuccess = true
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            TranslationResult(
                sourceProtocol = AiProtocol.CLAUDE_MESSAGES,
                targetProtocol = AiProtocol.OPENAI_COMPATIBLE,
                inputPayload = claudePayload,
                translatedPayload = "",
                executionTimeMs = elapsed,
                isSuccess = false,
                errorMessage = e.message ?: "Failed to translate Claude to OpenAI"
            )
        }
    }

    fun openAiToClaude(openAiPayload: String, targetModelOverride: String? = null): TranslationResult {
        val startMs = System.currentTimeMillis()
        return try {
            val openAiJson = JSONObject(openAiPayload)
            val claudeJson = JSONObject()

            val model = targetModelOverride ?: when (val m = openAiJson.optString("model", "default")) {
                "gpt-4o", "gpt-4-turbo", "gpt-4" -> "claude-3-5-sonnet"
                "gpt-4o-mini", "gpt-3.5-turbo" -> "claude-3-haiku"
                else -> m
            }
            claudeJson.put("model", model)

            val openAiMessages = openAiJson.optJSONArray("messages") ?: JSONArray()
            val claudeMessages = JSONArray()
            var extractedSystemPrompt = ""

            for (i in 0 until openAiMessages.length()) {
                val oMsg = openAiMessages.getJSONObject(i)
                val role = oMsg.optString("role", "user")
                val content = oMsg.optString("content", "")

                if (role == "system") {
                    extractedSystemPrompt = if (extractedSystemPrompt.isEmpty()) content else "$extractedSystemPrompt\n$content"
                } else {
                    val cMsg = JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    }
                    claudeMessages.put(cMsg)
                }
            }

            if (extractedSystemPrompt.isNotBlank()) {
                claudeJson.put("system", extractedSystemPrompt)
            }

            claudeJson.put("messages", claudeMessages)

            val maxTokens = if (openAiJson.has("max_tokens")) openAiJson.getInt("max_tokens") else 4096
            claudeJson.put("max_tokens", maxTokens)

            if (openAiJson.has("temperature")) {
                claudeJson.put("temperature", openAiJson.getDouble("temperature"))
            }
            if (openAiJson.has("stream")) {
                claudeJson.put("stream", openAiJson.getBoolean("stream"))
            }

            val elapsed = System.currentTimeMillis() - startMs
            TranslationResult(
                sourceProtocol = AiProtocol.OPENAI_COMPATIBLE,
                targetProtocol = AiProtocol.CLAUDE_MESSAGES,
                inputPayload = openAiPayload,
                translatedPayload = claudeJson.toString(2),
                executionTimeMs = elapsed,
                isSuccess = true
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            TranslationResult(
                sourceProtocol = AiProtocol.OPENAI_COMPATIBLE,
                targetProtocol = AiProtocol.CLAUDE_MESSAGES,
                inputPayload = openAiPayload,
                translatedPayload = "",
                executionTimeMs = elapsed,
                isSuccess = false,
                errorMessage = e.message ?: "Failed to translate OpenAI to Claude"
            )
        }
    }

    fun translateStreamingChunk(
        chunkData: String,
        fromProtocol: AiProtocol,
        toProtocol: AiProtocol
    ): String {
        if (fromProtocol == toProtocol) return chunkData

        return try {
            val json = JSONObject(chunkData)

            if (fromProtocol == AiProtocol.OPENAI_COMPATIBLE && toProtocol == AiProtocol.CLAUDE_MESSAGES) {
                // OpenAI chunk -> Claude content_block_delta
                val choices = json.optJSONArray("choices")
                val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
                val text = delta?.optString("content", "") ?: ""
                if (text.isNotEmpty()) {
                    JSONObject().apply {
                        put("type", "content_block_delta")
                        put("index", 0)
                        put("delta", JSONObject().apply {
                            put("type", "text_delta")
                            put("text", text)
                        })
                    }.toString()
                } else {
                    chunkData
                }
            } else if (fromProtocol == AiProtocol.CLAUDE_MESSAGES && toProtocol == AiProtocol.OPENAI_COMPATIBLE) {
                // Claude chunk -> OpenAI choices[0].delta.content
                val type = json.optString("type", "")
                if (type == "content_block_delta") {
                    val delta = json.optJSONObject("delta")
                    val text = delta?.optString("text", "") ?: ""
                    JSONObject().apply {
                        put("choices", JSONArray().apply {
                            put(JSONObject().apply {
                                put("index", 0)
                                put("delta", JSONObject().apply {
                                    put("content", text)
                                })
                            })
                        })
                    }.toString()
                } else {
                    chunkData
                }
            } else {
                chunkData
            }
        } catch (_: Exception) {
            chunkData
        }
    }
}
