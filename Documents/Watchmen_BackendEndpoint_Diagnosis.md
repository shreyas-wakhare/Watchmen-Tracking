# Watchmen Tracker — Backend Endpoint Resolution & Discovery Root-Cause Diagnosis

## 1. Executive Summary
- **Symptom**: On starting the Watchmen Tracker backend (`192.168.1.53:8000`) and opening the Android application on the Samsung test phone, the Login screen displays:
  > *"Backend server URL is not configured or discovered."*
- **Network Verification**: Direct HTTP access to `/health` from the Samsung device returned HTTP 200 OK. This proves that the phone can reach the FastAPI server over the LAN on `192.168.1.53:8000`.

- **Root Cause**: Architectural misplacement of `BackendDiscoveryManager`. The mDNS discovery engine was tightly coupled inside `TrackingService.kt`, which only starts *after* user login. When unauthenticated users open the app or launch it fresh after server/device restart, `BackendDiscoveryManager` was never started. Consequently, mDNS discovery was inactive during login, causing `BackendEndpointManager` to remain unconfigured and fail immediately.

---

## 2. Current Architecture & Lifecycle Trace

```text
               APP LAUNCH (Unauthenticated)
                            │
                            ▼
                     SplashActivity
                            │
                    Auth State Check
                            │
                            ▼
                      LoginActivity  ◄── (TrackingService NOT RUNNING)
                            │             (BackendDiscoveryManager NOT STARTED)
                     User Taps LOGIN
                            │
                            ▼
                     AuthApiClient
                            │
            BackendEndpointManager.getHttpUrlOrNull()
                            │
                  Is Base URL Configured?
                   /                 \
                 NO                  YES
                 │                    │
                 ▼                    ▼
      Returns null             Executes HTTP POST /auth/login
                 │                    │
                 ▼                    ▼
   "Backend server URL is        (Fails if cached IP is stale)
  not configured or discovered."
```

### Breakdown of Existing Components
1. **Backend (`backend/main.py`)**:
   - Registers Zeroconf/mDNS service type `_watchmen._tcp.local.` under service name `WatchmenTrackerBackend._watchmen._tcp.local.` on startup.
   - Advertises active LAN IP (e.g. `192.168.1.53`) and port (`8000`).
2. **`BackendEndpointManager.kt`**:
   - Singleton managing active HTTP/WebSocket base URLs (`activeHttpBaseUrl`, `activeWebSocketBaseUrl`).
   - Caches last known URL in `watchmen_prefs` under key `server_url`.
3. **`BackendDiscoveryManager.kt`**:
   - Uses Android `NsdManager` to discover service type `_watchmen._tcp`.
   - On resolution, invokes `BackendEndpointManager.updateEndpoint(resolvedUrl, context)`.
4. **`TrackingService.kt`**:
   - Instantiates and starts `BackendDiscoveryManager` inside `onCreate()`.
   - Only started by `MainActivity.startTrackingService()` *after* authentication.

---

## 3. Detailed Root Causes & Contributing Factors

