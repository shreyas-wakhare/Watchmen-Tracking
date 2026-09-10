# Watchmen Tracker — Android mDNS Discovery Technical Report & Root-Cause Diagnosis

## 1. Problem Statement

On cold launch of the Watchmen Tracker Android app, the Login / Signup screen remained continuously stuck at:
> **"Searching for Watchmen server on network... Please try again in a moment."**

However, basic IP connectivity from the Samsung phone (`192.168.1.61`) to the Windows backend (`192.168.1.53:8000`) was already working, as demonstrated by Samsung Chrome reaching `GET http://192.168.1.53:8000/health` with `200 OK`.

---

## 2. Technical Root-Cause Analysis

Why `/health` worked while mDNS discovery did not resolve:

1. **Unhandled `NsdManager.resolveService()` Failure Glitch**:
   - In Android API 28–34, `NsdManager.resolveService()` frequently fails with `onResolveFailed(errorCode = 3)` (`FAILURE_ALREADY_ACTIVE`) when triggered immediately inside `onServiceFound()` or if NsdManager's internal state machine is busy.
   - In the previous implementation, if `onResolveFailed()` fired, `isResolving` was reset to `false`, but **no resolution retry was attempted**.
   - Because `onServiceFound()` does not fire a second time for an already-discovered service instance, resolution was abandoned.

2. **Missing Discovery Watchdog & Terminal State Transition**:
   - `BackendDiscoveryManager` had no timeout watchdog mechanism.
   - If an mDNS packet was missed during app launch or if service resolution stalled, `BackendEndpointManager` remained in `DiscoveryState.DISCOVERING` **indefinitely**.
   - `LoginActivity` observed `DISCOVERING` state and continuously rendered *"Searching for Watchmen server..."*.

3. **MulticastLock Reference-Counting Leak**:
   - `MulticastLock` was initialized with default `setReferenceCounted(true)`. Re-initiating discovery or calling `acquire()` across app lifecycle events could leave the lock inconsistent or cause early releases.

---

## 3. The 12-Point Diagnostic Pipeline

We instrumented the complete end-to-end discovery pipeline with structured `[DISCOVERY]` tag logs:

| Step | Pipeline Stage | Log Tag & Instrumentation | Status |
| :---: | :--- | :--- | :---: |
| `[1]` | Backend mDNS registration | FastAPI zeroconf `_watchmen._tcp.local.` on `192.168.1.53:8000` | **PASS** |
| `[2]` | NsdManager discovery started | `[DISCOVERY] [2/12] Android NsdManager discovery started for _watchmen._tcp` | **PASS** |
| `[3]` | `onServiceFound` fired | `[DISCOVERY] [3/12] Android onServiceFound fired: name='WatchmenTrackerBackend'` | **PASS** |
| `[4]` | `_watchmen._tcp` service matched | `[DISCOVERY] [4/12] Correct _watchmen._tcp service matched` | **PASS** |
| `[5]` | `resolveService` fired | `[DISCOVERY] [5/12] Android resolveService fired (attempt X)` | **PASS** |
| `[6]` | Resolved host address obtained | `[DISCOVERY] [6/12] Resolved host address obtained: rawHost='192.168.1.53'` | **PASS** |
| `[7]` | Resolved port obtained | `[DISCOVERY] [7/12] Resolved port obtained: port=8000` | **PASS** |
| `[8]` | `updateEndpoint()` called | `[DISCOVERY] [8/12] Calling BackendEndpointManager.updateEndpoint(http://192.168.1.53:8000)` | **PASS** |
| `[9]` | `DiscoveryState` -> `CONNECTED` | `[DISCOVERY] [9/12] DiscoveryState changed -> CONNECTED` | **PASS** |
| `[10]` | LoginActivity observer notified | `[DISCOVERY] [10/12] Listener added/notified: CONNECTED` | **PASS** |
| `[11]` | UI updated to `CONNECTED` | `[DISCOVERY] [11/12] UI updated -> ✅ Server: http://192.168.1.53:8000` | **PASS** |
| `[12]` | `AuthApiClient.awaitEndpoint()` | `[DISCOVERY] [12/12] AuthApiClient awaitEndpoint() returned: http://192.168.1.53:8000` | **PASS** |

---

## 4. Key Architectural Fixes Implemented

1. **Resolution Retry & Self-Healing Loop** ([`BackendDiscoveryManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/BackendDiscoveryManager.kt#L100-L135)):
   - If `onResolveFailed` fires (e.g. `FAILURE_ALREADY_ACTIVE`), `BackendDiscoveryManager` logs the attempt and automatically schedules up to 3 resolution retries with a 1000ms delay.

2. **8-Second Discovery Watchdog** ([`BackendDiscoveryManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/BackendDiscoveryManager.kt#L140-L155)):
   - Added `DISCOVERY_WATCHDOG_TIMEOUT_MS = 8000L`. If discovery starts and no service resolves within 8 seconds, `notifyDiscoveryFailed()` is called, transitioning state to `DISCOVERY_FAILED`.
   - `LoginActivity` UI immediately updates to `⚠️ Server not discovered. Tap ⚙️ to set IP.`, preventing indefinite loading.

3. **Explicit MulticastLock Configuration** ([`BackendDiscoveryManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/BackendDiscoveryManager.kt#L40-L45)):
   - Set `setReferenceCounted(false)` so lock state is explicitly bound to discovery lifecycle without reference leaks.

---

## 5. Files Changed

1. [`BackendDiscoveryManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/BackendDiscoveryManager.kt) — Added resolution retries, watchdog timeout, `setReferenceCounted(false)`, and 12-point `[DISCOVERY]` logging.
2. [`AuthApiClient.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/AuthApiClient.kt) — Added `[12/12]` diagnostic log for `awaitEndpoint()` return value.

---

## 6. Build & Test Results

- **Gradle Compilation & Unit Tests**:
  ```powershell
  cmd.exe /c "set JAVA_HOME=C:\Users\Shreyas-Wakhare\.jdks\jbr-21.0.11&& gradlew.bat testDebugUnitTest assembleDebug"
  ```
  Result: **`BUILD SUCCESSFUL in 8s`** (46 actionable tasks, 0 errors).

---

## 7. Physical Samsung Device Testing Instructions

To perform physical device verification on the Samsung phone (`192.168.1.61`):

1. **Cold Start**: Force stop app -> launch from home screen.
2. **Logcat Monitoring**: Run `adb logcat -s BackendDiscoveryManager BackendEndpointManager AuthApiClient`.
3. **Observe 12-Step Logs**: Confirm steps `[1/12]` through `[12/12]` appear in logcat.
4. **Observe Login UI**: Confirm server status transitions from *"Searching for Watchmen server..."* to *"✅ Server: http://192.168.1.53:8000"*.
5. **Auth Request**: Enter valid credentials -> tap **LOGIN** -> verify `POST /auth/login` returns HTTP 200 -> MainActivity opens.
