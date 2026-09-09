# Watchmen Tracker — Backend Endpoint Resolution & Discovery Permanent Fix

## 1. Problem Overview
- **Issue**: Android application displayed `"Backend server URL is not configured or discovered."` on the Login screen upon initial app launch or backend restart.
- **Root Cause**: `BackendDiscoveryManager` was previously instantiated and started exclusively inside `TrackingService.kt`. `TrackingService` is only started *after* user login. Thus, mDNS discovery was inactive before login, preventing `BackendEndpointManager` from discovering `http://192.168.1.53:8000`.

---

## 2. Architecture Comparison

### Before (Buggy Architecture)
```text
App Launch -> SplashActivity -> LoginActivity (mDNS Discovery INACTIVE)
                                       │
                                User Taps LOGIN
                                       │
                        "URL not configured or discovered" (FAIL)
                                       │
                              (Login impossible)
                                       │
                           TrackingService (mDNS Discovery started HERE - TOO LATE!)
```

### After (Fixed Architecture)
```text
                 FASTAPI BACKEND
                        │
                 mDNS / Zeroconf
                        │
             WatchmenTrackerBackend
                        │
                        │ LAN (192.168.1.53:8000)
                        ▼
                  ANDROID APP
                        │
                  App launches
                        │
                        ▼
             BackendEndpointManager
             (Global Single Owner)
                        │
                        ▼
             BackendDiscoveryManager
                        │
                     mDNS/NSD
                        │
             http://192.168.1.53:8000
                        │
             ┌──────────┴──────────┐
             ▼                     ▼
       AuthApiClient         TrackingService
             │                     │
        /auth/login               /ws
             │                     │
             └──────────┬──────────┘
                        ▼
                 FASTAPI BACKEND
                        │
                        ▼
                 Web Dashboard
```

---

## 3. Key Changes Implemented

1. **Centralized Discovery Ownership in `BackendEndpointManager`**:
   - `BackendEndpointManager` is now the single global owner of `BackendDiscoveryManager`.
   - Calling `BackendEndpointManager.init(context)` automatically starts global mDNS discovery on app launch (`SplashActivity`).
   - Introduced `DiscoveryState` enum: `UNCONFIGURED`, `DISCOVERING`, `CONNECTED`, `DISCOVERY_FAILED`.
   - Implemented `awaitEndpoint(timeoutMs: Long)` coroutine method allowing `AuthApiClient` to wait gracefully up to 3 seconds for mDNS discovery to complete if state is `DISCOVERING`.

2. **Decoupled `TrackingService` Discovery**:
   - Removed independent `BackendDiscoveryManager` instance from `TrackingService.kt`.
   - `TrackingService` delegates endpoint management directly to `BackendEndpointManager.init(this)`.

3. **Login UI Connection Badge & Fallback Config**:
   - Added live server status indicator badge on `LoginActivity`:
     - *"📡 Searching for Watchmen server..."* (when discovering)
     - *"✅ Server: http://192.168.1.53:8000"* (when resolved)
     - *"⚠️ Server not discovered. Tap ⚙️ to set IP."* (if unconfigured)
   - Added a Server Settings gear button (`ibServerSettings`) allowing manual server URL override as a fallback if a local router drops mDNS multicast packets.

4. **Zero Hardcoded IPs**:
   - Absolutely NO machine-specific developer LAN IP (e.g. `192.168.1.53`) is hardcoded in production source code. All resolution is dynamic via mDNS or user-configured fallback.

---

## 4. Files Changed

1. [`BackendEndpointManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/BackendEndpointManager.kt): Added global discovery management, `DiscoveryState` tracking, and `awaitEndpoint` coroutine support.
2. [`BackendDiscoveryManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/BackendDiscoveryManager.kt): Updated callbacks to report discovery state changes to `BackendEndpointManager`.
3. [`TrackingService.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/TrackingService.kt): Removed duplicate discovery instance; uses `BackendEndpointManager`.
4. [`AuthApiClient.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/AuthApiClient.kt): Added `awaitEndpoint(3000L)` call before HTTP requests to handle in-flight discovery gracefully.
5. [`LoginActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/LoginActivity.kt): Added `EndpointChangeListener` for real-time status updates and manual server URL fallback dialog.
6. [`activity_login.xml`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/res/layout/activity_login.xml): Added server status badge and gear icon.
7. [`BackendEndpointManagerTest.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/test/java/com/watchmen/tracker/BackendEndpointManagerTest.kt): Added unit tests for `DiscoveryState` transitions and `awaitEndpoint`.

---

## 5. Verification Results

### Automated Tests
- Gradle unit test & assembly execution: `./gradlew testDebugUnitTest assembleDebug`
- Status: **`BUILD SUCCESSFUL`**

### Physical Device Verification Matrix

| Verification Step | Target / Description | Result |
| :--- | :--- | :---: |
| **1. Backend Registration** | Zeroconf registers `WatchmenTrackerBackend` on `192.168.1.53:8000` | PASS |
| **2. App Cold Launch** | App starts -> `SplashActivity` initializes `BackendEndpointManager` | PASS |
| **3. mDNS Auto-Discovery** | mDNS resolves `http://192.168.1.53:8000` before/during Login | PASS |
| **4. Login Screen UI** | Displays *"✅ Server: http://192.168.1.53:8000"* | PASS |
| **5. Authentication API** | `POST /auth/login` connects to backend and returns JWT | PASS |
| **6. Main Transition** | Login success navigates to `MainActivity` | PASS |
| **7. Tracking Service** | `TrackingService` starts automatically | PASS |
| **8. WebSocket Connection** | Connects to `ws://192.168.1.53:8000/ws` | PASS |
| **9. Connected Devices API** | `GET /devices/connected` lists Samsung device | PASS |
| **10. Web Dashboard** | Samsung device visible on Dashboard dropdown | PASS |
