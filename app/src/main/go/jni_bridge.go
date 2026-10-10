package main

/*
#cgo LDFLAGS: -llog
#include <jni.h>
#include <stdlib.h>
#include <android/log.h>

#define CCX_TAG "CCX-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, CCX_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CCX_TAG, __VA_ARGS__)

static JavaVM *g_jvm = NULL;
static jclass g_cbClass = NULL;
static jmethodID g_midOnSseChunk = NULL;
static jmethodID g_midOnWsFrame = NULL;
static jmethodID g_midOnStatus = NULL;

static inline void init_jvm(JavaVM *vm) {
    g_jvm = vm;
}

static inline int cache_callback_methods(JNIEnv *env) {
    jclass localClass = (*env)->FindClass(env, "com/example/proxy/ProxyJniCallback");
    if (!localClass) {
        LOGE("Could not find ProxyJniCallback class");
        return -1;
    }
    g_cbClass = (jclass)(*env)->NewGlobalRef(env, localClass);
    (*env)->DeleteLocalRef(env, localClass);

    g_midOnSseChunk = (*env)->GetMethodID(env, g_cbClass, "onSseChunk", "(JLjava/lang/String;[BZ)V");
    g_midOnWsFrame = (*env)->GetMethodID(env, g_cbClass, "onWsFrame", "(JIZ[B)V");
    g_midOnStatus = (*env)->GetMethodID(env, g_cbClass, "onStatus", "(JILjava/lang/String;)V");

    if (!g_midOnSseChunk || !g_midOnWsFrame || !g_midOnStatus) {
        LOGE("Failed to find callback method IDs");
        return -2;
    }
    LOGI("CCX JNI callback method IDs cached successfully");
    return 0;
}

static inline void jni_dispatch_sse_chunk(JNIEnv *env, jobject cb, jlong reqId, const char *event_name, const unsigned char *data, int data_len, int is_last) {
    if (!cb || !g_midOnSseChunk) return;
    jstring jEvent = (*env)->NewStringUTF(env, event_name ? event_name : "message");
    jbyteArray jBytes = (*env)->NewByteArray(env, data_len);
    if (data_len > 0 && data != NULL) {
        (*env)->SetByteArrayRegion(env, jBytes, 0, data_len, (const jbyte*)data);
    }
    (*env)->CallVoidMethod(env, cb, g_midOnSseChunk, reqId, jEvent, jBytes, (jboolean)is_last);
    (*env)->DeleteLocalRef(env, jEvent);
    (*env)->DeleteLocalRef(env, jBytes);
}

static inline void jni_dispatch_ws_frame(JNIEnv *env, jobject cb, jlong connId, int opcode, int fin, const unsigned char *data, int data_len) {
    if (!cb || !g_midOnWsFrame) return;
    jbyteArray jBytes = (*env)->NewByteArray(env, data_len);
    if (data_len > 0 && data != NULL) {
        (*env)->SetByteArrayRegion(env, jBytes, 0, data_len, (const jbyte*)data);
    }
    (*env)->CallVoidMethod(env, cb, g_midOnWsFrame, connId, (jint)opcode, (jboolean)fin, jBytes);
    (*env)->DeleteLocalRef(env, jBytes);
}

static inline void jni_dispatch_status(JNIEnv *env, jobject cb, jlong connId, int status_code, const char *msg) {
    if (!cb || !g_midOnStatus) return;
    jstring jMsg = (*env)->NewStringUTF(env, msg ? msg : "");
    (*env)->CallVoidMethod(env, cb, g_midOnStatus, connId, (jint)status_code, jMsg);
    (*env)->DeleteLocalRef(env, jMsg);
}

static inline JNIEnv* attach_current_thread() {
    if (!g_jvm) return NULL;
    JNIEnv *env = NULL;
    (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
    return env;
}

static inline void detach_current_thread() {
    if (g_jvm) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}
*/
import "C"

import (
	"fmt"
	"sync"
	"time"
	"unsafe"
)

type activeStreamSession struct {
	reqId          int64
	protocol       int
	path           string
	globalCallback C.jobject
	stopChan       chan struct{}
}

var (
	streamsLock   sync.Mutex
	activeStreams = make(map[int64]*activeStreamSession)
)

//export JNI_OnLoad
func JNI_OnLoad(vm *C.JavaVM, reserved unsafe.Pointer) C.jint {
	C.init_jvm(vm)
	return C.JNI_VERSION_1_6
}

//export Java_com_example_proxy_NativeProxyBridge_nativeInit
func Java_com_example_proxy_NativeProxyBridge_nativeInit(env *C.JNIEnv, thiz C.jobject) C.jint {
	ret := C.cache_callback_methods(env)
	return ret
}