### ROOT CAUSE #1: Architectural Misplacement of Discovery Lifecycle
- **Evidence**: [`TrackingService.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/TrackingService.kt#L416)
  - Line 416: `private var discoveryManager: BackendDiscoveryManager? = null`
  - Line 841: `discoveryManager = BackendDiscoveryManager(this)`
  - Line 843: `discoveryManager?.startDiscovery()`
- **Impact**: `BackendDiscoveryManager` is only instantiated inside `TrackingService.kt`. Because `TrackingService` requires prior authentication to run, mDNS discovery was completely dead before login.

### ROOT CAUSE #2: Absence of Global Application-Level Discovery
- **Evidence**: [`SplashActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/SplashActivity.kt) & [`LoginActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/LoginActivity.kt)
- **Impact**: Neither `SplashActivity` nor `LoginActivity` started or bound `BackendDiscoveryManager`. Discovery was not managed centrally alongside `BackendEndpointManager`.

### CONTRIBUTING FACTOR #1: Async Discovery Race Condition
- **Impact**: Even when discovery starts on app launch, mDNS DNS-SD resolution takes 300ms–2000ms. If a user immediately taps `LOGIN`, `BackendEndpointManager.getHttpUrlOrNull()` returned `null` instead of indicating that discovery was still in progress ("Connecting to server...").

### CONTRIBUTING FACTOR #2: Stale Cached IP Persistence
- **Impact**: If the PC IP changed (e.g. from `192.168.1.50` to `192.168.1.53` after reboot), `BackendEndpointManager.init()` loaded the old IP. Requests to `192.168.1.50` timed out, and because discovery wasn't active on `LoginActivity`, the new IP `192.168.1.53` was never discovered.

### TECHNICAL DEBT #1: Missing Manual Override / Server Config on Login Screen
- **Impact**: If a Wi-Fi router drops mDNS multicast packets, the user had no UI option on `LoginActivity` to inspect or manually update the server IP.

---

## 4. Contract Comparison: Backend vs Android

| Contract Parameter | Backend Advertisement (`backend/main.py`) | Android NSD Expectation (`BackendDiscoveryManager.kt`) | Status |
| :--- | :--- | :--- | :---: |
| **Service Type** | `_watchmen._tcp.local.` | `_watchmen._tcp` | ✅ Match |
| **Service Name** | `WatchmenTrackerBackend._watchmen._tcp.local.` | `WatchmenTrackerBackend...` | ✅ Match |
| **Port** | `8000` (or `PORT` env) | `8000` (or resolved port) | ✅ Match |
| **IP Address** | `get_lan_ip()` (e.g. `192.168.1.53`) | Resolved via `inet_aton` -> `cleanHost` | ✅ Match |
| **Discovery Scope** | Global (App startup) | Service level only (`TrackingService.kt`) | ❌ **MISMATCH** |

---

## 5. Proposed Architectural Fix

```text
┌─────────────────────────────────────────────────────────────┐
│                 BackendEndpointManager                       │
│  - Single source of truth for base HTTP/WS URLs              │
│  - Holds BackendDiscoveryManager instance                   │
│  - Exposes startGlobalDiscovery(context)                     │
│  - Exposes getOrAwaitBaseUrl() / isDiscovering()            │
└──────────────┬──────────────────────────────┬───────────────┘
               │                              │
     Started at App Launch           Consumed by Screens & Services
               │                              │
     ┌─────────┴─────────┐       ┌────────────┼────────────┐
     │ Splash / Login /  │       │ Login /    │ AuthApi    │ TrackingService
     │ MainActivity      │       │ Signup UI  │ Client     │ (WebSocket / GPS)
     └───────────────────┘       └────────────┴────────────┘
```

1. **Centralize Discovery in `BackendEndpointManager`**:
   - Embed `BackendDiscoveryManager` inside `BackendEndpointManager` (or manage it globally at Application lifecycle).
   - Automatically trigger `startGlobalDiscovery(context)` when `BackendEndpointManager.init(context)` is called.
2. **Update `SplashActivity` & `LoginActivity`**:
   - `SplashActivity` initializes `BackendEndpointManager.init(applicationContext)`, starting mDNS discovery immediately.
   - `LoginActivity` displays server connection status:
     - `DISCOVERING`: *"Searching for Watchmen server on network..."*
     - `CONNECTED`: *"Connected to http://192.168.1.53:8000"*
     - `UNCONFIGURED`: *"Server not found. Tap to configure IP."*
3. **Add Server Config Dialog / Direct Override in `LoginActivity`**:
   - Provide a Server Settings button on `LoginActivity` allowing manual IP entry (e.g. `http://192.168.1.53:8000`) if mDNS multicast is blocked on the local network.
4. **Preserve `TrackingService` Integration**:
   - `TrackingService` delegates discovery lifecycle to `BackendEndpointManager` rather than running its own duplicate instance.

---

## 6. Risk Analysis & Verification Strategy

- **Zero Hardcoded IPs**: No developer LAN IPs will be hardcoded in production source code.
- **Backward Compatibility**: `watchmen_prefs` caching and manual `SetupActivity` config will continue working cleanly.
- **Verification Steps**:
  1. **Unit Tests**: Verify `BackendEndpointManager` state transitions, discovery listener updates, and URL normalization.
  2. **Gradle Build**: Execute `gradlew testDebugUnitTest assembleDebug`.
  3. **Physical Samsung Test**: Test cold launch, login with active discovery, backend IP change recovery, and WebSocket tracking connectivity.
