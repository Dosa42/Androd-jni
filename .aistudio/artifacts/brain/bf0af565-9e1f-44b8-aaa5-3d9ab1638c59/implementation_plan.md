# Custom REST Client Backend & System Parameter Configuration Plan

Comprehensive architectural plan for integrating a dedicated **Client Backend (Custom REST)** configuration into tunnel host profiles and exposing all previously hardcoded network and server parameters into a dynamic UI settings panel.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural scope was confirmed during interactive clarification:

- **Confirmed Feature (Custom REST Client Backend)**: Adding dedicated **Client Backend (Custom REST)** settings to host profiles (including Custom REST API endpoint, authentication headers/tokens, and cloud signature parameters).
- **Confirmed Feature (Dynamic System Parameters)**: Exposing all previously silent/hardcoded system parameters into the UI configuration panel:
  - OkHttp connection timeout & read timeout (previously hardcoded to 15s / 0s).
  - Foreground Service Wakelock duration (previously hardcoded to 1 hour).
  - Ping interval & health check polling cadence (previously hardcoded to 60s).
  - Traffic log history buffer size (previously hardcoded to max 100 entries).
- **Confirmed Credential Storage**: Storing credentials and parameters persistently via `TunnelConfigManager` and `NgrokConfig` preferences.

---

## 1. Overview & Core Concept

### What It Delivers
1. **Client Backend (Custom REST Integration)**: Configure cloud provider backend targets with dedicated authentication tokens, custom REST routing paths, and upstream headers per host profile.
2. **Global System Parameter Dashboard**: A new configuration section in the settings dialog that allows tuning network timeouts, logging buffer sizes, wakelock durations, and health check intervals without recompiling.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Adding a Host with Custom REST Client Backend**:
   - User opens host setup -> taps **"Add Host Profile"**.
   - Fills in profile name, multi-target URLs, and the new **Client Backend (Custom REST)** section (Custom REST Endpoint, Bearer/API Key, Cloud Provider Header).
2. **Global System Settings Panel**:
   - In the configuration dialog, a dedicated **System Network & Daemon Parameters** section lets users adjust timeouts, buffer sizes, and ping intervals with instant persistence.

---

## 3. Technical Architecture & Data Strategy

### Upgraded Data Models

```kotlin
data class ClientBackendConfig(
    val isEnabled: Boolean = false,
    val restEndpoint: String = "",
    val apiKey: String = "",
    val cloudHeaderKey: String = "X-Cloud-Provider",
    val cloudHeaderValue: String = "Custom-REST"
)

data class SystemParameters(
    val connectTimeoutSeconds: Int = 15,
    val readTimeoutSeconds: Int = 30,
    val wakelockDurationMinutes: Int = 60,
    val pingIntervalSeconds: Int = 20,
    val trafficLogMaxEntries: Int = 100
)

data class TunnelHostProfile(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val targetUrls: List<String>,
    val pathRewritePrefix: String = "",
    val customHeaders: List<HeaderEntry> = emptyList(),
    val testPayload: String = "{\"action\": \"ping\"}",
    val clientBackend: ClientBackendConfig = ClientBackendConfig(),
    val isDefault: Boolean = false,
    val totalRequestsHandled: Long = 0L,
    val averageLatencyMs: Long = 0L
)
```

---

## 4. Verification & Testing Plan

1. **Compilation**: Verify build with `compile_applet`.
2. **Unit Tests**:
   - Test Custom REST client backend serialization/deserialization.
   - Test dynamic system parameter persistence in preferences.