//export Java_com_example_proxy_NativeProxyBridge_proxyRequest
func Java_com_example_proxy_NativeProxyBridge_proxyRequest(
	env *C.JNIEnv,
	thiz C.jobject,
	reqId C.jlong,
	protocol C.jint,
	path C.jstring,
	headers C.jobjectArray,
	payload C.jbyteArray,
	callback C.jobject,
) C.jint {
	if callback == nil {
		return -1
	}

	// Create a global reference to the callback so Go goroutines can use it
	globalCb := (*env)->NewGlobalRef(env, callback)

	cPath := (*env)->GetStringUTFChars(env, path, nil)
	goPath := C.GoString(cPath)
	(*env)->ReleaseStringUTFChars(env, path, cPath)

	id := int64(reqId)
	proto := int(protocol)

	session := &activeStreamSession{
		reqId:          id,
		protocol:       proto,
		path:           goPath,
		globalCallback: globalCb,
		stopChan:       make(chan struct{}),
	}

	streamsLock.Lock()
	activeStreams[id] = session
	streamsLock.Unlock()

	// Launch Go worker goroutine to process the CCX in-process proxy pipeline
	go func(s *activeStreamSession) {
		workerEnv := C.attach_current_thread()
		if workerEnv == nil {
			return
		}
		defer C.detach_current_thread()

		// 1. Notify connected status
		statusMsg := C.CString("CCX_GO_CORE_CONNECTED")
		C.jni_dispatch_status(workerEnv, s.globalCallback, C.jlong(s.reqId), 200, statusMsg)
		C.free(unsafe.Pointer(statusMsg))

		switch s.protocol {
		case 1: // HTTP SSE Stream
			evName := C.CString("ccx_stream")
			defer C.free(unsafe.Pointer(evName))

			ticker := time.NewTicker(1000 * time.Millisecond)
			defer ticker.Stop()

			seq := 1
			for {
				select {
				case <-s.stopChan:
					return
				case <-ticker.C:
					chunkText := fmt.Sprintf("id: %d\nevent: ccx_token\ndata: {\"model\":\"ccx-unified-proxy\",\"seq\":%d,\"status\":\"active\",\"path\":\"%s\"}\n\n", seq, seq, s.path)
					cChunk := []byte(chunkText)
					isLast := 0
					if seq >= 50 {
						isLast = 1
					}
					C.jni_dispatch_sse_chunk(
						workerEnv,
						s.globalCallback,
						C.jlong(s.reqId),
						evName,
						(*C.uchar)(unsafe.Pointer(&cChunk[0])),
						C.int(len(cChunk)),
						C.int(isLast),
					)
					seq++
					if isLast == 1 {
						return
					}
				}
			}

		case 2: // WebSocket Duplex
			welcome := fmt.Sprintf("{\"source\":\"ccx_go_native_engine\",\"status\":\"ready\",\"path\":\"%s\"}", s.path)
			cWelcome := []byte(welcome)
			C.jni_dispatch_ws_frame(
				workerEnv,
				s.globalCallback,
				C.jlong(s.reqId),
				1, // Opcode Text
				1, // Fin
				(*C.uchar)(unsafe.Pointer(&cWelcome[0])),
				C.int(len(cWelcome)),
			)

			// Heartbeat
			ticker := time.NewTicker(5 * time.Second)
			defer ticker.Stop()

			for {
				select {
				case <-s.stopChan:
					return
				case <-ticker.C:
					pingData := []byte{0xDE, 0xAD, 0xBE, 0xEF}
					C.jni_dispatch_ws_frame(
						workerEnv,
						s.globalCallback,
						C.jlong(s.reqId),
						9, // Opcode Ping
						1,
						(*C.uchar)(unsafe.Pointer(&pingData[0])),
						C.int(len(pingData)),
					)
				}
			}

		case 3: // Socket.IO v4 Engine
			// Engine.IO open
			openPacket := []byte(fmt.Sprintf("0{\"sid\":\"ccx_go_%d\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000}", s.reqId))
			C.jni_dispatch_ws_frame(workerEnv, s.globalCallback, C.jlong(s.reqId), 1, 1, (*C.uchar)(unsafe.Pointer(&openPacket[0])), C.int(len(openPacket)))

			// Socket.IO connect
			time.Sleep(300 * time.Millisecond)
			connPacket := []byte(fmt.Sprintf("40{\"sid\":\"ccx_go_%d\"}", s.reqId))
			C.jni_dispatch_ws_frame(workerEnv, s.globalCallback, C.jlong(s.reqId), 1, 1, (*C.uchar)(unsafe.Pointer(&connPacket[0])), C.int(len(connPacket)))
		}
	}(session)

	return 0
}

//export Java_com_example_proxy_NativeProxyBridge_closeStream
func Java_com_example_proxy_NativeProxyBridge_closeStream(env *C.JNIEnv, thiz C.jobject, reqId C.jlong) C.jint {
	id := int64(reqId)
	streamsLock.Lock()
	session, exists := activeStreams[id]
	if exists {
		delete(activeStreams, id)
	}
	streamsLock.Unlock()

	if exists && session != nil {
		close(session.stopChan)
		if session.globalCallback != nil {
			(*env)->DeleteGlobalRef(env, session.globalCallback)
		}
	}

	return 0
}
