package com.example

import com.example.proxy.NativeProxyEngine
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testWebSocketFrameEncodingAndDecoding() {
        val message = "Hello Direct In-Process FFI"
        val payloadBytes = message.toByteArray(Charsets.UTF_8)

        // Encode as client-masked Text frame (opcode 0x1)
        val encodedBytes = NativeProxyEngine.encodeWsFrame(
            opcode = 0x1,
            payload = payloadBytes,
            fin = true,
            mask = true
        )

        // Validate frame is at least header + mask + payload length
        assertTrue(encodedBytes.size >= 2 + 4 + payloadBytes.size)

        // Decode frame
        val decoded = NativeProxyEngine.decodeWsFrame(encodedBytes)

        assertTrue(decoded.fin)
        assertEquals(0x1, decoded.opcode)
        assertEquals("TEXT", decoded.opcodeName)
        assertTrue(decoded.isMasked)
        assertEquals(payloadBytes.size.toLong(), decoded.payloadLength)

        val decodedText = String(decoded.payload, Charsets.UTF_8)
        assertEquals(message, decodedText)
    }

    @Test
    fun testWebSocketBinaryFrame() {
        val binaryData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xDE.toByte(), 0xAD.toByte())
        val encodedBytes = NativeProxyEngine.encodeWsFrame(
            opcode = 0x2,
            payload = binaryData,
            fin = true,
            mask = false
        )

        val decoded = NativeProxyEngine.decodeWsFrame(encodedBytes)
        assertEquals(0x2, decoded.opcode)
        assertEquals("BINARY", decoded.opcodeName)
        assertArrayEquals(binaryData, decoded.payload)
    }
}
