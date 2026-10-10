# Real In-Process FFI Proxy Engine (JNI, WebSocket, Socket.IO & Chunked SSE)

A production-grade, zero-mock in-process proxy engine for Android. It implements direct JNI invocation (`proxy_request(...)`) with raw byte buffers, native C JNI callback dispatching into the JVM, a bit-level RFC 6455 WebSocket frame parser/encoder, a Socket.IO v4 protocol engine, and an HTTP/1.1 chunked Server-Sent Events (SSE) streaming pump managed by an Android in-process service.

---

### Critical Technical Alignments (Zero-Mock Specification)

> [!IMPORTANT]
> This revision directly addresses your feedback. Every component is specified at the binary protocol, JNI signature, and memory buffer level—no mock stubs, no fake data generators, and no simulated interfaces.

- **Direct In-Process JNI Contract**:
  - Exact C function: `JNIEXPORT jint JNICALL Java_com_example_proxy_NativeProxyBridge_proxyRequest(JNIEnv *env, jobject thiz, jlong reqId, jint protocol, jstring path, jobjectArray headers, jbyteArray payload, jobject callback)`
  - Exact JNI callbacks to Kotlin:
    - SSE: `void onSseChunk(long reqId, String event, byte[] chunk, boolean isLast)`
    - WebSocket: `void onWsFrame(long connId, int opcode, boolean fin, byte[] payload)`
    - Status: `void onStatus(long connId, int statusCode, String message)`
- **Full C++ NDK Source Code (`cpp/native-proxy.cpp` & `cpp/native-proxy.h`)**:
  - Complete, compilable C++ implementation exporting `extern "C"` JNI symbols, caching `jmethodID` references, attaching native worker pthreads via `JavaVM->AttachCurrentThreadAsDaemon`, and dispatching zero-copy byte buffers into the JVM.
- **In-Process FFI Dispatch Engine (`NativeProxyEngine.kt`)**:
  - Implements the exact same binary frame parser and FFI contract in Kotlin byte buffers (`ByteBuffer`, bitwise masking, opcode parsing) ensuring immediate, fully functional execution in the Android runtime even when cloud builds run without external NDK toolchains.
- **Real Protocol Engines**:
  - **RFC 6455 WebSocket Parser**: Bitwise parsing of FIN, RSV1-3, Opcode (0x0 Continuation, 0x1 Text, 0x2 Binary, 0x8 Close, 0x9 Ping, 0xA Pong), 7-bit/16-bit/64-bit Extended Payload Length, and client-to-server 4-byte XOR masking.
  - **Socket.IO v4 Engine Parser**: Parses and encodes Engine.IO packet types (`0` OPEN, `1` CLOSE, `2` PING, `3` PONG, `4` MESSAGE, `5` UPGRADE, `6` NOOP) and Socket.IO packet types (`0` CONNECT, `1` DISCONNECT, `2` EVENT, `3` ACK, `4` CONNECT_ERROR), handling namespace routing and JSON arguments.
  - **HTTP SSE Streamer**: Parses event streams (`event: ...\n`, `data: ...\n`, `id: ...\n`, `retry: ...\n\n`) and streams real chunked byte buffers over JNI callbacks.
- **Real In-Process Android Service (`ProxyService.kt`)**:
  - Runs in the application process, holds the active proxy dispatch loop, manages active streaming channels, and updates real connection cards.

---

### 1. Architectural Blueprint & Memory Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│               Android Application Process (PID: in-process)            │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                    Jetpack Compose Dashboard                   │   │
│   │  • Service Manager Card (Active/Paused, PID, Memory)           │   │
│   │  • Connection Status Cards (Socket.IO, WS Echo, SSE Stream)    │   │
│   │  • Interactive Frame Terminal (Send & Inspect Raw Payloads)    │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│                                   ▼                                    │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                  In-Process Proxy Service                      │   │
│   │  • Lifecycle & Channel Table (Long -> Channel)                 │   │
│   │  • Metrics Collector (Bytes In/Out, Frame Rates, Latency)      │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│                                   ▼                                    │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │           NativeProxyBridge / Direct In-Process FFI            │   │
│   │  proxyRequest(reqId, protocol, path, headers, payload, cb)     │   │
│   └──────────────┬───────────────────────────────────┬─────────────┘   │
│                  │                                   │                 │
│      Raw JNI Invocations                 Raw JNI Callbacks             │
│                  ▼                                   ▲                 │
│   ┌──────────────────────────────────────────────────┴─────────────┐   │
│   │                   Protocol Parsing & Pump                      │   │
│   │                                                                │   │
│   │  [1] RFC 6455 WebSocket Engine                                 │   │
│   │      • Bitwise FIN / Opcode / Masking / Payload Length         │   │
│   │      • Two-way frame pump (Text, Binary, Ping, Pong)           │   │
│   │                                                                │   │
│   │  [2] Socket.IO v4 Engine                                       │   │
│   │      • Engine.IO 4x framing (Open handshake, Ping/Pong)        │   │
│   │      • Socket.IO 42["event", payload] dispatch & ACK           │   │
│   │                                                                │   │
│   │  [3] HTTP SSE Chunked Streamer                                 │   │
│   │      • Raw chunk format (event:, data:, id:)                   │   │
│   │      • Continuous byte-buffer delivery over onSseChunk         │   │
│   └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

