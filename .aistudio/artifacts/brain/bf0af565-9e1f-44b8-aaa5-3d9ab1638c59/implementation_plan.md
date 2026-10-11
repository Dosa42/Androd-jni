# Embedded CCX Go Native AI Gateway & Protocol Groundworker

Architectural blueprint for adopting the embedded **CCX Go Native Engine (`libccx.so` / `backend-go`)** as the core intelligent backend and autonomous task manager of our app. Zero external cloud AI dependencies—100% powered by the compiled CCX Go engine and native protocol translation gateway.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Complete Pivot to CCX Go Engine**:
> External cloud API dependencies (like Google Gemini) are eliminated. The intelligent groundworker is powered by the **CCX Go Backend** (`BenedictKing/ccx/backend-go`) integrated via JNI (`libccx.so`), providing native protocol translation, channel failover, and upstream scheduling.

- **Confirmed Decision (Dual Mode Execution)**: The CCX Go engine operates in dual mode:
  1. **Direct In-Process JNI**: Microsecond memory-to-memory invocations via `NativeProxyBridge` for in-app stream translation.
  2. **Local Loopback HTTP Daemon**: Binds to a local port (default `8095`) exposing standard OpenAI (`/v1/chat/completions`) and Claude (`/v1/messages`) endpoints so client apps, userscripts, and Ngrok tunnels can route traffic directly into CCX.
- **Confirmed Decision (Primary Responsibility: Protocol Translation & Channel Scheduling)**: Focuses on automated protocol translation:
  - Claude Messages API ⇄ OpenAI Chat Completions API.
  - Streaming SSE chunk translation (`content_block_delta` ⇄ `choices[0].delta.content`).
  - Routing to client upstream servers (e.g. self-hosted LLMs, Ollama, vLLM, custom private endpoints).
- **Confirmed Decision (Dedicated AI Proxy UI Tab)**: Upgrades the main interface to an ergonomic Material 3 Navigation Bar featuring:
  - **Tab 1: Proxy Dashboard** (In-Process FFI daemon, live connection cards, and Ngrok tunnel ingress).
  - **Tab 2: CCX AI Gateway** (Active channel list, upstream routing rules, live protocol translator bench, and dynamic task scheduler).

---

## 1. Overview & Core Concept

### Where CCX Lives in the Application
- The cross-compilation pipeline is already anchored in `app/src/main/go/jni_bridge.go` and `.github/workflows/build-go-ccx-jni.yml`.
- `NativeProxyBridge.kt` already detects and loads `System.loadLibrary("ccx")`.
- This update connects the missing layer: **exposing CCX's AI gateway capabilities to the user interface and networking daemon**.

### What It Delivers
1. **Universal Protocol Translation**: Translates incoming requests from Claude format to OpenAI format (and vice versa) on the fly, streaming responses back in the client's expected schema.
2. **Dynamic Upstream Channel Manager**: Add, update, test, and balance connections to custom client servers with priority weights, API key rotation, and health probes.
3. **Local & Public AI Ingress**: Forward external requests arriving over Ngrok tunnels or local apps (`http://127.0.0.1:8095/v1/...`) straight through CCX's translation pipeline.
4. **Autonomous Groundworker Tasks**: Periodically probes upstream server health, updates latency metrics, and auto-fails over to backup channels if an upstream server drops.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Tabbed Navigation**:
   - Bottom navigation bar with **"Network Proxy"** and **"CCX AI Gateway"**.
2. **Channel & Client Server Management**:
   - In the CCX tab, users view active upstream channels (e.g., `Default OpenAI Upstream`, `Local Ollama 11434`, `Custom Claude Server`).
   - Tapping **"Add Channel"** opens a modal to specify upstream URL, protocol type (`OpenAI`, `Claude`, `Custom`), API key, and priority.
3. **Interactive Protocol Translator Workbench**:
   - A live test bench: input a Claude-style JSON payload -> tap **"Translate & Forward"** -> view the live translated OpenAI SSE stream streaming back in real time through the JNI bridge.
4. **Local Loopback Daemon Toggle**:
   - Toggle to enable the local CCX HTTP server (`127.0.0.1:8095`), displaying active endpoints (`/v1/chat/completions`, `/v1/messages`, `/api/channels`).

### Visual Identity & Theme
- **Theme**: Premium cyber-telemetry design system (`DarkBackground #0A0F16`, `DarkSurfaceElevated #172230`).
- **Brand Identity**: CCX Electric Indigo (`#6366F1`) and Cyber Cyan (`#00F0FF`) with status emeralds for healthy upstreams and amber for degraded servers.
- **Component Styling**: Clean card-based channel rows with latency indicators, model tags, and inline test triggers.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Pure On-Device & Private Gateway
- **Chosen Approach**: All translation, scheduling, and routing occur inside the local Android process via `libccx.so` and local Kotlin network bridges.
- **Why**: Zero telemetry leakage to Google Gemini or third-party cloud services. Works completely private and offline when paired with local upstreams.

