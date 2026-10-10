# Real Native Ngrok Android Tunnel Engine Integration (Zero Mock)

Architectural blueprint for adopting the authentic native Ngrok Android JNI engine into our In-Process FFI Proxy app, enforcing 100% real native socket execution with zero mock data, zero simulation, and zero synthetic fallbacks.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Strict Zero-Mock Enforcement**:
> All concepts of mock forwarders, simulated fallback, or synthetic dummy responses have been completely removed. If native libraries fail to load or the authtoken is rejected by Ngrok's servers, the application will fail fast, propagate the exact raw native error (`TunnelState.Error`), and display full diagnostic stack traces directly in the UI. No fake states will ever be synthesized.

- **Confirmed Decision (Concurrent Dual Routing)**: The app runs authentic public Ngrok ingress traffic and in-process FFI streams concurrently. External requests hitting the public Ngrok tunnel flow directly through the live companion server, while in-process FFI streams operate simultaneously.
- **Confirmed Decision (Dashboard Presentation)**: Integrated as a dedicated **Ngrok Tunnel Telemetry Card** directly on the main dashboard, featuring live public URLs, real-time byte and request counters, authentic connection states, and raw error logs.
- **Confirmed Decision (Smart Configurable Dynamic Port)**: The companion HTTP server binds to a real TCP `ServerSocket` starting at port `8085`, with automatic collision detection and user-customizable port configuration.

---

## 1. Overview & Core Concept

### Authentic Native Ingress
This integration embeds the real Ngrok native C/Rust JNI runtime (`com.ngrok:ngrok-java` + `libngrok_java.so`) into the Android application process:
1. **Real Native Session**: Calls authentic `com.ngrok.Session.withAuthtoken(token).connect()` over TLS directly to Ngrok's global edge servers.
2. **Real Native Forwarder**: Executes `session.httpEndpoint().forward(URL("http://127.0.0.1:$port"))` to bind public internet traffic to the device's local TCP port.
3. **Real In-App Socket Server**: Spawns an actual `java.net.ServerSocket` handling authentic HTTP request/response lifecycles, parsing real incoming client headers, query parameters, and byte streams.
4. **Real Device Telemetry & Endpoints**: Directly queries Android system hardware (`BatteryManager`, `NetworkCapabilities`, `StatFs`, `Runtime.getRuntime()`) and serves real diagnostic data at `/api/phone/status`, `/status`, and `/ping`.
5. **Persistent Daemon Execution**: Keeps the tunnel alive via `NgrokTunnelService` using an Android Foreground Service with an active `PowerManager.PARTIAL_WAKE_LOCK`.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Authtoken Entry & Port Configuration**:
   - The user inputs their authentic Ngrok authtoken obtained from `dashboard.ngrok.com`.
   - The token is securely stored in local `SharedPreferences` via `NgrokConfig`.
2. **Tunnel Launch & Real State Progression**:
   - Tapping **"Start Ngrok Tunnel"** launches `NgrokTunnelService`.
   - Live status transitions through authentic states:
     - `Initializing native Ngrok JNI runtime (libngrok_java.so)...`
     - `Binding local TCP ServerSocket on port $port...`
     - `Connecting native Ngrok session to edge servers...`
     - `Tunnel Connected: https://<subdomain>.ngrok-free.app`
3. **Hard Failure Reporting (No Fake Fallbacks)**:
   - If the token is invalid, network drops, or JNI symbols fail, the engine immediately sets `TunnelState.Error` displaying the exact raw exception message (e.g. `ERR_NGROK_108: Invalid authtoken` or `UnsatisfiedLinkError`) with a retry button.
4. **Live Traffic Inspection**:
   - Every HTTP request arriving over the real Ngrok tunnel is logged in real time with actual HTTP verbs (`GET`, `POST`), target paths, HTTP status codes, client IPs, and timing down to the millisecond.

### Visual Identity & Theme
- **Theme**: Seamless fit into the dark cyber-telemetry design system (`DarkBackground #0A0F16`, `DarkSurfaceElevated #172230`, `DarkCardBorder #223548`).
- **Accent Tokens**: Ngrok Blue (`#0066F5`), Cyan (`#38BDF8`), Active Emerald (`#00E676`), and Error Crimson (`#FF1744`).
- **Telemetry Display**: Displays active uptime, real total request count, local port, public URL with 1-click clipboard copy, and live ping response latency.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Real Native JNI Extraction
- **Chosen Approach**: Configure Gradle build tasks (`ExtractNgrokJni` and `ngrokNativeClasses`) to unpack `libngrok_java.so` from the official artifacts (`com.ngrok:ngrok-java-native:1.0.0`) for `arm64-v8a` and `armeabi-v7a`.
- **Why**: Ensures genuine JNI binaries are bundled into the APK, enabling the official C/Rust core to manage encrypted TLS tunnels.
- **Fail-Fast Policy**: If the native library cannot be loaded on an incompatible architecture, throw the raw `UnsatisfiedLinkError` directly into the UI state so the developer sees the genuine native fault.