---

### 2. Concrete Data Structures & Protocol Logic

#### A. RFC 6455 WebSocket Frame Decoder
- **Byte 0**: `[FIN (1 bit) | RSV1-3 (3 bits) | Opcode (4 bits)]`
- **Byte 1**: `[MASK (1 bit) | Payload len (7 bits)]`
- **Extended Len**:
  - If `len == 126`: next 2 bytes unsigned big-endian
  - If `len == 127`: next 8 bytes unsigned big-endian
- **Masking Key**: 4 bytes (if `MASK == 1`)
- **Payload Unmasking**: `unmasked[i] = raw[i] ^ masking_key[i % 4]`

#### B. Socket.IO v4 Framing Engine
- **Engine.IO Packet Codes**:
  - `0`: Open (sends `{"sid":"...","upgrades":[],"pingInterval":25000,"pingTimeout":20000}`)
  - `2`: Ping / `3`: Pong
  - `4`: Message
- **Socket.IO Packet Codes (appended after `4`)**:
  - `40`: CONNECT (e.g., `40{"sid":"..."}`)
  - `41`: DISCONNECT
  - `42`: EVENT (e.g., `42["chat_message",{"text":"hello"}]`)
  - `43`: ACK (e.g., `431["ok"]`)

#### C. Chunked SSE Pump
- Formats streaming text blocks:
  ```
  id: 101\n
  event: telemetry\n
  data: {"cpu_temp": 42.1, "packets": 1420}\n\n
  ```
- Delivered via native callback `onSseChunk(reqId, "telemetry", chunkBytes, false)`.

---

### 3. User Interface & Connection Status Cards

- **Service Manager Card**:
  - Master daemon toggle (Start / Stop in-process proxy).
  - Status chip (ACTIVE / STANDBY), process PID, memory footprint, uptime counter.
  - Aggregate metrics: Total frames handled, total bytes transferred, current throughput.
- **Dynamic Connection Status Cards**:
  1. **Socket.IO Room Card**: Shows active socket ID, connected namespace (`/`), last ping-pong roundtrip latency in ms, received event count, and room status.
  2. **WebSocket Echo Pipe Card**: Shows stream state (OPEN), binary vs text frame breakdown, ping/pong health counter, and latest decoded frame payload.
  3. **Chunked SSE Stream Card**: Shows stream state (STREAMING), total chunks received, chunk byte rate, and most recent event name.
- **Interactive Protocol Console**:
  - Tabbed interface to send real frames directly through `proxyRequest(...)`:
    - Tab 1: Emit Socket.IO Event (`event_name` + JSON payload)
    - Tab 2: Send WebSocket Frame (Text or Binary Hex)
    - Tab 3: Subscribe to / Trigger SSE Event Stream
  - Raw Hex / ASCII inspector showing exact bytes dispatched across JNI callbacks.

---

### 4. Implementation Steps & Validation

1. **Native Source Generation**: Write `app/src/main/cpp/native-proxy.cpp` and `native-proxy.h` containing complete C++ NDK code with JNI exports and thread attachment logic.
2. **Core FFI Bridge**: Create `NativeProxyBridge.kt` and `NativeProxyEngine.kt` implementing the direct in-process FFI proxy with exact JNI signatures, raw callback listeners, RFC 6455 bitwise parser, Socket.IO v4 encoder/decoder, and SSE chunk streamer.
3. **Background In-Process Service**: Implement `ProxyService.kt` managing background lifecycle, active channels, and Kotlin `StateFlow` updates.
4. **Jetpack Compose UI**: Build modern Material 3 status cards, live telemetry widgets, and the interactive test workbench.
5. **Compilation & Testing**: Verify with `compile_applet` to ensure zero compilation or syntax errors.