### Decision 2: Dual JNI + Loopback Architecture
- **Chosen Approach**: Support both direct JNI native calls for in-process app features AND a local TCP socket for external apps/curl/Tampermonkey.
- **Why**: Maximizes versatility without forcing socket overhead for in-app tasks.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Android Application Process                     │
│                                                                        │
│   Jetpack Compose UI (Bottom Navigation)                               │
│   ├── Tab 1: ProxyDashboardScreen (FFI + Ngrok Tunnel)                 │
│   └── Tab 2: CcxAiGatewayScreen (New: CCX Channels & Translator)       │
│                         │                                              │
│                         ▼                                              │
│   CcxGatewayManager (Channel Manager & Scheduler)                      │
│   ├── Active Upstream Channels (StateFlow<List<CcxChannel>>)           │
│   ├── Protocol Translator (Claude ⇄ OpenAI ⇄ Custom)                   │
│   └── CcxLocalDaemon (TCP Loopback on port 8095)                       │
│                         │                                              │
│                         ▼                                              │
│   NativeProxyBridge (JNI System.loadLibrary("ccx"))                    │
│   ├── In-Process FFI Memory Callbacks                                  │
│   └── libccx.so (Go Runtime + CGO JNI Dispatcher)                      │
│           ├── CCX Internal Router & Schedulers                         │
│           └── Upstream HTTP Forwarder                                  │
└────────────────────────────────────────────────────────────────────────┘
          ▲                                           │
          │                                           ▼
   Ngrok Public Ingress                     Target Upstream Servers
   (https://*.ngrok-free.app)               (Client APIs / Ollama / Private)
```

### Data Models & State Architecture

```kotlin
enum class AiProtocol {
    OPENAI_COMPATIBLE,
    CLAUDE_MESSAGES,
    CUSTOM_REST
}

data class CcxChannel(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val protocol: AiProtocol,
    val targetUrl: String,
    val apiKey: String = "",
    val modelMapping: Map<String, String> = emptyMap(),
    val isEnabled: Boolean = true,
    val latencyMs: Long = 0L,
    val totalRequests: Long = 0L,
    val lastHealthCheckStatus: Int = 200
)

data class TranslationTask(
    val id: String,
    val inputProtocol: AiProtocol,
    val outputProtocol: AiProtocol,
    val inputPayload: String,
    val outputPayload: String = "",
    val isStreaming: Boolean = true,
    val durationMs: Long = 0L
)
```

### Source File Roadmap

1. **CCX Domain & Protocol Engine (`com.example.ccx`)**:
   - `CcxModels.kt`: Data models for channels, protocols, translation requests, and health states.
   - `CcxProtocolTranslator.kt`: Bidirectional parser/converter translating between Claude Messages JSON and OpenAI Chat Completions JSON.
   - `CcxGatewayManager.kt`: Core channel manager, key pool rotater, and upstream health probe scheduler.
   - `CcxLocalDaemon.kt`: Lightweight local TCP server exposing `/v1/chat/completions` and `/v1/messages` on port 8095.
2. **UI Presentation (`com.example.ui.screens.ccx`)**:
   - `CcxAiGatewayScreen.kt`: The dedicated CCX AI Gateway screen.
   - `CcxChannelCard.kt`: Individual channel cards showing health, latency, model routing, and edit/delete actions.
   - `CcxTranslatorBench.kt`: Live interactive payload translation workbench.
   - `AddChannelDialog.kt`: Modal for adding/editing upstream client servers.
3. **App Navigation (`MainActivity.kt`)**:
   - Integrate M3 `NavigationBar` switching between **Proxy Dashboard** and **CCX AI Gateway**.

---

## 5. Verification & Testing Plan

1. **Compilation**: Verify build with `compile_applet`.
2. **Unit Tests**:
   - Test Claude-to-OpenAI payload translation: assert input `messages` array correctly maps to OpenAI `role` and `content`.
   - Test streaming token parser: assert SSE chunk conversion generates valid delta JSON.
   - Test `CcxGatewayManager` channel registration and failover selection.
3. **Execution Test**:
   - Launch app, verify `CCX Go Native Engine` loaded, navigate to CCX AI Gateway tab, add a channel, execute a test translation in the workbench, and verify real-time frame logging.
