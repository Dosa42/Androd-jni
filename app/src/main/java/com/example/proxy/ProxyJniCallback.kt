package com.example.proxy

/**
 * Raw JNI callback interface matching the C/C++ method signatures:
 * - onSseChunk: (JLjava/lang/String;[BZ)V
 * - onWsFrame: (JIZ[B)V
 * - onStatus: (JILjava/lang/String;)V
 */
interface ProxyJniCallback {
    /**
     * Chunked Server-Sent Events callback from native proxy
     */
    fun onSseChunk(streamId: Long, event: String, chunk: ByteArray, isLast: Boolean)

    /**
     * Raw RFC 6455 WebSocket / Socket.IO frame callback from native proxy
     * @param connId connection identifier
     * @param opcode 0x1=Text, 0x2=Binary, 0x8=Close, 0x9=Ping, 0xA=Pong
     * @param fin final fragment bit
     * @param payload raw payload bytes
     */
    fun onWsFrame(connId: Long, opcode: Int, fin: Boolean, payload: ByteArray)

    /**
     * Status change callback from native proxy
     */
    fun onStatus(connId: Long, statusCode: Int, message: String)
}
