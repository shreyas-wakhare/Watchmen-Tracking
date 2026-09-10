# Watchmen Tracker — Android mDNS Discovery Forensic Diagnosis Protocol

## 1. Proven Physical Environment & Network Topology

| Dimension | Physical Evidence / Observed Value | Status |
| :--- | :--- | :---: |
| **Samsung Android Test Device IP** | `192.168.1.61` (Subnet: `255.255.255.0`, Gateway: `192.168.1.1`) | **VERIFIED** |
| **Windows Backend Laptop IP** | `192.168.1.53` (Subnet: `255.255.255.0`, Gateway: `192.168.1.1`) | **VERIFIED** |
| **FastAPI Backend Binding** | `0.0.0.0:8000` | **VERIFIED** |
| **HTTP Unicast (Samsung Chrome -> Laptop)** | `GET http://192.168.1.53:8000/health` -> `200 OK` | **PASS ✅** |
| **FastAPI Zeroconf Advertisement** | `WatchmenTrackerBackend` (`_watchmen._tcp.local.`) on `192.168.1.53:8000` | **PASS ✅** |
| **App mDNS Multicast Discovery** | Phone app reaching 8s watchdog -> `"Unable to discover Watchmen server"` | **INVESTIGATING ⚠️** |

---

## 2. Unicast vs. Multicast Architectural Isolation

```text
1. UNICAST HTTP (WORKS PERFECTLY ✅):
   Samsung Phone (192.168.1.61) ─── Direct TCP ───> Windows PC (192.168.1.53:8000) ───> 200 OK

2. MULTICAST mDNS (CURRENT PHYSICAL BLOCKER ⚠️):
   Windows PC (Zeroconf UDP 5353) ─── Multicast 224.0.0.251 ───> Router ───[?]───> Samsung Phone (NsdManager)
```

---

## 3. Forensic Diagnostic Breakdown & Decision Matrix

To pinpoint the exact failure layer without making speculative code changes, monitor logcat using:

```powershell
adb logcat -s BackendDiscoveryManager BackendEndpointManager AuthApiClient
```

### Forensic Decision Criteria:

```text
[DISCOVERY] startDiscovery()
          │
          ▼
   onDiscoveryStarted()
          │
  ┌───────┴───────┐
  │               │
FIRE              NEVER FIRES
  │               │
  ▼               ▼
Check onServiceFound()   [CLASSIFICATION A]: Android NsdManager service discovery failed to start.
  │
  ├─► NEVER FIRES (Timeout) ──► [CLASSIFICATION B]: Unicast HTTP works, but mDNS Multicast is blocked
  │                             (Windows Firewall UDP 5353, router multicast isolation, or Wi-Fi interface binding).
  │
  └─► FIRES ──► Check resolveService()
                     │
                     ├─► onResolveFailed ──► [CLASSIFICATION C]: NsdManager resolution failure / concurrency error.
                     │
                     └─► onServiceResolved ──► Check BackendEndpointManager
                                                      │
                                                      └─► [CLASSIFICATION D]: State management / UI observer issue.
```

---

## 4. Code Changes Made for Instrumentation

1. [`BackendDiscoveryManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/BackendDiscoveryManager.kt):
   - Added active Wi-Fi IP address retrieval (`connectionInfo.ipAddress`) and explicit logcat output.
   - Retained 12-point `[DISCOVERY]` step logging.
   - Retained 8-second watchdog timer to gracefully handle discovery timeouts without hanging the UI.

---

## 5. Physical Logcat Log Verification Protocol

Run the updated APK on the Samsung device (`192.168.1.61`) and capture logcat output during cold launch:

1. Confirm log: `[DISCOVERY] [1/12] Starting mDNS discovery for service type: _watchmen._tcp`
2. Confirm log: `[DISCOVERY] Active device Wi-Fi IP: 192.168.1.61 | MulticastLock held: true`
3. Confirm log: `[DISCOVERY] [2/12] Android NsdManager discovery started`
4. **Inspect Step 3**:
   - If `[DISCOVERY] [3/12] Android onServiceFound fired` appears: mDNS multicast packet **WAS RECEIVED**.
   - If `[DISCOVERY] Watchdog timeout (8000 ms) reached` appears without `onServiceFound`: mDNS multicast packet **WAS NOT RECEIVED** by the phone.
