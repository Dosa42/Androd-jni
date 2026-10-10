#ifndef NATIVE_PROXY_H
#define NATIVE_PROXY_H

#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>

#define PROTOCOL_HTTP_SSE     1
#define PROTOCOL_WEBSOCKET    2
#define PROTOCOL_SOCKET_IO    3

#define WS_OPCODE_CONTINUATION 0x0
#define WS_OPCODE_TEXT         0x1
#define WS_OPCODE_BINARY       0x2
#define WS_OPCODE_CLOSE        0x8
#define WS_OPCODE_PING         0x9
#define WS_OPCODE_PONG         0xA

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL
Java_com_example_proxy_NativeProxyBridge_nativeInit(
        JNIEnv *env,
        jobject thiz);

JNIEXPORT jint JNICALL
Java_com_example_proxy_NativeProxyBridge_proxyRequest(
        JNIEnv *env,
        jobject thiz,
        jlong reqId,
        jint protocol,
        jstring path,
        jobjectArray headers,
        jbyteArray payload,
        jobject callback);

JNIEXPORT jint JNICALL
Java_com_example_proxy_NativeProxyBridge_closeStream(
        JNIEnv *env,
        jobject thiz,
        jlong reqId);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_PROXY_H
