#include "native-proxy.h"
#include <android/log.h>
#include <cstring>
#include <map>
#include <mutex>
#include <thread>
#include <atomic>
#include <chrono>

#define TAG "NativeProxyEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;
static jclass g_callbackClass = nullptr;
static jmethodID g_midOnSseChunk = nullptr;
static jmethodID g_midOnWsFrame = nullptr;
static jmethodID g_midOnStatus = nullptr;

struct ActiveStream {
    jlong reqId;
    jint protocol;
    jobject globalCallback;
    std::atomic<bool> isRunning{true};
    std::thread workerThread;
};

static std::map<jlong, ActiveStream*> g_streams;
static std::mutex g_streamMutex;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad: Native Proxy Engine initialized successfully.");
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_example_proxy_NativeProxyBridge_nativeInit(JNIEnv *env, jobject thiz) {
    jclass cbClass = env->FindClass("com/example/proxy/ProxyJniCallback");
    if (!cbClass) {
        LOGE("Could not find class com/example/proxy/ProxyJniCallback");
        return -1;
    }
    g_callbackClass = reinterpret_cast<jclass>(env->NewGlobalRef(cbClass));
    g_midOnSseChunk = env->GetMethodID(g_callbackClass, "onSseChunk", "(JLjava/lang/String;[BZ)V");
    g_midOnWsFrame = env->GetMethodID(g_callbackClass, "onWsFrame", "(JIZ[B)V");
    g_midOnStatus = env->GetMethodID(g_callbackClass, "onStatus", "(JILjava/lang/String;)V");

    if (!g_midOnSseChunk || !g_midOnWsFrame || !g_midOnStatus) {
        LOGE("Failed to cache JNI callback method IDs");
        return -2;
    }
    LOGI("nativeInit completed with cached method IDs.");
    return 0;
}

static void dispatchWsFrame(JNIEnv *env, jobject cb, jlong connId, jint opcode, bool fin, const uint8_t *data, size_t len) {
    if (!cb || !g_midOnWsFrame) return;
    jbyteArray jBytes = env->NewByteArray(static_cast<jsize>(len));
    if (len > 0 && data != nullptr) {
        env->SetByteArrayRegion(jBytes, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(data));
    }
    env->CallVoidMethod(cb, g_midOnWsFrame, connId, opcode, static_cast<jboolean>(fin), jBytes);
    env->DeleteLocalRef(jBytes);
}

static void dispatchSseChunk(JNIEnv *env, jobject cb, jlong reqId, const char *event, const uint8_t *data, size_t len, bool isLast) {
    if (!cb || !g_midOnSseChunk) return;
    jstring jEvent = env->NewStringUTF(event ? event : "message");
    jbyteArray jBytes = env->NewByteArray(static_cast<jsize>(len));
    if (len > 0 && data != nullptr) {
        env->SetByteArrayRegion(jBytes, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(data));
    }
    env->CallVoidMethod(cb, g_midOnSseChunk, reqId, jEvent, jBytes, static_cast<jboolean>(isLast));
    env->DeleteLocalRef(jEvent);
    env->DeleteLocalRef(jBytes);
}

static void dispatchStatus(JNIEnv *env, jobject cb, jlong connId, jint statusCode, const char *msg) {
    if (!cb || !g_midOnStatus) return;
    jstring jMsg = env->NewStringUTF(msg ? msg : "");
    env->CallVoidMethod(cb, g_midOnStatus, connId, statusCode, jMsg);
    env->DeleteLocalRef(jMsg);
}

JNIEXPORT jint JNICALL
Java_com_example_proxy_NativeProxyBridge_proxyRequest(
        JNIEnv *env,
        jobject thiz,
        jlong reqId,
        jint protocol,
        jstring path,
        jobjectArray headers,
        jbyteArray payload,
        jobject callback) {

    if (!callback) {
        LOGE("proxyRequest: null callback provided.");
        return -1;
    }

    jobject globalCb = env->NewGlobalRef(callback);

    auto *stream = new ActiveStream();
    stream->reqId = reqId;
    stream->protocol = protocol;
    stream->globalCallback = globalCb;

    {
        std::lock_guard<std::mutex> lock(g_streamMutex);
        g_streams[reqId] = stream;
    }

    // Launch worker thread for asynchronous duplex framing & SSE pumping
    stream->workerThread = std::thread([stream]() {
        JNIEnv *workerEnv = nullptr;
        if (g_jvm->AttachCurrentThread(&workerEnv, nullptr) != JNI_OK) {
            LOGE("Failed to attach worker thread to JVM");
            return;
        }

        dispatchStatus(workerEnv, stream->globalCallback, stream->reqId, 200, "STREAM_CONNECTED");

        if (stream->protocol == PROTOCOL_HTTP_SSE) {
            // Stream chunked SSE events
            for (int i = 1; i <= 5 && stream->isRunning; i++) {
                std::string sseData = "{\"event_seq\":" + std::to_string(i) + ",\"status\":\"streaming\",\"timestamp\":" + std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count()) + "}";
                dispatchSseChunk(workerEnv, stream->globalCallback, stream->reqId, "telemetry",
                                 reinterpret_cast<const uint8_t*>(sseData.c_str()), sseData.length(), (i == 5));
                std::this_thread::sleep_for(std::chrono::milliseconds(500));
            }
        } else if (stream->protocol == PROTOCOL_WEBSOCKET) {
            // Send RFC 6455 Handshake ACK Frame
            std::string welcome = "{\"ws_session_ready\":true,\"connId\":" + std::to_string(stream->reqId) + "}";
            dispatchWsFrame(workerEnv, stream->globalCallback, stream->reqId, WS_OPCODE_TEXT, true,
                            reinterpret_cast<const uint8_t*>(welcome.c_str()), welcome.length());

            // Send Ping frame
            const uint8_t pingData[] = {0xDE, 0xAD, 0xBE, 0xEF};
            dispatchWsFrame(workerEnv, stream->globalCallback, stream->reqId, WS_OPCODE_PING, true, pingData, sizeof(pingData));
        } else if (stream->protocol == PROTOCOL_SOCKET_IO) {
            // Engine.IO Open handshake (packet '0')
            std::string openPkt = "0{\"sid\":\"inproc_" + std::to_string(stream->reqId) + "\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000}";
            dispatchWsFrame(workerEnv, stream->globalCallback, stream->reqId, WS_OPCODE_TEXT, true,
                            reinterpret_cast<const uint8_t*>(openPkt.c_str()), openPkt.length());

            // Socket.IO Connect ACK (packet '40')
            std::string connPkt = "40{\"sid\":\"inproc_" + std::to_string(stream->reqId) + "\"}";
            dispatchWsFrame(workerEnv, stream->globalCallback, stream->reqId, WS_OPCODE_TEXT, true,
                            reinterpret_cast<const uint8_t*>(connPkt.c_str()), connPkt.length());
        }

        g_jvm->DetachCurrentThread();
    });

    return 0; // Success
}

JNIEXPORT jint JNICALL
Java_com_example_proxy_NativeProxyBridge_closeStream(JNIEnv *env, jobject thiz, jlong reqId) {
    ActiveStream *stream = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_streamMutex);
        auto it = g_streams.find(reqId);
        if (it != g_streams.end()) {
            stream = it->second;
            g_streams.erase(it);
        }
    }

    if (stream) {
        stream->isRunning = false;
        if (stream->workerThread.joinable()) {
            stream->workerThread.join();
        }
        if (stream->globalCallback) {
            env->DeleteGlobalRef(stream->globalCallback);
        }
        delete stream;
    }
    return 0;
}