### Decision 2: Real ServerSocket Companion Implementation
- **Chosen Approach**: Use standard Java `ServerSocket` and `Socket` streams to handle HTTP connections on localhost without third-party web frameworks.
- **Why**: Pure, zero-dependency, transparent socket programming where every byte read and written is visible, verifiable, and logged.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Android Application Process                     │
│                                                                        │
│   Jetpack Compose UI (ProxyDashboardScreen)                            │
│   ├── ServiceManagerCard (In-Process FFI Daemon)                       │
│   ├── NgrokTunnelCard (Real Public Ingress Telemetry & Controls)       │
│   ├── ConnectionStatusCards (WebSocket / Socket.IO / SSE)              │
│   └── FrameInspectorView (Live Raw Hex Dump)                           │
│                         │                                              │
│                         ▼                                              │
│   NgrokTunnelManager (StateFlow<TunnelState>)                          │
│   ├── NgrokTunnelService (Foreground Service + Partial WakeLock)       │
│   ├── com.ngrok.Runtime (Loads libngrok_java.so via System.loadLibrary)│
│   ├── com.ngrok.Session (Real TLS connection to Ngrok Edge)            │
│   │       └── forward(URL("http://127.0.0.1:$port"))                   │
│   └── EmbeddedHttpServer (Real TCP ServerSocket listening on $port)    │
│           ├── /api/phone/status (Actual Hardware Telemetry)            │
│           ├── /proxy/forward (Real Reverse Proxy Forwarding)           │
│           ├── /Proxy-Redirect.user.js (Userscript Distribution)        │
│           └── /ping & /status (Health checks)                          │
│                         │                                              │
│                         ▼                                              │
│   NativeProxyBridge.invokeProxyRequest(...)                            │
│   ├── Direct In-Process FFI Engine                                     │
│   └── External Remote Upstream Bridge (OkHttp)                         │
└────────────────────────────────────────────────────────────────────────┘
                                ▲
                                │ Real TLS Public Ingress
                                │ https://*.ngrok-free.app
                                ▼
                   Global Internet / Web Clients
```

### Source File Roadmap

1. **Gradle Build Configuration (`app/build.gradle.kts`)**:
   - Add `com.ngrok:ngrok-java:1.0.0` and native classifiers (`linux-android-aarch_64`, `linux-android-armv7`).
   - Add `ExtractNgrokJni` and `ngrokNativeClasses` tasks.
   - Enable `coreLibraryDesugaring` with `desugar_jdk_libs:2.1.5`.
2. **Native Loader (`app/src/main/java/com/ngrok/Runtime.java`)**:
   - Real JNI loader executing `System.loadLibrary("ngrok_java")` and `native init(logger)`.
3. **Tunnel Domain & Manager (`app/src/main/java/com/example/tunnel/`)**:
   - `TunnelState.kt`: Strict sealed state model (`Disconnected`, `Connecting`, `Connected`, `Stopping`, `Error`).
   - `NgrokConfig.kt`: SharedPreferences manager for authtoken, port, and boot start.
   - `NgrokTunnelService.kt`: Android Foreground Service with ongoing notification and wakelock.
   - `NgrokTunnelManager.kt`: Mutex-guarded lifecycle calling real `Session.withAuthtoken(token).connect()`.
   - `EmbeddedHttpServer.kt`: Real TCP `ServerSocket` dispatcher serving real routes.
   - `BootReceiver.kt`: Broadcast receiver for `BOOT_COMPLETED`.
4. **UI Component (`app/src/main/java/com/example/ui/screens/NgrokTunnelCard.kt`)**:
   - Dedicated dashboard card displaying authentic tunnel status, live URL, port configurator modal, test ping trigger, and live traffic log drawer.
5. **Dashboard Integration (`ProxyDashboardScreen.kt`)**:
   - Add `NgrokTunnelCard` into the primary dashboard lazy column.

---

## 5. Verification & Testing Plan

1. **Compilation**: Verify build with `compile_applet`.
2. **Unit Tests**: Run unit tests via `gradle :app:testDebugUnitTest`.
3. **Real Socket Verification**: Start `EmbeddedHttpServer` on local port, execute real HTTP `GET /ping` via `HttpURLConnection`, and assert `HTTP 200: {"status": "pong"}` response.
4. **Error Handling Verification**: Test invalid token input and verify that `TunnelState.Error` triggers immediately with the exact error message and zero fake data.
