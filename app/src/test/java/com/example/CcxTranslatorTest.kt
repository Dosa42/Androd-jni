package com.example

import com.example.ccx.AiProtocol
import com.example.ccx.CcxGatewayManager
import com.example.ccx.CcxProtocolTranslator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CcxTranslatorTest {

    @Test
    fun testClaudeToOpenAiTranslation() {
        val claudeJson = """{
            "model": "claude-3-5-sonnet",
            "system": "System instructions here",
            "messages": [
                { "role": "user", "content": "Hello from user" }
            ],
            "max_tokens": 1024,
            "stream": true
        }"""

        val result = CcxProtocolTranslator.claudeToOpenAi(claudeJson)
        assertTrue("Translation must succeed", result.isSuccess)
        assertNotNull(result.translatedPayload)

        val openAiObj = JSONObject(result.translatedPayload)
        assertEquals("gpt-4o", openAiObj.getString("model"))
        assertTrue(openAiObj.getBoolean("stream"))
        assertEquals(1024, openAiObj.getInt("max_tokens"))

        val messages = openAiObj.getJSONArray("messages")
        assertEquals(2, messages.length()) // System + User
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("System instructions here", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Hello from user", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun testOpenAiToClaudeTranslation() {
        val openAiJson = """{
            "model": "gpt-4o",
            "messages": [
                { "role": "system", "content": "Assistant prompt" },
                { "role": "user", "content": "Can you check status?" }
            ],
            "max_tokens": 2048
        }"""

        val result = CcxProtocolTranslator.openAiToClaude(openAiJson)
        assertTrue("Translation must succeed", result.isSuccess)

        val claudeObj = JSONObject(result.translatedPayload)
        assertEquals("claude-3-5-sonnet", claudeObj.getString("model"))
        assertEquals("Assistant prompt", claudeObj.getString("system"))
        assertEquals(2048, claudeObj.getInt("max_tokens"))

        val messages = claudeObj.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("Can you check status?", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun testStreamingChunkTranslation() {
        val claudeDelta = """{
            "type": "content_block_delta",
            "index": 0,
            "delta": {
                "type": "text_delta",
                "text": "Hello world"
            }
        }"""

        val openAiChunk = CcxProtocolTranslator.translateStreamingChunk(
            chunkData = claudeDelta,
            fromProtocol = AiProtocol.CLAUDE_MESSAGES,
            toProtocol = AiProtocol.OPENAI_COMPATIBLE
        )

        val json = JSONObject(openAiChunk)
        assertTrue(json.has("choices"))
        val delta = json.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertEquals("Hello world", delta.getString("content"))
    }

    @Test
    fun testChannelManagement() {
        val countBefore = CcxGatewayManager.channels.value.size
        val added = CcxGatewayManager.addChannel(
            name = "Test Ollama",
            protocol = AiProtocol.OPENAI_COMPATIBLE,
            targetUrl = "http://127.0.0.1:11434/v1/chat/completions",
            apiKey = "",
            defaultModel = "mistral"
        )

        assertEquals(countBefore + 1, CcxGatewayManager.channels.value.size)
        assertTrue(added.isEnabled)

        CcxGatewayManager.toggleChannel(added.id)
        assertFalse(CcxGatewayManager.channels.value.first { it.id == added.id }.isEnabled)

        CcxGatewayManager.removeChannel(added.id)
        assertEquals(countBefore, CcxGatewayManager.channels.value.size)
    }
}
