# Watchmen Tracker — Complete Project Analysis

- **Analysis Date:** September 8, 2026
- **Repository Analyzed:** `c:\Users\Shreyas-Wakhare\Desktop\WatchmenTracker`
- **Application Identification:** Watchmen Elite | DWEX Command & Control Center (Android package: `com.watchmen.tracker`, Backend API: FastAPI v4.2, Frontend Design System: Watchmen Elite v2.5)
- **High-Level Project Status:** Functionally operational end-to-end telemetry and command-and-control system with native Android sensor tracking, bi-directional WebSocket and HTTP REST backend, real-time single-page operations dashboard, offline fallback queues, and face-liveness check-in. Lacks authentication and multi-tenant authorization.
- **Purpose of this Document:** Serves as the primary, definitive technical reverse-engineering reference and single source of truth for software engineers, systems architects, and AI coding agents operating on the Watchmen Tracker repository.

---

## 1. TECH STACK

### Mobile Application
The mobile client is a native Android application residing in the `app/` module of the repository:
- **Programming Language:** Kotlin (`2.0.21` with Kotlin Android plugin `org.jetbrains.kotlin.android`)
- **Android Target Platform:** `compileSdk = 34`, `targetSdk = 34`, `minSdk = 29` (Android 10.0+)
- **Build System:** Gradle with Kotlin DSL (`build.gradle.kts`, Android Gradle Plugin `8.13.0`, Gradle Wrapper `8.9`)
- **Core Architecture Components:** Android Jetpack (AppCompat `1.7.0`, Core KTX `1.13.1`, Material Design `1.12.0`, ViewBinding enabled)
- **Concurrency:** Kotlin Coroutines (`1.8.1`: `kotlinx-coroutines-android`, `kotlinx-coroutines-core`, `kotlinx-coroutines-play-services`)
- **Location Services:** Google Play Services Location (`com.google.android.gms:play-services-location:21.3.0`) utilizing `FusedLocationProviderClient`, `LocationRequest.Builder` with `PRIORITY_HIGH_ACCURACY`, and hardware sensors
- **Background Execution & Resilience:** Android Foreground Service with `foregroundServiceType="location"`, `WAKE_LOCK`, `AlarmManager` with `setExactAndAllowWhileIdle`, `JobScheduler` (`TrackingJobService`), and `BootReceiver`
- **Camera & Computer Vision:** AndroidX CameraX (`1.3.4`: `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`), Google ML Kit Face Detection (`com.google.mlkit:face-detection:16.1.7`) for interactive liveness verification
- **Network & Real-Time Communication:** Square OkHttp (`4.12.0`) for HTTP/REST and RFC 6455 WebSockets (`OkHttpClient.newWebSocket()`), Android NSD (`android.net.nsd.NsdManager`) for local mDNS zeroconf backend discovery
- **Speech & Hardware Control:** Android `TextToSpeech` engine wrapped in `MultilingualTTS` (English, Urdu, Hindi, Arabic), Android `Vibrator` / `VibratorManager`, `MediaPlayer`, `AudioManager` (`SirenController`), `TelephonyManager` (`SimCardMonitor`), Android `RoleManager` (`ROLE_SMS`)

### Backend
The backend is an asynchronous Python service located in the `backend/` directory:
- **Programming Language:** Python (`3.11` / `3.14` runtime compatible)
- **Framework & Server:** FastAPI (`>=0.115.0`), Starlette, Uvicorn (`>=0.30.0` with `standard` uvloop/httptools), Gunicorn (`>=23.0.0`)
- **Asynchronous Protocol:** WebSockets (`websockets>=13.0` and FastAPI Native WebSocket endpoints)
- **ORM & Database Toolkit:** SQLAlchemy (`>=2.0.35`) with Declarative Base and Session management
- **Database Driver:** SQLite built-in driver, `psycopg2-binary>=2.9.9` (driver present for PostgreSQL migrations)
- **Data Validation & Schemas:** Pydantic v2 (`>=2.10.0`)
- **Local Service Discovery:** Python Zeroconf (`zeroconf>=0.131.0`) advertising `_watchmen._tcp.local.` on LAN
- **Middleware:** `CORSMiddleware` (allow all origins `*`), `GZipMiddleware` (compression threshold 1,000 bytes), custom HTTP request execution timer middleware
- **Templating & Static Files:** Jinja2 (`>=3.1.4`), Starlette `StaticFiles` mounted at `/static`, `/uploads`, `/incidents`, `/videos`, `/bugs`

### Web Dashboard
The web dashboard is an enterprise-grade Single-Page Application (SPA) served statically from `backend/static/`:
- **Core Technologies:** HTML5, Modern Vanilla JavaScript (ES6+ async/await, modular functions, 4,249 lines in `dashboard.js`), Vanilla CSS (2,443 lines in `css/watchmen-elite.css`)
- **Typography & Icons:** Google Fonts (`Inter`, `JetBrains Mono`), FontAwesome 6.5.1
- **Mapping & Geospatial:** Leaflet.js `1.9.4` with Esri World Dark Gray Canvas and Google Satellite raster tile layers; Leaflet.Draw `1.0.4` for interactive circle and polygon geofence creation
- **Telemetry Charting:** Chart.js (vendored locally at `backend/static/chart.min.js`)
- **Real-Time Layer:** Native Browser WebSocket API connecting to `/ws?type=dashboard` with exponential backoff auto-reconnect
- **Design System:** Custom "Watchmen Elite" Design Language (dark surface tokens `--bg-primary: #0B0F14`, `--bg-secondary: #10161D`, semantic status tokens `--color-online: #22C55E`, `--color-warning: #F59E0B`, `--color-critical: #EF4444`, restrained 1px borders, CSS variables, zero Tailwind dependency)

### Database
- **Active Database Engine:** SQLite 3 (`backend/watchmen_test.db`, file size: ~2.58 MB)
- **Configuration String:** `DATABASE_URL = os.environ.get("DATABASE_URL", "sqlite:///./watchmen_test.db")`
- **Dialect Support:** Automatic detection in code for PostgreSQL connection options (`connect_args={"options": "-c statement_timeout=60000"}`)
- **Access Pattern:** SQLAlchemy ORM with direct sessions via `SessionLocal = sessionmaker(bind=engine)` and FastAPI dependency injection `Depends(get_db)`
- **Schema Migration:** No Alembic migrations configured; schemas are created via `Base.metadata.create_all(bind=engine)`

### Development Tools
- **Operating Systems / Environments:** Windows development host, Android Studio IDE, Python virtual environments (`.venv`)
- **Build Tools:** Gradle Wrapper (`gradlew`, `gradlew.bat`), pip (`requirements.txt`)
- **Testing Tools:** JUnit 4 (`4.13.2`) for Android unit tests, custom Python scripts (`test_discovery.py`, `test_ws_e2e.py`, `test_websocket.py`)

### Consolidated Tech Stack Table

| Layer | Technology | Version | Purpose | Evidence |
| :--- | :--- | :--- | :--- | :--- |
| **Mobile OS** | Android SDK | SDK 29 - 34 | Client runtime platform | `app/build.gradle.kts:8-13` |
| **Mobile Language** | Kotlin | 2.0.21 | Primary application code | `gradle/libs.versions.toml:3` |
| **Mobile Build** | Gradle / AGP | 8.13.0 | Android build automation | `gradle/libs.versions.toml:2` |
| **Mobile Concurrency** | Kotlinx Coroutines | 1.8.1 | Asynchronous processing & background tasks | `app/build.gradle.kts:50-52` |
| **Mobile Location** | Play Services Location | 21.3.0 | GPS and network location polling | `app/build.gradle.kts:48` |
| **Mobile Camera** | CameraX | 1.3.4 | Face capture and verification preview | `app/build.gradle.kts:58-62` |
| **Mobile ML** | ML Kit Face Detection | 16.1.7 | Liveness, blink, and face tracking | `app/build.gradle.kts:65` |
| **Mobile HTTP/WS** | OkHttp | 4.12.0 | REST API calls & WebSocket client connection | `app/build.gradle.kts:68` |
| **Mobile Speech** | Android TTS | Platform Native | Multilingual spoken voice notifications | `MultilingualTTS.kt` |
| **Backend Framework** | FastAPI | >=0.115.0 | REST API & WebSocket routing | `backend/requirements.txt:1` |
| **Backend ASGI** | Uvicorn | >=0.30.0 | High-performance ASGI web server | `backend/requirements.txt:2` |
| **Backend ORM** | SQLAlchemy | >=2.0.35 | Database abstraction & query modeling | `backend/requirements.txt:4` |
| **Backend Schemas** | Pydantic | >=2.10.0 | Payload validation and model aliasing | `backend/requirements.txt:10` |
| **Backend Discovery** | Zeroconf | >=0.131.0 | mDNS service advertisement on LAN | `backend/requirements.txt:12` |
| **Database Engine** | SQLite | 3.x | Persistent local relational store | `backend/watchmen_test.db` |
| **Dashboard UI** | Vanilla HTML5/CSS3/JS | Native ES6+ | SPA dashboard interface | `dashboard.html`, `dashboard.js` |
| **Dashboard Mapping** | Leaflet | 1.9.4 | Map visualization, markers, and trails | `dashboard.html:18-19` |
| **Dashboard Drawing** | Leaflet.Draw | 1.0.4 | Geofence interactive polygon/circle creation | `dashboard.html:20-21` |
| **Dashboard Charts** | Chart.js | 4.x (vendored) | Telemetry & activity visualization | `backend/static/chart.min.js` |

---

## 2. PROBLEM STATEMENT

### Operational Problem
Organizations employing security personnel, field watchmen, and patrol guards face significant integrity, visibility, and safety challenges. Field operators are distributed across large construction projects, industrial zones, or commercial properties during day and night shifts. Management has limited means of verifying whether watchmen are actually on site, actively patrolling designated patrol paths, remaining alert, or experiencing security breaches.

### Watchmen Monitoring & Integrity Problem
A recurring operational failure mode in security guard tracking is false presence or evasion:
- *CONFIRMED FROM CODE (`GpsSpoofingDetector.kt`):* Personnel using fake GPS software or developer mock location apps on Android devices to simulate patrol route presence while remaining off-site.
- *CONFIRMED FROM CODE (`MovementAuthenticityDetector.kt`):* Personnel placing the phone on an oscillating mechanical cradle, vibrating surface, or manually shaking the phone to simulate footsteps while remaining seated or asleep.
- *CONFIRMED FROM CODE (`SimCardMonitor.kt`):* Personnel removing the authorized corporate SIM card to prevent mobile data transmission and avoid live tracking.
- *CONFIRMED FROM CODE (`FaceLivenessDetector.kt`, `CameraCaptureActivity.kt`):* Personnel using printed photographs, looping video screens, or absent check-ins during mandatory hourly photo verification roll calls.

### Location Tracking & Device Visibility Problem
- *CONFIRMED FROM CODE (`TrackingService.kt`, `KalmanPedestrian`):* Raw consumer GPS data exhibits extreme jitter, multipath drift, indoor signal attenuation, and false speed spikes. The system must smooth pedestrian drift while distinguishing between walking, vehicle movement, and stationary states.
- *CONFIRMED FROM CODE (`OfflineGpsStorage.kt`, `TelemetryCache`):* Field operations frequently suffer from intermittent GSM/LTE cellular dead zones. Without local persistent caching, telemetry is permanently lost during network disconnections.

### Communication & Incident Problem
- *CONFIRMED FROM CODE (`MainActivity.kt`, `SirenController.kt`, `IncidentReportActivity.kt`):* Lone workers in distress need an immediate panic mechanism that transmits coordinates to the control room and fires emergency SMS messages to supervisors even when data lines fail. Conversely, central command requires remote actuation of sirens, immediate location pings, and multilingual audio instructions broadcast directly into the watchman's earpiece.

---

## 3. PROJECT GOAL

**Primary Goal:**
> The Watchmen Tracker system is an end-to-end mission-critical security patrol and telemetry platform that continuously captures, filters, and analyzes watchman movement authenticity, verifies biometric hourly presence, alerts control center operators to tampering and geofence breaches in real time, and provides an operations dashboard for fleet tracking and remote device actuation.

### Detailed Goals
1. **Continuous High-Accuracy GPS Tracking:** Ingest location data at dynamic rates (8s to 60s depending on motion context) using Fused Location services smoothed by a Kalman filter.
2. **Device State & Context Detection:** Classify movement states (`INITIALIZING`, `MOVING`, `IDLE_SUSPECTED`, `IDLE_CONFIRMED`, `STOPPED`, `IN_VEHICLE`) based on accelerometer variance, step detector counters, and speed.
3. **Anti-Fraud & Security Integrity:** Automatically detect mock locations, teleportation jumps, synthetic vibrations, phone cradle shaking, and SIM card removal, dispatching automated alerts to central command.
4. **Interactive Biometric Check-In:** Require hourly selfie captures with real-time ML Kit facial liveness verification (blinking, head yaw, motion detection) to prevent spoofing.
5. **Real-Time Operational Visibility:** Stream location updates, device health, and alerts to a central web command console over persistent WebSockets.
6. **Bi-Directional Command Center:** Enable operators to remotely sound emergency sirens, request instant location updates, wipe caches, and send audio broadcast announcements in multiple languages (English, Urdu, Hindi, Arabic).
7. **Offline Fault Tolerance:** Maintain localized disk queues for telemetry, incident reports, and offline track sessions, replaying them sequentially upon cellular reconnection.

---

## 4. WHY ARE WE BUILDING THIS APPLICATION?

### Operational Justification
| Question | Answer from Codebase Evidence |
| :--- | :--- |
| **Why does this application exist?** | To eliminate the blind spots of lone-worker security guarding by providing tamper-resistant, verifiable real-time monitoring and two-way emergency controls. |
| **Who uses it?** | **Watchmen / Guards:** Carry the mobile Android app running the background tracking service, respond to hourly photo prompts, report incidents, and trigger emergency panic alarms.<br>**Operations / Control Room Team:** View the web dashboard, monitor fleet status, review alerts, draw geofences, dispatch remote commands, and manage announcements. |
| **What happens without this system?** | Operations relies on manual radio calls, paper logbooks, or unverified chat check-ins. Evasion (sleeping on duty, leaving the post, spoofing location) remains undetected, and emergency distress situations cannot be identified or pinpointed immediately. |

### Value Realized by System Components
- **Real-Time Tracking:** Instantaneous visualization of all guards on dark-mode vector and satellite maps.
- **Centralized Visibility:** A single unified dashboard displaying total active devices, online/offline connection state, battery levels, speed, and geofence compliance.
- **Automated Anti-Spoofing:** Alerts for GPS mock providers, unrealistic speed jumps (>360 km/h), stationary stepping fraud, and SIM card removal.
- **Remote Commands:** Operators can remotely activate a 100% volume audible siren and repeating vibration on lost or unresponsive guard phones.
- **Multilingual Support:** Watchmen speaking Urdu, Hindi, Arabic, or English receive localized UI text and voice announcements.

---

## 5. SYSTEM OVERVIEW

The Watchmen Tracker architecture is organized into four primary layers:
1. **Mobile Client (Android):** Runs the core `TrackingService` foreground service, sensors, Kalman filter, offline queue, ML Kit face detector, and OkHttp WebSocket client.
2. **Transport & Network Discovery:** Discovers backend IP on the LAN using mDNS (`_watchmen._tcp.local.`), handles automatic IP resolution, and transmits bidirectional HTTP REST and WebSocket traffic.
3. **Backend Service (FastAPI):** Ingests telemetry, processes geofences, detects duplicates, logs alerts into SQLite/PostgreSQL, manages connection pools, and broadcasts real-time events.
4. **Operations Web Dashboard (Leaflet + Chart.js + Vanilla JS):** Visualizes fleet coordinates, routes commands, manages geofences, and audits alerts.

### System Architecture Diagram

```text
                           WATCHMEN PATROL DEVICE
                         (Android Smartphone - App)
                                     │
           ┌─────────────────────────┼─────────────────────────┐
           ▼                         ▼                         ▼
   Sensors & Hardware        Local Security Engine      Offline Storage
   • FusedLocationProvider   • GpsSpoofingDetector      • TelemetryCache (JSON)
   • Accelerometer & Steps   • MovementAuthDetector     • OfflineGpsStorage (.track)
   • CameraX + ML Kit Face   • SimCardMonitor           • SharedPreferences
   • Siren / MediaPlayer     • Multilingual TTS
           │                         │                         │
           └─────────────────────────┼─────────────────────────┘
                                     │
                        mDNS Service Discovery
                     (NsdManager ◄──► Zeroconf)
                                     │
                        HTTP REST / WebSockets
                      (OkHttp RFC 6455 Client)
                                     │
                                     ▼
                      FASTAPI BACKEND (main.py)
                                     │
           ┌─────────────────────────┴─────────────────────────┐
           ▼                                                   ▼
   REST API Endpoints                                 ConnectionManager
   • POST /telemetry, /telemetry_batch                • Active Devices Map
   • POST /alert, /security-alert, /upload            • Dashboard WS Pool
   • POST /device/{id}/command, /message              • Broadcast Engine
   • GET  /data, /alerts, /geofences                  • Two-Way Command Router
           │                                                   │
           ▼                                                   ▼
   RELATIONAL DATABASE                               WEBSOCKET BROADCAST
  (backend/watchmen_test.db)                        (/ws?type=dashboard)
   • telemetry (7,005 rows)                                    │
   • security_alerts (1,541 rows)                              ▼
   • alerts (4 rows)                                   OPERATIONS TEAM
   • geofences & events                              (Web Command Console)
   • incidents, checkpoints                          • Live Fleet Map (Leaflet)
   • announcements & receipts                        • Command Center
                                                     • Analytics & Reports
                                                     • Photo Gallery
```

---

## 6. MOBILE APPLICATION

### 6.1 Purpose
The mobile application (`com.watchmen.tracker`) runs on an Android device carried by the watchman. It operates autonomously in the background as an unkillable foreground service, continuously tracking the guard's position, validating motion authenticity, executing remote commands from the control room, and prompting for mandatory biometric presence verification.

### 6.2 Application Architecture
The Android client comprises the following components in `app/src/main/java/com/watchmen/tracker/`:

- **Activities:**
  - `SplashActivity.kt`: App launch entry point. Checks if initial onboarding setup is completed (`watchmen_prefs -> setup_complete`). If complete, navigates to `MainActivity`; otherwise opens `SetupActivity`.
  - `MainActivity.kt`: Primary user interface for the watchman. Displays live tracking state, check-in button, incident report launcher, checkpoint scanner, and emergency panic button. Manages Android runtime permissions.
  - `SetupActivity.kt`: Initial device provisioning screen. Configures device name, supervisor phone number, project number, optional backend server URL, and preferred language (`en`, `ur`, `hi`, `ar`).
  - `CameraCaptureActivity.kt`: Interactive face liveness detection screen powered by CameraX and ML Kit. Guides user to blink, turn head, and captures optimized photos for upload.
  - `IncidentReportActivity.kt`: Form for guards to report on-site security incidents with categorized types, text descriptions, and camera attachments.
  - `AppGuideActivity.kt` / `AppGuideAdapter.kt`: Pager-based user onboarding guide explaining app functionality.

- **Services:**
  - `TrackingService.kt`: Core engine of the client. Runs as a sticky Foreground Service (`android:foregroundServiceType="location"`), manages location callbacks, applies Kalman filtering, evaluates movement state machines, monitors sensors, connects to WebSocket `/ws`, and processes incoming commands.
  - `TrackingJobService.kt`: `JobService` registered with Android `JobScheduler` (Job ID `456`, periodic 15 minutes, network required) to resurrect `TrackingService` if the OS kills it under aggressive battery management.

- **Managers & Helpers:**
  - `BackendEndpointManager.kt`: Thread-safe singleton managing the backend base URL. Handles mDNS discovery updates, dynamically derives WebSocket URLs (`http->ws`, `https->wss`), normalizes endpoints, and notifies registered listeners.
  - `BackendDiscoveryManager.kt`: Wraps Android `NsdManager` and `WifiManager.MulticastLock` to resolve `_watchmen._tcp` mDNS advertisements on the local subnet.
  - `FaceLivenessDetector.kt`: ML Kit wrapper evaluating blink duration, head yaw/roll angle, face motion, and bounding box ratios to calculate liveness confidence scores.
  - `GpsSpoofingDetector.kt`: Evaluates Android mock location flags (`location.isMock` / `isFromMockProvider`), impossible velocity (>100 m/s), teleportation jumps, and app ops mock permissions.
  - `MovementAuthenticityDetector.kt`: Ingests accelerometer and gyroscope histories at `SENSOR_DELAY_GAME` to detect phone cradle shaking, stationary ground placement with artificial steps, and mechanical vibration.
  - `SimCardMonitor.kt`: Listens to `TelephonyManager` SIM state broadcasts to alert when a SIM card is removed.
  - `SirenController.kt`: Manages `MediaPlayer` and `Vibrator` to trigger repeating 100% volume sirens upon remote control room demand.
  - `OfflineGpsStorage.kt`: Records offline track sessions into JSON-lines files (`.track`) in internal storage for batch replay.
  - `MultilingualTTS.kt`: Wraps Android `TextToSpeech` with language selection for voice alerts.
  - `WarningCooldownManager.kt`: Rate-limits auditory warnings to prevent sound spam.
  - `TrialManager.kt`: Tracks consecutive liveness trial failures (up to 3 trials) and reports failure details to the supervisor.

- **Receivers:**
  - `BootReceiver.kt`: Listens for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `REBOOT` to start `TrackingService` immediately after device reboot.
  - `DeviceAdminReceiver.kt` (`WatchmenDeviceAdminReceiver`): Android Device Admin receiver registered against `xml/device_admin_policy.xml` for enterprise security enforcement.
  - `SmsReceiver.kt`: BroadcastReceiver for `android.provider.Telephony.SMS_RECEIVED` enabling the app to qualify as an emergency SMS handler.
  - `PhotoCaptureReceiver.kt`: Handles alarm broadcasts triggering scheduled photo verification.

### 6.3 Location Tracking Implementation
- **API Used:** Google Play Services `FusedLocationProviderClient`.
- **Update Frequency:** High-accuracy location request created via:
  ```kotlin
  LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
      .setMinUpdateIntervalMillis(3_000L)
      .setMaxUpdateDelayMillis(8_000L)
      .setWaitForAccurateLocation(false)
      .build()
  ```
- **Transmission Frequency by State:** The service adjusts transmission intervals based on motion classification (`TrackingService.kt:445-455`):
  - `MOVING`: 20 seconds (or immediately if displacement > 3m and speed > 0.3 m/s)
  - `INITIALIZING`: 8 seconds
  - `IN_VEHICLE`: 20 seconds (or if displacement > 50m)
  - `IDLE_SUSPECTED`: 20 seconds
  - `IDLE_CONFIRMED`: 30 seconds
  - `STOPPED`: 60 seconds
  - `Force Send Interval`: 20 seconds maximum elapsed time between telemetry packets.
- **Filtering & Smoothing:**
  - *Kalman Pedestrian Filter:* A 4-state Kalman filter (`KalmanPedestrian`) estimates `[lat, lon, v_lat, v_lon]`. When stationary, velocity states are zeroed and covariance decayed.
  - *Stationary Detector:* Examines a 5-reading circular buffer (`confirmWindow = 5`); if drift is < 3.0m, speed < 0.15 m/s, and steps == 0, the device is declared stationary.
  - *Vehicle Detector:* Evaluates speed windows (>7.0 m/s low threshold, >15.0 m/s high threshold) and accelerometer variance (< 2.0 smooth motion) to identify vehicle transit.
  - *Jump Detection:* Rejects location deltas where implied jump distance exceeds 10m for pedestrians (or 100m in vehicles) within 5 seconds (`TrackingService.kt:1630-1641`).
- **Foreground Service & Battery Considerations:** Acquires a partial wake lock (`PowerManager.PARTIAL_WAKE_LOCK`, refreshed every 8 minutes with a 10-minute timeout). Requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

### 6.4 Telemetry Fields Generated

| Field | Source | Generated By | Sent To | Stored in DB? | Used By |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `deviceid` | Preferences / Android ID | `getTrackerDeviceId()` | POST `/telemetry` | Yes (`telemetry.device_id`) | Dashboard selector & filtering |
| `devicename` | Preferences | `SetupActivity` | POST `/telemetry` | Yes (`telemetry.device_name`) | Dashboard device table |
| `projectnumber` | Preferences | `SetupActivity` | POST `/telemetry` | Yes (`telemetry.project_number`)| Operational project groupings |
| `latitude` | GPS / Kalman | `processLocationUpdate()` | POST `/telemetry` | Yes (`telemetry.latitude`) | Map markers & breadcrumb trails |
| `longitude` | GPS / Kalman | `processLocationUpdate()` | POST `/telemetry` | Yes (`telemetry.longitude`) | Map markers & breadcrumb trails |
| `speed` | GPS hardware | `Location.speed` | POST `/telemetry` | Yes (`telemetry.speed`) | Activity log & anomaly engine |
| `bearing` | GPS hardware | `Location.bearing` | POST `/telemetry` | Yes (`telemetry.bearing`) | Map heading arrows |
| `altitude` | GPS hardware | `Location.altitude` | POST `/telemetry` | Yes (`telemetry.altitude`) | Terrain / elevation tracking |
| `accuracy` | GPS hardware | `Location.accuracy` | POST `/telemetry` | Yes (`telemetry.accuracy`) | GPS jitter ring display |
| `steps` | Hardware Step Detector | `Sensor.TYPE_STEP_DETECTOR`| POST `/telemetry` | Yes (`telemetry.steps`) | Daily step metrics & integrity check |
| `battery` | BatteryManager | `getBatteryLevel()` | POST `/telemetry` | Yes (`telemetry.battery`) | Battery status bar & alert trigger |
| `offline` | ConnectivityManager | `isOnline()` | POST `/telemetry` | Yes (`telemetry.offline`) | Connectivity indicator |
| `trackingstate` | State Machine | `evaluateAndTransitionState()`| POST `/telemetry` | Yes (`telemetry.tracking_state`)| Status badge (`MOVING`, `STOPPED`, etc.)|
| `movementcontext` | Sensor analysis | `MovementContext` | POST `/telemetry` | Yes (`telemetry.movement_context`)| JSON context for displacement & variance |
| `timestamp` | UTC Clock | `SimpleDateFormat (UTC)` | POST `/telemetry` | Yes (`telemetry.timestamp`) | Chronological order & time filters |

### 6.5 Device Identity
The client creates a composite identifier to uniquely identify devices without collision:
- **Generation Logic (`TrackingService.kt:2124-2148`):**
  1. Reads cached `device_id` from `watchmen_prefs`.
  2. If missing, reads `project_number` (default `PROJECT_UNKNOWN`) and `device_name` (default `DEVICE_UNKNOWN`).
  3. Queries `Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)`.
  4. Concatenates: `"${projectNumber}_${deviceName}_${androidId.take(8)}"`.
  5. Caches the string permanently in `watchmen_prefs`.
  *Real Example from active database:* `TEST001_Samsung Test_74285b00`.

### 6.6 Connectivity & Resilience
- **HTTP Transport:** OkHttp client with `retryOnConnectionFailure(true)`.
- **WebSocket Transport:** Client establishes connection to `/ws?type=device&deviceid=<deviceId>`.
- **Handshake Protocol:** Immediately upon `onOpen`, sends:
  ```json
  {"type": "device_handshake", "deviceid": "<deviceId>", "device_id": "<deviceId>", "timestamp": 1788841408000}
  ```
- **Reconnection Logic:** Exponential backoff with random jitter (`TrackingService.kt:458-466`):
  $\text{delay} = \min(1000 \times 1.5^{\text{attempt}}, 30000) + \text{jitter}(0\dots 500\text{ms})$.
- **Endpoint Auto-Discovery:** If the backend IP changes on the LAN, `BackendDiscoveryManager` resolves `_watchmen._tcp` via mDNS and invokes `BackendEndpointManager.updateEndpoint()`. This triggers `reconnectWebSocketToNewEndpoint()` and flushes offline telemetry caches.
- **Offline Persistence:**
  - Individual telemetry packets failing HTTP post are written to disk by `TelemetryCache`. A retry loop (`scheduleRetryLoop`) replays up to 50 cached packets every 45 seconds.
  - Complete continuous tracks during extended offline periods are written to `.track` files by `OfflineGpsStorage`. When connectivity returns, `startOfflineSyncLoop` bundles tracks into JSON arrays and posts them to `/telemetry_batch`.

### 6.7 Remote Commands
The mobile application accepts commands over WebSocket in `TrackingService.kt:701-768`:

| Command | Sent From | Received By | Mobile Action | Response Sent |
| :--- | :--- | :--- | :--- | :--- |
| `START_SIREN` | Dashboard Command Center | `TrackingService` WS | `sirenController.startSiren()` (Max volume alarm + repeating vibration) | `{"type":"command_ack","command":"START_SIREN","status":"success"}` |
| `STOP_SIREN` | Dashboard Command Center | `TrackingService` WS | `sirenController.stopSiren()` (Releases MediaPlayer & cancels vibration) | `{"type":"command_ack","command":"STOP_SIREN","status":"success"}` |
| `GET_LOCATION` | Dashboard Command Center | `TrackingService` WS | Requests `fusedClient.lastLocation`, executes `sendTelemetry(loc)` | `{"type":"command_ack","command":"GET_LOCATION","latitude":...,"longitude":...}` |
| `WIPE_CACHE` | Dashboard Command Center | `TrackingService` WS | Clears `cache.clear()` and `offlineGpsStorage.clearAllSessions()` | `{"type":"command_ack","command":"WIPE_CACHE","status":"success"}` |
| `RESTART_SERVICE` | Dashboard Command Center | `TrackingService` WS | Stops siren, resets state machines, triggers delayed `restartService()` | `{"type":"command_ack","command":"RESTART_SERVICE","status":"success"}` |
| `message` (chat) | Dashboard Chat Console | `TrackingService` WS | Speaks message via `ttsHelper.speak()`, triggers 500ms vibration | `{"type":"message_ack","status":"success"}` |
| `announcement` | Dashboard Announcement | `TrackingService` WS | Shows notification, vibrates, speaks TTS message, logs ACK | `{"type":"security_alert","alert_type":"ANNOUNCEMENT_ACK"}` |

### 6.8 Current Mobile Implementation Status
- **IMPLEMENTED:** GPS tracking, Kalman filtering, state machine classification, WebSocket bi-directional command processing, siren actuation, offline disk batch sync, mDNS local discovery, panic SMS dispatch fallback, multi-language TTS, CameraX selfie capture with ML Kit blink/head-turn liveness detection, SIM removal detector.
- **PARTIALLY IMPLEMENTED:** Background automatic SMS transmission (restricted by modern Android OS permissions; falls back to pre-filling the system SMS app via `ACTION_SENDTO` intent). Geofence client-side validation is temporarily bypassed in `validateLocation()` (commented out at lines 1603-1628) to allow unrestricted telemetry flow.
- **NOT IMPLEMENTED:** NFC checkpoint scanning (button exists in `activity_main.xml`, but click handler triggers a placeholder dialog without hardware NFC adapter binding). In-app user login authentication.

---

## 7. WEB APPLICATION / DASHBOARD

### 7.1 Purpose
The Watchmen Elite Command & Control dashboard provides the operations team with full fleet operational visibility, geospatial mapping, device management, incident tracking, geofencing, broadcast announcements, and telemetry analytics.

### 7.2 Dashboard Architecture
- **Structure:** Single-Page Architecture in `backend/static/dashboard.html` with 14 functional workspaces toggled via `switchView(viewName)`.
- **Styles:** Custom design system in `backend/static/css/watchmen-elite.css` utilizing dark theme surface tokens, responsive flex/grid layouts, and zero external CSS utility frameworks.
- **Logic:** Central controller in `backend/static/dashboard.js` (4,249 lines) maintaining global state variables (`currentDeviceId`, `connectedDevices`, `deviceData`, `deviceMarkers`, `deviceTrailsMap`).
- **External Dependencies:** Leaflet.js `1.9.4`, Leaflet.Draw `1.0.4`, FontAwesome `6.5.1`, Chart.js.

### 7.3 Overview Dashboard Components
The primary Overview view (`#view-dashboard`) contains:
1. **KPI Stat Cards (`updateKpiCards()`):**
   - *Active Tracking / Total Devices:* Counts total distinct devices and devices transmitting within 5 minutes.
   - *Online Devices:* Computes active WebSocket connections from `connectedDevices`.
   - *Battery Alerts:* Displays count of devices with battery $\le 15\%$.
   - *Active Alerts:* Displays count of unresolved security and panic alerts.
2. **Global Fleet / Device Scoped Header:** Dynamically shifts context when a device is selected from the top dropdown (`#deviceSelector`).
3. **Embedded Fleet Map:** Synchronized Leaflet map showing real-time pulses, directional bearing, and color-coded status rings.
4. **Live Activity Carousel / Stream (`updateScopedActivity()`):** Chronological log of GPS updates, connection events, and alerts.
5. **Recent Security Alerts Panel:** Table of latest alerts with quick-resolve actions.

### 7.4 Device Selection & Filtering (`selectDevice()`)
- **Fleet Scope (`currentDeviceId = null`):**
  - Displays aggregated fleet KPIs.
  - Shows all device markers on the map; automatically calls `map.fitBounds()` across all active coordinates.
  - Hides device-specific controls (e.g., Live Location Request, 24-hour summary buttons) using CSS class `.hidden-scope`.
- **Device Scope (`currentDeviceId = "DEVICE_ID"`):**
  - Filters map markers to display only the selected device and its historical breadcrumb polyline trail.
  - Binds the Chart.js real-time graph to the device's incoming speed/battery stream.
  - Enables the Command Center and Chat panels specifically for the targeted device.

### 7.5 Live Tracking & Map Controls
- **Markers:** Generated as custom `L.divIcon` HTML elements with CSS pulses (`.marker-pulse`). Color indicates status:
  - Green (`#22C55E`): Online & healthy
  - Amber (`#F59E0B`): Battery $\le 15\%$
  - Red (`#EF4444`): Battery $\le 5\%$ or active panic
  - Gray (`#667585`): Offline / disconnected
- **Trails:** `L.polyline` connecting the last 500 telemetry points.
- **Follow Mode (`autoFollow = true`):** Automatically pans the Leaflet viewport to follow the active device whenever a new GPS coordinate is received.
- **Historical Playback:** `startPlayback()` steps through cached historical coordinates with a pause/resume scrubber.

### 7.6 Alerts Management
- Ingests alerts via WebSocket (`data.alert`, `data.securityalert`, `data.type === 'new_photo_alert'`) and REST (`GET /alerts`, `GET /security-alerts`).
- Categorizes alerts by severity (`NORMAL`, `HIGH`, `CRITICAL`).
- Displays a top navigation notification bell badge with live unread counts.
- Plays an audible browser chime (`playAlertSound()`) on critical panic events.

### 7.7 Command Center Workspace (`#view-commands`)
- Features quick action buttons for:
  - `START_SIREN` (Red warning button with confirmation modal)
  - `STOP_SIREN` (Green stop button)
  - `GET_LOCATION` (Forces immediate GPS poll)
  - `WIPE_CACHE` (Clears mobile disk buffers)
  - `RESTART_SERVICE` (Reboots mobile background tracking)
- **Direct Terminal / Chat:** Interactive terminal window permitting free-text message dispatch to the watchman's earpiece via POST `/device/{id}/message`.

### 7.8 Remaining Workspaces
- **Devices (`#view-devices`):** Comprehensive table listing Device ID, Device Name, Project Number, Status, Battery, Speed, Last Seen UAE timestamp, and action buttons.
- **Geofences (`#view-geofences`):** Interactive map for drawing circular or polygon geofences with radius and name inputs.
- **Incidents (`#view-incidents`):** Log of guard-submitted field incident reports with photographic evidence.
- **Announcements (`#view-announcements`):** Broadcast dispatch console to create text/TTS announcements across all devices or target specific IDs.
- **Analytics (`#view-analytics`):** Chart.js graphs tracking battery depletion curves, tracking state distributions, and distance traveled.
- **Reports (`#view-reports`):** Tabular export of route summaries, speed anomalies, and telemetry data (CSV/JSON export buttons).
- **Media (`#view-photos`):** Grid gallery of uploaded selfie check-ins with client liveness confidence badges and spoof classification tags.
- **Checkpoints (`#view-checkpoints`):** Log of logged checkpoint visits.
- **Bugs (`#view-bugs`):** Central dashboard for mobile bug and crash reports.

---

## 8. BACKEND ARCHITECTURE

### 8.1 Framework & Entry Point
- **Framework:** FastAPI `0.115.0` running under ASGI (Uvicorn / Gunicorn).
- **Primary Entry Point:** `backend/main.py` (2,227 lines).
- **Initialization Lifecycle (`startup_event` / `shutdown_event`):**
  - Initializes database tables via `Base.metadata.create_all(bind=engine)`.
  - Determines local LAN IP (`get_lan_ip()`) by opening a dummy UDP socket to `8.8.8.8:80`.
  - Registers mDNS service advertisement via Python `zeroconf.AsyncZeroconf()` broadcasting `_watchmen._tcp.local.` on port `8000` (or `$PORT`).
  - Gracefully deregisters mDNS on server shutdown.

### 8.2 API Routes Table
The backend exposes 45 REST and WebSocket endpoints in `backend/main.py`:

| Method | Endpoint | Purpose | Request Body / Params | Response | Used By |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/` | Serves SPA dashboard | None | `dashboard.html` | Web Browser |
| `GET` | `/health` | Server health & counts | None | JSON status & counts | Monitoring / Sidebar |
| `GET` | `/api/v1/config` | Discovery metadata | None | JSON backend config | Android Discovery |
| `GET` | `/data` | Historical telemetry | `limit`, `sinceminutes`, `deviceid`, `stride` | JSON array of points | Dashboard Map & Charts |
| `POST`| `/telemetry` | Ingests single telemetry point | JSON (`Telemetry` schema) | `{"status":"stored"}` | Android `TrackingService` |
| `POST`| `/telemetry_batch` | Ingests offline batch points | JSON array (`OfflineTelemetryPoint`) | `{"inserted": N}` | Android `OfflineGpsStorage` |
| `POST`| `/alert` | Ingests panic / violation alert | JSON (`PanicAlert` schema) | `{"status":"alert_logged"}` | Android `MainActivity` |
| `POST`| `/upload` | Multipart photo upload | Multipart Form (`image`, `device_id`, liveness) | `{"status":"success","id":N}`| Android `CameraCaptureActivity` |
| `POST`| `/incident` *(Def 1)*| Logs incident report | JSON (`IncidentReport` schema) | `{"status":"incident_logged"}`| Android `IncidentReportActivity` |
| `POST`| `/incident` *(Def 2)*| Logs incident report | Multipart Form (`file`, form fields) | `{"status":"ok","id":N}` | Web Dashboard |
| `GET` | `/incidents` | Fetches recent incidents | `hours`, `device_id`, `resolved` | JSON array of incidents | Dashboard Incidents View |
| `PUT` | `/incidents/{id}/resolve`| Marks incident resolved | URL param `{incident_id}` | `{"status":"resolved"}` | Dashboard Incidents View |
| `POST`| `/checkpoint` | Ingests checkpoint scan | JSON (`CheckpointVisit` schema) | `{"status":"checkpoint_logged"}`| Android / Field client |
| `GET` | `/checkpoints` | Fetches checkpoint logs | `device_id`, `hours` | JSON array of visits | Dashboard Checkpoints View |
| `POST`| `/crash` | Ingests crash stack trace | JSON (`device_id`, `stacktrace`, error) | `{"status":"crash_logged"}` | Android crash handler |
| `POST`| `/bug-report` | Ingests user bug report | Multipart Form (`payload`, `screenshot`) | `{"status":"bug_reported"}` | Mobile / Dashboard |
| `GET` | `/bug-reports` | Lists submitted bug reports | `resolved` | JSON array of bug reports | Dashboard Bugs View |
| `GET` | `/geofence/config`| Client geofence sync | `deviceid` query param | JSON geofence boundaries | Android `GeofenceManager` |
| `GET` | `/geofences` | Lists all geofences | None | JSON array of geofences | Dashboard Geofence View |
| `POST`| `/geofences` | Creates new geofence | JSON (`name`, `latitude`, `longitude`, radius) | `{"status":"created"}` | Dashboard Leaflet.Draw |
| `PUT` | `/geofences/{id}` | Updates existing geofence | JSON geofence fields | `{"status":"updated"}` | Dashboard Geofence View |
| `DELETE`| `/geofences/{id}` | Deletes geofence | URL param `{geofence_id}` | `{"status":"deleted"}` | Dashboard Geofence View |
| `GET` | `/geofence_events`| Lists zone enter/exit events| `hours`, `device_id` | JSON array of events | Dashboard Geofence View |
| `POST`| `/request-location`| Sets flag for live location poll | `device_id` query param | `{"status":"ok"}` | Dashboard Live Tracking |
| `GET` | `/check-location-request`| Polled by device for live ping | `device_id` query param | `{"request": bool}` | Android `TrackingService` |
| `GET` | `/alerts` | Fetches recent alerts | `hours`, `device_id` | JSON array of alerts | Dashboard Alerts View |
| `GET` | `/stats/liveness` | Aggregated liveness counts | None | JSON counts (verified, failed, etc.)| Dashboard Media View |
| `GET` | `/images` | Lists alerts with photos | `limit` query param | JSON photo alert array | Dashboard Media Gallery |
| `GET` | `/summary` | Computes route metrics | `device_id`, `since_minutes` | JSON distance, speed, steps | Dashboard Reports View |
| `GET` | `/anomalies` | Detects speed anomalies | `device_id`, `hours`, `speed_limit` | JSON speed violations | Dashboard Analytics View |
| `GET` | `/stats/battery` | Battery statistics | None | JSON `avg`, `min`, `max` | Dashboard Analytics View |
| `GET` | `/stats/distance` | Total distance traveled | None | JSON total meters/km | Dashboard Analytics View |
| `GET` | `/stats/states` | Tracking state distribution | None | JSON state counts | Dashboard Analytics View |
| `POST`| `/security-alert`| Ingests security alerts | JSON (`SecurityAlert` schema) | `{"status":"securityalert_logged"}`| Android Security Modules |
| `GET` | `/security-alerts`| Lists security alerts | `hours`, `device_id` | JSON array of alerts | Dashboard Alerts View |
| `GET` | `/device/{id}/health`| Computes device health | URL param `{device_id}` | JSON status, last_seen | Dashboard Device Drawer |
| `POST`| `/announcements` | Creates broadcast announcement| JSON announcement fields | `{"status":"created"}` | Dashboard Announcement View |
| `GET` | `/check-announcements`| Polled by device for announcements| `device_id` query param | JSON announcement or false | Android `TrackingService` |
| `GET` | `/announcements` | Lists announcements | `hours` query param | JSON announcement list | Dashboard Announcement View |
| `POST`| `/announcements/{id}/ack`| Acknowledges announcement | JSON (`AnnouncementAck` schema) | `{"status":"acknowledged"}` | Android / Dashboard |
| `POST`| `/announcements/{id}/retry`| Retries failed announcement | URL param `{id}`, `device_id` | `{"status":"retried"}` | Dashboard Announcement View |
| `GET` | `/devices/connected`| Active WebSocket devices | None | JSON connected device list | Dashboard Fleet Sync |
| `GET` | `/device/{id}/connection-status`| Diagnostic connection check | URL param `{device_id}` | JSON connection metadata | System Diagnostics |
| `POST`| `/device/{id}/command`| Sends remote command | JSON (`CommandRequest` schema) | `{"status":"sent"}` | Dashboard Command Center |
| `POST`| `/device/{id}/message`| Sends chat message | JSON (`message`, `urgent`) | `{"status":"sent"}` | Dashboard Chat Panel |
| `WS`  | `/ws` | Bi-directional WebSocket | Query: `type=device\|dashboard`, `deviceid`| Continuous duplex stream | Android & Dashboard |

### 8.3 WebSocket Endpoint Implementation (`/ws`)
- **Connection Router:** Query parameter `type`:
  - `type=device`: Requires `deviceid`. Handled by `manager.connect(ws, "device", device_id)`. If an existing WebSocket for that device ID is active, it is cleanly closed with code `1000` to enforce single-session consistency.
  - `type=dashboard`: Handled by `manager.connect(ws, "dashboard")`. Appends the connection to `self.dashboard_connections`.
- **Broadcasting Engine (`ConnectionManager.broadcast`):** Iterates through `dashboard_connections` sending JSON payloads. Automatically identifies dead sockets and purges them.
- **Direct Device Command Route (`manager.send_to_device`):** Looks up the target device's socket in `self.active_connections[device_id]` and dispatches the payload directly.
- **Inbound Device Message Handling:**
  - `device_handshake`: Acknowledges and broadcasts `device_handshake_ack` to dashboards.
  - `ping` / `keepalive`: Responds immediately with `{"type": "pong"}`.
  - `command_ack`, `message_ack`, `device_message`: Broadcasts directly to all connected dashboards.

---

## 9. DATABASE — CURRENT STATE

### Database Overview
- **Storage Engine:** SQLite 3
- **Primary Database File:** `backend/watchmen_test.db` (File size: 2,576,384 bytes / ~2.58 MB)
- **Secondary Test File:** `backend/test.db` (212,992 bytes, empty schemas)
- **Root Placeholder File:** `watchmen_test.db` at repository root (0 bytes, unused)
- **Connection Configuration:** `DATABASE_URL = os.environ.get("DATABASE_URL", "sqlite:///./watchmen_test.db")`
- **Total Tables:** 12 domain tables + 1 `sqlite_sequence` table.

---

### Table: `telemetry` (7,005 rows)
Stores the primary historical and live GPS and sensor stream.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Auto-incrementing primary key |
| `device_id` | `VARCHAR` | YES | `None` |  | Unique composite device identifier |
| `device_name` | `VARCHAR` | YES | `None` |  | Human-readable device nickname |
| `project_number`| `VARCHAR` | YES | `None` |  | Site/project identifier code |
| `latitude` | `FLOAT` | NO | `None` |  | WGS-84 latitude coordinate |
| `longitude` | `FLOAT` | NO | `None` |  | WGS-84 longitude coordinate |
| `speed` | `FLOAT` | YES | `None` |  | Speed in meters per second |
| `bearing` | `FLOAT` | YES | `None` |  | Directional heading angle (degrees) |
| `altitude` | `FLOAT` | YES | `None` |  | Height above sea level (meters) |
| `accuracy` | `FLOAT` | YES | `None` |  | Horizontal accuracy radius (meters) |
| `steps` | `INTEGER` | YES | `None` |  | Step counter increment |
| `battery` | `FLOAT` | YES | `None` |  | Device battery percentage (0-100) |
| `offline` | `INTEGER` | YES | `None` |  | Offline indicator flag (1=offline, 0=live) |
| `tracking_state`| `VARCHAR` | YES | `None` |  | Motion state (`MOVING`, `STOPPED`, etc.) |
| `movement_context`| `JSON` | YES | `None` |  | JSON object with displacement & variance |
| `device_health` | `JSON` | YES | `None` |  | JSON object with client diagnostic state |
| `timestamp` | `DATETIME`| YES | `None` |  | UTC timestamp of telemetry capture |
| `created_at` | `DATETIME`| YES | `None` |  | UTC timestamp of database insertion |

**Indexes:** `ix_telemetry_id`, `ix_telemetry_device_id`, `ix_telemetry_project_number`, `ix_telemetry_tracking_state`, `ix_telemetry_timestamp`.

---

### Table: `security_alerts` (1,541 rows)
Stores automated anti-fraud and device security integrity violation events.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Auto-incrementing primary key |
| `device_id` | `VARCHAR` | YES | `None` |  | Associated device identifier |
| `alert_type` | `VARCHAR` | YES | `None` |  | Code (`sim_removed`, `fake_movement`, `data_delay`, `gps_spoofing`) |
| `details` | `TEXT` | YES | `None` |  | Text details / diagnostic justification |
| `timestamp` | `DATETIME`| YES | `None` |  | Event timestamp (UTC) |

**Indexes:** `ix_security_alerts_id`, `ix_security_alerts_device_id`, `ix_security_alerts_alert_type`, `ix_security_alerts_timestamp`.

---

### Table: `alerts` (4 rows)
Stores guard-initiated emergency panic alerts and biometric hourly check-in photo records.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Auto-incrementing primary key |
| `device_id` | `VARCHAR` | YES | `None` |  | Associated device identifier |
| `alert_type` | `VARCHAR` | YES | `None` |  | Alert classification (`PANIC`, `hourly_checkin`, `suspicious_override`) |
| `violation_type`| `VARCHAR` | YES | `None` |  | Optional sub-violation classification |
| `latitude` | `FLOAT` | YES | `None` |  | Latitude at time of alert |
| `longitude` | `FLOAT` | YES | `None` |  | Longitude at time of alert |
| `accuracy` | `FLOAT` | YES | `None` |  | Location accuracy |
| `battery` | `FLOAT` | YES | `None` |  | Battery level at time of alert |
| `reason` | `TEXT` | YES | `None` |  | Trigger reason |
| `details` | `TEXT` | YES | `None` |  | Additional alert text |
| `priority` | `VARCHAR` | YES | `None` |  | Severity level (`NORMAL`, `HIGH`, `CRITICAL`)|
| `timestamp` | `DATETIME`| YES | `None` |  | Event timestamp (UTC) |
| `resolved` | `BOOLEAN` | YES | `None` |  | Control center resolution state |
| `image_path` | `VARCHAR` | YES | `None` |  | Filename of uploaded selfie in `uploads/` |
| `liveness_verified`|`BOOLEAN`| YES | `None` |  | Client-side ML Kit liveness verification pass |
| `liveness_confidence`|`FLOAT`| YES | `None` |  | Liveness score (0.0 to 1.0) |
| `spoof_type` | `VARCHAR` | YES | `None` |  | Spoof classification (`none`, `photo_static`, `video_loop`) |
| `liveness_reasons`| `TEXT` | YES | `None` |  | Semicolon-separated check failure reasons |
| `override_used`| `BOOLEAN` | YES | `None` |  | Flag if user bypassed verification via override |

**Indexes:** `ix_alerts_id`, `ix_alerts_device_id`, `ix_alerts_alert_type`, `ix_alerts_timestamp`.

---

### Table: `geofences` (0 rows)
Stores circular and polygonal geofence boundary configurations.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `name` | `VARCHAR` | YES | `None` |  | Descriptive zone name |
| `type` | `VARCHAR` | YES | `None` |  | Boundary geometry type (`circle` or `polygon`) |
| `latitude` | `FLOAT` | YES | `None` |  | Circle center latitude |
| `longitude` | `FLOAT` | YES | `None` |  | Circle center longitude |
| `radius` | `FLOAT` | YES | `None` |  | Circle radius in meters |
| `coordinates` | `JSON` | YES | `None` |  | Vertex array for polygon boundaries |
| `color` | `VARCHAR` | YES | `None` |  | Hex color string for map rendering |
| `enabled` | `BOOLEAN` | YES | `None` |  | Active toggle state |
| `created_at` | `DATETIME`| YES | `None` |  | Creation timestamp |
| `updated_at` | `DATETIME`| YES | `None` |  | Last modification timestamp |

**Indexes:** `ix_geofences_id`, `ix_geofences_name`.

---

### Table: `geofence_events` (0 rows)
Records real-time entry and exit breaches across active geofences.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `device_id` | `VARCHAR` | YES | `None` |  | Associated device |
| `geofence_id` | `INTEGER` | YES | `None` | FK | Foreign key -> `geofences.id` |
| `geofence_name`| `VARCHAR` | YES | `None` |  | Cached name of breached zone |
| `event_type` | `VARCHAR` | YES | `None` |  | Transition state (`ENTER` or `EXIT`) |
| `latitude` | `FLOAT` | YES | `None` |  | Breach latitude |
| `longitude` | `FLOAT` | YES | `None` |  | Breach longitude |
| `timestamp` | `DATETIME`| YES | `None` |  | Event timestamp |

**Indexes:** `ix_geofence_events_id`, `ix_geofence_events_device_id`, `ix_geofence_events_event_type`, `ix_geofence_events_timestamp`.

---

### Table: `incidents` (0 rows)
Stores security incidents filed from the field or dashboard.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `device_id` | `VARCHAR` | YES | `None` |  | Submitting device ID |
| `incident_type`| `VARCHAR` | YES | `None` |  | Category (Theft, Trespassing, Fire, etc.) |
| `description` | `TEXT` | YES | `None` |  | Guard narrative description |
| `latitude` | `FLOAT` | YES | `None` |  | Incident location latitude |
| `longitude` | `FLOAT` | YES | `None` |  | Incident location longitude |
| `accuracy` | `FLOAT` | YES | `None` |  | Location accuracy |
| `has_photo` | `BOOLEAN` | YES | `None` |  | Flag if photo evidence is attached |
| `photo_path` | `VARCHAR` | YES | `None` |  | Filename in `incidents/` directory |
| `timestamp` | `DATETIME`| YES | `None` |  | Incident occurrence timestamp |
| `resolved` | `BOOLEAN` | YES | `None` |  | Case resolution state |

**Indexes:** `ix_incidents_id`, `ix_incidents_device_id`, `ix_incidents_incident_type`, `ix_incidents_timestamp`.

---

### Table: `checkpoints` (0 rows)
Logs verified checkpoint guard visits along patrol routes.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `device_id` | `VARCHAR` | YES | `None` |  | Visiting device ID |
| `checkpoint_id`| `VARCHAR` | YES | `None` |  | Unique QR/NFC code string |
| `checkpoint_name`|`VARCHAR`| YES | `None` |  | Human-readable checkpoint label |
| `latitude` | `FLOAT` | YES | `None` |  | GPS latitude at scan |
| `longitude` | `FLOAT` | YES | `None` |  | GPS longitude at scan |
| `timestamp` | `DATETIME`| YES | `None` |  | Scan timestamp |

**Indexes:** `ix_checkpoints_id`, `ix_checkpoints_device_id`, `ix_checkpoints_checkpoint_id`, `ix_checkpoints_timestamp`.

---

### Table: `announcements` (0 rows)
Stores control room broadcast audio/text announcements.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `title` | `VARCHAR` | NO | `None` |  | Announcement header |
| `message` | `TEXT` | NO | `None` |  | Announcement content |
| `priority` | `VARCHAR` | YES | `None` |  | Severity level (`NORMAL`, `HIGH`, `CRITICAL`)|
| `language` | `VARCHAR` | YES | `None` |  | Language code (`en`, `ur`, `hi`, `ar`) |
| `tts` | `BOOLEAN` | YES | `None` |  | Enable text-to-speech audio playback |
| `vibrate` | `BOOLEAN` | YES | `None` |  | Trigger device vibration |
| `raise_alert` | `BOOLEAN` | YES | `None` |  | Require explicit acknowledgment receipt |
| `device_id` | `VARCHAR` | YES | `None` |  | Specific target device (`NULL` = broadcast all)|
| `created_at` | `DATETIME`| YES | `None` |  | Creation timestamp |
| `expires_at` | `DATETIME`| YES | `None` |  | Expiration timestamp |

**Indexes:** `ix_announcements_id`.

---

### Table: `announcement_receipts` (0 rows)
Tracks delivery and guard acknowledgment status per announcement per device.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `announcement_id`|`INTEGER` | YES | `None` | FK | Foreign key -> `announcements.id` (CASCADE) |
| `device_id` | `VARCHAR` | YES | `None` |  | Target device ID |
| `delivered_at`| `DATETIME`| YES | `None` |  | Delivery timestamp |
| `acked_at` | `DATETIME`| YES | `None` |  | Acknowledgment timestamp |
| `ack_status` | `VARCHAR` | YES | `None` |  | Status (`PLAYED`, `DISMISSED`, `FAILED`) |
| `retry_count` | `INTEGER` | YES | `None` |  | Delivery retry count (limit 3) |

**Indexes:** `ix_announcement_receipts_announcement_id`, `ix_announcement_receipts_device_id`.

---

### Table: `bug_reports` (0 rows)
Stores guard bug reports and application feedback.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `device_id` | `VARCHAR` | NO | `None` |  | Reporting device ID |
| `app_version` | `VARCHAR` | YES | `None` |  | Client application build version |
| `os_version` | `VARCHAR` | YES | `None` |  | Android OS release version |
| `device_model` | `VARCHAR` | YES | `None` |  | Hardware model string |
| `title` | `VARCHAR` | NO | `None` |  | Bug summary |
| `description` | `TEXT` | NO | `None` |  | Bug details narrative |
| `severity` | `VARCHAR` | YES | `None` |  | Bug severity (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`) |
| `screenshot_path`|`VARCHAR`| YES | `None` |  | Filename in `bugs/` directory |
| `logs` | `TEXT` | YES | `None` |  | Attached device logs |
| `created_at` | `DATETIME`| YES | `None` |  | Report submission timestamp |
| `resolved` | `BOOLEAN` | YES | `None` |  | Resolution status |

**Indexes:** `ix_bug_reports_id`, `ix_bug_reports_device_id`.

---

### Table: `crash_reports` (0 rows)
Stores uncaught application exception stack traces captured by the Android crash handler.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `device_id` | `VARCHAR` | YES | `None` |  | Device ID |
| `crash_time` | `DATETIME`| YES | `None` |  | Crash timestamp |
| `error_message`| `TEXT` | YES | `None` |  | Top-level exception message |
| `stacktrace` | `TEXT` | YES | `None` |  | Full Kotlin/Java stack trace string |
| `created_at` | `DATETIME`| YES | `None` |  | Log creation timestamp |

**Indexes:** `ix_crash_reports_id`, `ix_crash_reports_device_id`.

---

### Table: `trial_failures` (0 rows)
Designed to audit failed facial liveness attempts logged by `TrialManager`.

| Column | Type | Nullable | Default | Key | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | NO | `None` | PK | Primary key |
| `device_id` | `VARCHAR` | YES | `None` |  | Device ID |
| `attempt_number`|`INTEGER` | YES | `None` |  | Attempt sequence index |
| `confidence` | `FLOAT` | YES | `None` |  | Measured liveness confidence |
| `reasons` | `TEXT` | YES | `None` |  | Semicolon-separated failure reasons |
| `timestamp` | `DATETIME`| YES | `None` |  | Attempt timestamp |

**Indexes:** `ix_trial_failures_id`, `ix_trial_failures_device_id`.

---

## 10. HOW DATA IS STORED

### Complete Telemetry Storage Lifecycle

```text
       Mobile Device
             │
       LocationCallback fires (every 5s)
             │
       GpsSpoofingDetector checks (isMock, velocity > 100 m/s, teleportation)
             │ (passed)
       KalmanPedestrian filter smooths coordinates
             │
       OfflineGpsStorage appends point to local session file (.track)
             │
       Transmission Evaluator checks shouldSendUpdate() (state interval or displacement)
             │ (time or distance threshold met)
       HTTP POST /telemetry (JSON payload via OkHttp)
             │
             ▼
       FastAPI Backend: receive_telemetry()
             │
       Pydantic Telemetry Schema Validation & Alias Normalization
             │
       Duplicate Detection Query:
       SELECT id FROM telemetry
       WHERE device_id = :id AND latitude = :lat AND longitude = :lon
         AND timestamp BETWEEN (:ts - 2s) AND (:ts + 2s)
             │
             ├── [DUPLICATE FOUND] ──► Discard DB write, broadcast duplicate ping, return "duplicate"
             │
             └── [UNIQUE POINT]
                     │
                     ├── Write to Database:
                     │   INSERT INTO telemetry (device_id, latitude, longitude, speed, ...)
                     │   COMMIT TRANSACTION
                     │
                     ├── Evaluate Geofences:
                     │   Compute Haversine distance against all enabled circle geofences
                     │   Evaluate enter/exit with 15m hysteresis buffer & 10s cooldown
                     │   INSERT INTO geofence_events IF state transitioned
                     │
                     └── Broadcast to Web Dashboards:
                         manager.broadcast({lat, lon, speed, battery, deviceid, trackingstate, ...})
```

- **Insertion vs Updates:** Telemetry data is strictly **append-only**. Telemetry rows are never mutated or updated after insert.
- **Duplicate Suppression (`backend/main.py:543-565`):** The backend queries for an existing record with identical `device_id`, `latitude`, and `longitude` within a $\pm 2$-second window. If matched, the insertion is skipped.
- **Timestamp Handling:** All timestamps are ingested as UTC (`parse_timestamp()` parses epoch integer milliseconds or ISO-8601 strings). A server helper `to_uae()` projects UTC into United Arab Emirates standard time (Gulf Standard Time, `UTC+4`) for operational dashboard display.
- **Historical Retention:** Telemetry records persist indefinitely in SQLite; no automated cron or SQL cleanup script is configured in the repository. Hard query limits are enforced: `GET /data` enforces `Query(200, ge=0, le=200000)` and supports integer `stride` downsampling.
- **Offline Synchronization:**
  - If single HTTP POSTs fail on mobile, `TelemetryCache` writes individual JSON objects to private internal storage.
  - Continuous route tracking writes to `.track` files via `OfflineGpsStorage`. Upon network restoration, `syncOfflineSessions()` sends points in batches to `POST /telemetry_batch`. The backend processes them within a single transaction, commits, and broadcasts an `offline_batch_ingested` event.

---

## 11. DATA FLOW

### 11.1 Mobile $\rightarrow$ Backend

```text
[Mobile Client]                                              [FastAPI Backend]
       │                                                             │
       ├──── POST /telemetry (Live JSON) ───────────────────────────►│ (process_telemetry_payload)
       ├──── POST /telemetry_batch (Offline batch JSON) ────────────►│ (bulk commit to telemetry)
       ├──── POST /alert (Panic JSON) ──────────────────────────────►│ (writes to alerts)
       ├──── POST /security-alert (Anti-fraud JSON) ────────────────►│ (writes to security_alerts)
       ├──── POST /upload (Multipart selfie + liveness) ────────────►│ (saves image to uploads/)
       ├──── POST /incident (Field report + photo) ─────────────────►│ (saves to incidents/)
       ├──── POST /crash (Uncaught stacktrace JSON) ────────────────►│ (writes to crash_reports)
       └──── WS send: device_handshake / command_ack ───────────────►│ (ConnectionManager)
```

### 11.2 Backend $\rightarrow$ Database

```text
[FastAPI Backend]                                            [SQLite Database]
       │                                                             │
       ├─── INSERT INTO telemetry ──────────────────────────────────►│ (Single & batch points)
       ├─── INSERT INTO alerts ─────────────────────────────────────►│ (Panic & photo check-ins)
       ├─── INSERT INTO security_alerts ────────────────────────────►│ (Tamper & spoof events)
       ├─── INSERT INTO geofence_events ────────────────────────────►│ (Automated breach logs)
       ├─── INSERT INTO incidents ──────────────────────────────────►│ (Field incident reports)
       └─── INSERT INTO checkpoints ────────────────────────────────►│ (Verified visits)
```

### 11.3 Backend $\rightarrow$ Dashboard

```text
[FastAPI Backend]                                            [Web Dashboard]
       │                                                             │
       ├─── WS Broadcast: live telemetry point ─────────────────────►│ (updateLivePosition)
       ├─── WS Broadcast: device_connected / disconnected ──────────►│ (connectedDevices set)
       ├─── WS Broadcast: command_ack / message_ack ────────────────►│ (Terminal log & toast)
       ├─── WS Broadcast: alert / securityalert ────────────────────►│ (Audio alert chime)
       ├─── WS Broadcast: geofence_enter / geofence_exit ───────────►│ (Map alert notification)
       ├─── WS Broadcast: new_photo_alert ──────────────────────────►│ (Photo gallery refresh)
       └─── HTTP GET Responses (/data, /alerts, /geofences, etc.) ──►│ (Initial load & refresh)
```

### 11.4 Dashboard $\rightarrow$ Backend

```text
[Web Dashboard]                                              [FastAPI Backend]
       │                                                             │
       ├─── POST /device/{device_id}/command ───────────────────────►│ (Validates device connected)
       ├─── POST /device/{device_id}/message ───────────────────────►│ (Validates device connected)
       ├─── POST /request-location?device_id=... ───────────────────►│ (Sets pending flag)
       ├─── POST /geofences (Create zone JSON) ─────────────────────►│ (Inserts into geofences)
       ├─── PUT  /incidents/{id}/resolve ───────────────────────────►│ (Updates incident row)
       └─── POST /announcements (Broadcast announcement) ───────────►│ (Inserts into announcements)
```

### 11.5 Backend $\rightarrow$ Mobile

```text
[FastAPI Backend]                                            [Mobile Client]
       │                                                             │
       ├─── WS push: {"type":"command","command":"START_SIREN"} ────►│ (SirenController starts)
       ├─── WS push: {"type":"command","command":"GET_LOCATION"} ───►│ (Immediate GPS dispatched)
       ├─── WS push: {"type":"message","message":"...","urgent":true}►│ (TTS voice playback)
       ├─── WS push: {"type":"announcement", ...} ──────────────────►│ (TTS + vibration + notif)
       └─── Polling response: /check-location-request -> true ──────►│ (Forces immediate telemetry)
```

---

## 12. API + WEBSOCKET COMMUNICATION MATRIX

| Feature | Mobile Implementation | Backend REST Endpoint | WebSocket Message Type | Database Table | Dashboard Implementation |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Live Location** | `TrackingService.sendTelemetry` | `POST /telemetry` | `data.lat`, `data.lon` payload | `telemetry` | `updateLivePosition()`, marker update |
| **Offline Telemetry** | `OfflineGpsStorage.syncOffline` | `POST /telemetry_batch`| `offline_batch_ingested` | `telemetry` | Batch notification, trail refresh |
| **Panic Alarm** | `MainActivity.sendPanicAlert` | `POST /alert` | `data.alert = true` | `alerts` | Red marker pulse, audio chime |
| **Hourly Photo** | `CameraCaptureActivity` | `POST /upload` | `new_photo_alert` | `alerts` | Media gallery image rendering |
| **Face Liveness** | `FaceLivenessDetector` | `POST /upload` | `liveness_data` | `alerts` | Verified / Spoof confidence badge |
| **GPS Spoofing** | `GpsSpoofingDetector` | `POST /security-alert` | `securityalert` | `security_alerts` | Security alert toast & table |
| **Movement Fraud** | `MovementAuthenticityDetector` | `POST /security-alert` | `securityalert` | `security_alerts` | Security alert toast & table |
| **SIM Removal** | `SimCardMonitor` | `POST /security-alert` | `securityalert` | `security_alerts` | Security alert toast & table |
| **Remote Siren** | `SirenController` | `POST /device/{id}/command`| `command` / `command_ack` | None (In-memory) | Command Center Start/Stop buttons |
| **Live Ping Poll** | `checkForLocationRequest` | `GET /check-location-request`| None | None (In-memory dict)| "Request Live Location" button |
| **Geofencing** | `GeofenceManager` (Sync) | `GET /geofence/config` | `geofence_enter`, `exit` | `geofences`, `geofence_events` | Leaflet.Draw editor & polygon layers |
| **Incident Reports**| `IncidentReportActivity` | `POST /incident` | `incident` | `incidents` | Incident reports workspace & photos |
| **Announcements** | `TrackingService.handleAnnounce`| `GET /check-announcements`| `announcement_created` | `announcements`, `announcement_receipts`| Announcement composer workspace |
| **Text Chat** | `TrackingService.handleWSMessage`| `POST /device/{id}/message`| `message` / `message_ack` | None (In-memory) | Command Center terminal console |
| **Checkpoints** | Placeholder dialog | `POST /checkpoint` | `checkpoint` | `checkpoints` | Checkpoint log table |
| **Crash Reports** | `installCrashHandler` | `POST /crash` | None | `crash_reports` | Bug & Crash Reports table |

---

## 13. FOLDER STRUCTURE

```text
WatchmenTracker/
│
├── .gitignore                                 # Root Git ignore (build, caches, local.properties)
├── build.gradle.kts                           # Root Gradle project plugins (AGP, Kotlin)
├── gradle.properties                          # JVM memory (-Xmx2048m), AndroidX, nonTransitiveRClass
├── gradlew / gradlew.bat                      # Gradle wrapper executables
├── local.properties                           # Local Android SDK directory path
├── settings.gradle.kts                        # Gradle module includes (":app") & repositories
├── test_websocket.py                          # Diagnostic WebSocket test script
├── watchmen_test.db                           # 0-byte placeholder at root (inactive)
│
├── android/                                   # Empty directory (redundant artifact)
│
├── app/                                       # [SOURCE CODE - ANDROID CLIENT MODULE]
│   ├── .gitignore
│   ├── build.gradle.kts                       # App dependencies, compileSdk 34, CameraX, ML Kit
│   ├── proguard-rules.pro                     # ProGuard rules for release shrinking
│   └── src/
│       ├── test/java/com/watchmen/tracker/    # [TEST FILES - ANDROID]
│       │   └── BackendEndpointManagerTest.kt  # Unit test for URL resolution & normalization
│       └── main/
│           ├── AndroidManifest.xml            # Permissions, Services, Receivers, FileProvider
│           ├── java/com/watchmen/tracker/     # [KOTLIN SOURCE CODE]
│           │   ├── Announcement.kt            # Data model for broadcast announcements
│           │   ├── AppGuideActivity.kt        # User onboarding guide container
│           │   ├── AppGuideAdapter.kt         # Pager adapter for onboarding slides
│           │   ├── BackendDiscoveryManager.kt # mDNS/NSD zeroconf LAN service resolution
│           │   ├── BackendEndpointManager.kt  # Dynamic server URL resolution singleton
│           │   ├── BootReceiver.kt            # Auto-restart tracking on device reboot
│           │   ├── CameraCaptureActivity.kt   # CameraX selfie capture with ML Kit liveness
│           │   ├── DeviceAdminReceiver.kt     # Android Device Admin enforcement receiver
│           │   ├── FaceAnalyzer.kt            # Frame analyzer bridging CameraX to ML Kit
│           │   ├── FaceLivenessDetector.kt    # Liveness heuristics (blink, head turn, quality)
│           │   ├── FaceOverlayView.kt         # Custom UI view drawing face bounding oval
│           │   ├── GeofenceActionHandler.kt   # Geofence event receiver and processor
│           │   ├── GpsSpoofingDetector.kt     # Anti-spoofing engine (mock flags, jump check)
│           │   ├── HourlyPhotoWorker.kt       # WorkManager periodic hourly check-in prompt
│           │   ├── IncidentReportActivity.kt  # On-site incident reporting form & photo attach
│           │   ├── LivenessReason.kt          # Enum codes for liveness failure justifications
│           │   ├── MainActivity.kt            # Primary guard dashboard & panic triggers
│           │   ├── MovementAuthenticityDetector.kt # Accelerometer/gyroscope anti-fraud
│           │   ├── MultilingualTTS.kt         # Android Text-to-Speech wrapper (en, ur, hi, ar)
│           │   ├── OfflineGpsStorage.kt       # Local disk session queue (.track files)
│           │   ├── PhotoCaptureReceiver.kt    # Alarm receiver for photo capture prompts
│           │   ├── SetupActivity.kt           # Initial setup & device provisioning UI
│           │   ├── SimCardMonitor.kt          # TelephonyManager SIM removal monitor
│           │   ├── SirenController.kt         # Audible emergency siren & vibration engine
│           │   ├── SmsReceiver.kt             # Minimal receiver for SMS role eligibility
│           │   ├── SpeechTable.kt             # Predefined TTS phrases across languages
│           │   ├── SplashActivity.kt          # App launch splash and setup router
│           │   ├── StringProvider.kt          # String resource resolution abstraction
│           │   ├── TrackingJobService.kt      # JobScheduler backup to survive OS doze mode
│           │   ├── TrackingService.kt         # Main location tracking foreground service
│           │   ├── TrialManager.kt            # Liveness failure tracking & trial counts
│           │   └── WarningCooldownManager.kt  # Rate-limiter for spoken audio alerts
│           └── res/                           # [ANDROID RESOURCES]
│               ├── drawable/                  # Vector icons, logos, glassmorphism cards
│               ├── layout/                    # XML layouts for activities and views
│               ├── mipmap-*/                  # App launch icons
│               ├── values/                    # Strings, colors, styles, arrays
│               ├── values-ar/                 # Arabic language translations
│               ├── values-hi/                 # Hindi language translations
│               ├── values-ur/                 # Urdu language translations
│               └── xml/                       # Network security, file provider, device admin
│
└── backend/                                   # [SOURCE CODE - FASTAPI BACKEND & DASHBOARD]
    ├── .gitignore
    ├── main.py                                # Primary FastAPI server, models, APIs, WebSockets
    ├── requirements.txt                       # Python library dependencies
    ├── test_discovery.py                      # Integration test for /api/v1/config & /health
    ├── test_ws_e2e.py                         # End-to-end WebSocket test for commands & ACKs
    ├── watchmen_test.db                       # [ACTIVE DATABASE] SQLite database (~2.58 MB)
    ├── test.db                                # Secondary SQLite database (~213 KB)
    ├── bugs/                                  # Uploaded bug screenshot storage
    ├── incidents/                             # Uploaded incident photo storage
    ├── outputs/                               # [DATA ARTIFACTS - ML EXPERIMENTATION]
    │   ├── daily_features_with_anomalies.csv  # Telemetry feature dataset with anomaly flags
    │   ├── prob_route_map.html                # Route probability visualization
    │   └── seq_autoencoder.h5                 # Keras autoencoder model for route anomalies
    ├── static/                                # [WEB DASHBOARD FRONTEND]
    │   ├── chart.min.js                       # Vendored Chart.js library
    │   ├── dashboard.html                     # Enterprise single-page dashboard HTML
    │   ├── dashboard.js                       # Dashboard application logic (4,249 lines)
    │   ├── logo_watchmen.png                  # Brand logo
    │   └── css/
    │       └── watchmen-elite.css             # Complete design system stylesheet (2,443 lines)
    ├── templates/                             # Jinja templates directory (contains logo)
    ├── uploads/                               # [UPLOAD DATA] Real JPEG selfie check-in photos
    └── videos/                                # Video uploads directory
```

---

## 14. CONFIGURATION & ENVIRONMENT

### Configuration Variables & Defaults

| Setting | Location | Default Value | Purpose |
| :--- | :--- | :--- | :--- |
| `DATABASE_URL` | Environment / `main.py:62` | `sqlite:///./watchmen_test.db` | Database connection string |
| `PORT` | Environment / `main.py:695` | `8000` | HTTP and WebSocket server port |
| Outbound IP | Computed / `main.py:667` | Output of socket to `8.8.8.8` | LAN IP advertised in mDNS records |
| Service Type | Defined / `BackendDiscoveryManager.kt:22` | `_watchmen._tcp` | Zeroconf / DNS-SD service identifier |
| Fallback Cloud URL| Defined / `test_websocket.py:15` | `wss://watchmen-backend.onrender.com` | Standalone test fallback target |
| Cleartext Traffic| XML / `network_security_config.xml` | `cleartextTrafficPermitted="true"` | Permits unencrypted HTTP to LAN IP |
| GPS Min Update | Defined / `TrackingService.kt:1473` | `3_000L` (3 seconds) | FusedLocation min interval |
| GPS Max Interval | Defined / `TrackingService.kt:1472` | `5_000L` (5 seconds) | FusedLocation target interval |
| Geofence Hysteresis| Defined / `main.py:451` | `15.0` meters | Prevents edge jitter false enter/exit |
| Geofence Cooldown | Defined / `main.py:452` | `10` seconds | Per-device event rate-limiter |

*Security Confirmation:* No active secrets, cryptographic private keys, or API tokens were found hardcoded in source code files. (Production credentials must be provided via environment variables).

---

## 15. DEPENDENCIES

### Mobile Dependencies (`app/build.gradle.kts`)
- `androidx.core:core-ktx:1.13.1`: Kotlin extensions for Android core framework.
- `androidx.appcompat:appcompat:1.7.0`: Backward-compatible UI components and ActionBar.
- `com.google.android.material:material:1.12.0`: Material Design components (cards, text inputs).
- `com.google.android.gms:play-services-location:21.3.0`: Fused Location Provider and geofencing.
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`: Android main-thread dispatcher and scopes.
- `androidx.work:work-runtime-ktx:2.9.0`: WorkManager for background periodic check-in jobs.
- `androidx.camera:camera-camera2:1.3.4`: CameraX implementation targeting Camera2 API.
- `com.google.mlkit:face-detection:16.1.7`: On-device machine learning face detection.
- `com.squareup.okhttp3:okhttp:4.12.0`: HTTP client and RFC 6455 WebSocket protocol engine.
- `junit:junit:4.13.2`: Unit testing framework.

### Backend Dependencies (`backend/requirements.txt`)
- `fastapi>=0.115.0`: Core asynchronous web and API framework.
- `uvicorn[standard]>=0.30.0`: Production ASGI server with uvloop and httptools.
- `sqlalchemy>=2.0.35`: Relational database ORM.
- `psycopg2-binary>=2.9.9`: PostgreSQL database adapter for production scaling.
- `websockets>=13.0`: WebSocket protocol implementation.
- `pydantic>=2.10.0`: Strict type validation and model schemas.
- `python-multipart>=0.0.12`: Parsing multipart form data for file/photo uploads.
- `zeroconf>=0.131.0`: Multicast DNS (mDNS) service registration and advertisement.
- `jinja2>=3.1.4`: Server-side templating engine.
- `gunicorn>=23.0.0`: Production WSGI/ASGI process manager.

### Dashboard Dependencies (CDN & Vendored)
- `Leaflet.js 1.9.4`: Geospatial mapping and layer orchestration.
- `Leaflet.Draw 1.0.4`: Interactive vector drawing tools for geofence geometry.
- `Chart.js 4.x`: Canvas-based chart rendering (vendored in `backend/static/chart.min.js`).
- `FontAwesome 6.5.1`: UI iconography.

---

## 16. TESTING

| Test | File | What It Tests | Current Status |
| :--- | :--- | :--- | :--- |
| **Backend Discovery & Health** | `backend/test_discovery.py` | Direct async test of `get_discovery_config()` (`/api/v1/config`) and `health_check()` (`/health`). | Validated — Both assertions pass. |
| **WebSocket E2E & Commands** | `backend/test_ws_e2e.py` | Launches Uvicorn server on port 8765, connects mock dashboard and mock device WebSockets, tests `device_handshake`, triggers `START_SIREN` and `STOP_SIREN` via REST, validates device receipt and dashboard broadcast of `command_ack`. | Validated — Full bi-directional loop passes. |
| **WebSocket Diagnostic Probe**| `test_websocket.py` | Standalone CLI script testing WebSocket connectivity against a local or Render cloud backend with ping/pong echo. | Existing script — manual utility. |
| **Endpoint Manager Unit Test**| `app/src/test/java/.../BackendEndpointManagerTest.kt` | Tests `BackendEndpointManager`: unconfigured state, URL scheme normalization (`http`, `https`, `ws`, `wss`), bind IP rejection (`0.0.0.0`), path segment and query encoding, and change listener firing. | Existing test file — execution requires Android Gradle runtime. |

---

## 17. CURRENT FUNCTIONALITY

| Feature | Status | Mobile | Backend | Dashboard | Notes |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **GPS Location Tracking** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | FusedLocation + Kalman filter $\rightarrow$ POST `/telemetry` $\rightarrow$ Leaflet map |
| **Offline Telemetry Queue** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Local disk buffers $\rightarrow$ POST `/telemetry_batch` $\rightarrow$ Map replay |
| **Real-Time WebSocket Stream**| ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Live duplex telemetry, device handshakes, and broadcasts |
| **Anti-Spoofing Detection** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Mock location, velocity jumps, teleportation checks |
| **Movement Authenticity** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Accelerometer variance & gyro checks for cradle shaking |
| **SIM Removal Monitor** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | TelephonyManager state listener fires security alert |
| **Remote Siren Control** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Control center triggers 100% audible siren and vibration |
| **Emergency Panic Alert** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Panic button transmits location to server + SMS to supervisor |
| **Facial Liveness Check-in**| ✅ IMPLEMENTED | ✅ | ✅ | ✅ | CameraX + ML Kit evaluates blinks, head turns, and motion |
| **Geofencing Engine** | ✅ IMPLEMENTED | 🟡 | ✅ | ✅ | Server evaluates 15m hysteresis; client validation bypassed |
| **Remote Audio Announcements**| ✅ IMPLEMENTED| ✅ | ✅ | ✅ | Multilingual TTS speaks announcements in en, ur, hi, ar |
| **Two-Way Text Chat** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Terminal console dispatches message to device TTS |
| **Incident Reporting** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Field incident form attaches camera photo |
| **Bug & Crash Reporting** | ✅ IMPLEMENTED | ✅ | ✅ | ✅ | Uncaught exception handler and bug report upload |
| **Local mDNS Discovery** | ✅ IMPLEMENTED | ✅ | ✅ | N/A | Zeroconf advertises backend; Android resolves dynamic IP |
| **User Authentication** | 🔴 NOT IMPLEMENTED| 🔴 | 🔴 | 🔴 | No login, sessions, JWT tokens, or password checks exist |
| **NFC Checkpoint Scanning**| 🔴 NOT IMPLEMENTED| 🔴 | 🟡 | 🟡 | UI button exists; hardware NFC adapter binding is absent |
| **Role-Based Access Control**| 🔴 NOT IMPLEMENTED| 🔴 | 🔴 | 🔴 | Dashboard actions have zero authorization restrictions |

---

## 18. CURRENT BUSINESS LOGIC

1. **Global Fleet vs Device Context (`dashboard.js:3079-3180`):**
   - When no device is selected (`currentDeviceId = null`), the dashboard displays fleet-wide aggregated metrics, renders all markers, fits map bounds to include all devices, and hides individual device controls.
   - Selecting a device filters the map strictly to that device, pans the camera, binds the real-time chart, and enables the Command Center.
2. **Device State Machine (`TrackingService.kt:1928-2039`):**
   - `MOVING`: Speed $> 0.3$ m/s or displacement $> 3.0$m. Transmits telemetry every 20s.
   - `IN_VEHICLE`: Speed $> 7.0$ m/s with smooth accelerometer variance ($< 2.0$) and 0 steps. Kalman max velocity is increased from 2.0 to 40.0.
   - `IDLE_SUSPECTED`: Detected 5 consecutive still readings.
   - `IDLE_CONFIRMED`: Detected persistent still readings. Transmits telemetry every 30s.
   - `STOPPED`: Stationary duration $> 300$ seconds (5 minutes). Transmits telemetry every 60s.
3. **Geofence Hysteresis & Cooldown (`backend/main.py:451-532`):**
   - Entry is triggered when distance $\le \text{radius}$.
   - Exit is triggered only when distance $\ge \text{radius} + 15.0$m (prevents GPS jitter oscillation).
   - Rate-limited to one event per geofence per device every 10 seconds.
4. **Liveness Evaluation Rules (`FaceLivenessDetector.kt:76-105`):**
   - Blink detection adds $+0.30$ score; head turn adds $+0.30$; face motion adds $+0.25$; frame quality adds $+0.15$.
   - A blink duration exceeding the maximum allowed limit triggers a hard failure (`BLINK_TOO_LONG`), flagging a `video_loop` spoof.
   - Failure to execute a head turn triggers a hard failure, flagging a `photo_static` spoof.
5. **Connection Freshness (`main.py:1754`):** A device is declared `offline` if its last telemetry record was created $> 300$ seconds (5 minutes) ago.

---

## 19. SECURITY & AUTHENTICATION

- **Authentication:** **Not currently implemented in the inspected repository.** There are no user login models, user registration tables, password hashing routines (though `passlib` is in `requirements.txt`), or JWT session tokens. All API endpoints and WebSocket channels are openly accessible.
- **Authorization & Access Control:** No role-based access control (RBAC) exists. Any network client can execute commands (e.g., triggering sirens or wiping caches) on any connected device by issuing a POST request to `/device/{device_id}/command`.
- **Transport Security:** Cleartext HTTP traffic is explicitly enabled in Android (`network_security_config.xml: cleartextTrafficPermitted="true"` and `AndroidManifest.xml: usesCleartextTraffic="true"`). In `TrackingService.kt:866`, the client executes `trustAllCertificates()`, installing a trust manager that accepts any X.509 certificate and bypassing TLS verification.
- **Secrets Management:** No hardcoded production passwords or credentials exist in the repository.

---

## 20. ERROR HANDLING & RESILIENCE

- **WebSocket Disconnection & Backoff:** The Android client employs an exponential backoff reconnect algorithm ($\min(1000 \times 1.5^{\text{attempt}}, 30000) + \text{jitter}$). The dashboard JS implementation employs a similar doubling backoff ($\min(1000 \times 2^{\text{attempt}}, 30000)$).
- **Mobile Network Failure:** Telemetry packets rejected by HTTP timeouts are queued to disk as individual JSON files by `TelemetryCache`. Continuous sessions are recorded as `.track` files by `OfflineGpsStorage` and synced in bulk via `/telemetry_batch` upon reconnection.
- **Uncaught Exceptions:** Android installs a global `Thread.setDefaultUncaughtExceptionHandler` that serializes crash messages and stack traces into JSON and dispatches them to POST `/crash`.
- **Database Resilience:** SQLAlchemy engine is initialized with `pool_pre_ping=True` to eliminate stale connections.

---

## 21. PERFORMANCE CONSIDERATIONS

- **Database Write Volume:** Under full deployment, telemetry points write to SQLite concurrently. In SQLite, concurrent writes lock the database file (`database is locked` error under high concurrency). Migrating to PostgreSQL is recommended when fleet size exceeds 50 devices.
- **Frontend Map Marker Updates:** The dashboard updates Leaflet markers by modifying DOM styles directly via `marker.setLatLng()` and CSS transforms rather than recreating layers, ensuring smooth rendering up to ~100 devices.
- **Telemetry Query Safety:** Route `GET /data` previously risked unbounded `.all()` queries; it now enforces a strict safety ceiling of `Query(200, ge=0, le=200000)` and provides integer `stride` downsampling.

---

## 22. KNOWN ISSUES / GAPS

| Issue | Location | Severity | Evidence | Impact |
| :--- | :--- | :---: | :--- | :--- |
| **Missing Endpoint: `/reportFailure`** | `backend/main.py` vs `TrialManager.kt:58` | **HIGH** | `TrialManager.kt` calls `POST /reportFailure`. In `main.py`, `TrialFailureDB` and `FailureReportRequest` are defined, but `@app.post("/reportFailure")` was never registered. | Mobile client receives HTTP 404 when reporting liveness verification failures. |
| **Duplicated Route: `POST /incident`** | `backend/main.py:939` & `1294` | **MEDIUM** | `@app.post("/incident")` is defined at line 939 (JSON body) and redefined at line 1294 (Form data). | The second route shadows the first; JSON incident submissions will fail with HTTP 422 validation errors. |
| **Duplicated Route: `GET /incidents`** | `backend/main.py:985` & `1351` | **LOW** | `@app.get("/incidents")` is declared at line 985 and line 1351 with slightly different query parameters. | Second registration shadows the first. |
| **Duplicated Lines in Geofence Update** | `backend/main.py:1283-1286` | **LOW** | Lines 1283 and 1285 execute `db.close()` twice, and lines 1284 and 1286 execute `broadcast_update` twice. | Redundant database close and duplicate WebSocket broadcast sent to dashboards. |
| **Empty Root Database File** | `watchmen_test.db` (root) | **LOW** | Root `watchmen_test.db` is 0 bytes; active database is `backend/watchmen_test.db`. | Running backend from root directory without `cd backend` creates an empty database. |
| **Empty `android/` Folder** | `android/` (root) | **LOW** | `android/` directory is completely empty; Android project is actually located in root `app/`. | Potential confusion for new developers. |
| **Disabled Client Geofence Check** | `TrackingService.kt:1603-1628`| **MEDIUM** | Geofence boundary check in `validateLocation()` is explicitly commented out. | Watchmen moving outside geofences are not alerted on device until re-enabled. |
| **NFC Checkpoint Placeholder** | `MainActivity.kt:700` | **LOW** | Checkpoint scanning button triggers a placeholder dialog without reading NFC hardware. | NFC patrol verification cannot be completed physically. |

---

## 23. TECHNICAL DEBT

### Code Debt
- Monolithic files: `backend/main.py` (2,227 lines), `dashboard.js` (4,249 lines), `TrackingService.kt` (2,164 lines), and `MainActivity.kt` (1,342 lines). All business logic, routes, database models, and socket handlers reside in single files rather than modular packages.
- Shadowed routes in `backend/main.py` (`POST /incident` and `GET /incidents`).

### Architecture Debt
- Lack of an authentication and authorization layer.
- Reliance on local SQLite for concurrent time-series telemetry data writes.
- Absence of database migration tooling (Alembic).

### Data / Database Debt
- The `telemetry` table is append-only with no automatic partitioning or time-series retention policy. Over months of 24/7 patrol tracking, telemetry tables will grow into millions of rows, degrading query performance without indexes on `(device_id, timestamp)`.

### UI / UX Debt
- Large CSS file (`watchmen-elite.css`, 2,443 lines) containing some legacy classes alongside the modern design tokens.

---

## 24. ARCHITECTURAL OBSERVATIONS

- **What is Working Well:**
  - The real-time telemetry and bi-directional command loop over WebSockets is responsive and well-orchestrated with concrete acknowledgment receipts.
  - The anti-spoofing and movement authenticity heuristics (`GpsSpoofingDetector`, `MovementAuthenticityDetector`) are sophisticated, leveraging raw accelerometer and gyroscope physics.
  - The zero-configuration local service discovery via mDNS allows client devices to locate dynamically assigned server IPs without hardcoding addresses.
- **What is Tightly Coupled:**
  - `backend/main.py` directly binds HTTP routes, Pydantic schemas, SQLAlchemy models, geofence Haversine calculations, and WebSocket connection state in one file.
- **Where Scaling May Become Difficult:**
  - When scaling past 50+ concurrent devices, SQLite's single-writer database lock will bottleneck concurrent telemetry ingestion. Migration to PostgreSQL (for which drivers already exist in `requirements.txt`) will be necessary.

---

## 25. IMPORTANT FILES

| File | Layer | Purpose | Importance |
| :--- | :--- | :--- | :--- |
| `backend/main.py` | Backend | Primary application entry point, API routes, database models, WebSocket manager | **CRITICAL** |
| `backend/static/dashboard.js` | Frontend | Single-page dashboard controller, WebSocket client, map rendering, command dispatch | **CRITICAL** |
| `backend/static/dashboard.html` | Frontend | Dashboard UI markup, 14 view workspaces, modals, and navigation shell | **CRITICAL** |
| `backend/static/css/watchmen-elite.css` | Frontend | Design system tokens, styles, layout grids, dark theme palettes | **HIGH** |
| `app/src/main/java/.../TrackingService.kt` | Mobile | Core foreground location tracking service, sensor listener, WebSocket client | **CRITICAL** |
| `app/src/main/java/.../MainActivity.kt` | Mobile | Guard interface, permission requests, panic trigger, SMS emergency dispatch | **HIGH** |
| `app/src/main/java/.../BackendEndpointManager.kt`| Mobile | Dynamic server URL resolution singleton and scheme converter | **HIGH** |
| `app/src/main/java/.../CameraCaptureActivity.kt` | Mobile | CameraX selfie capture with ML Kit facial liveness verification | **HIGH** |
| `app/src/main/java/.../MovementAuthenticityDetector.kt` | Mobile | Physics-based sensor fraud and mechanical cradle shaking detector | **HIGH** |
| `app/src/main/java/.../GpsSpoofingDetector.kt` | Mobile | Anti-mock location and impossible velocity anomaly detector | **MEDIUM** |
| `app/src/main/java/.../SirenController.kt` | Mobile | Full-volume emergency alarm and repeating vibration actuator | **MEDIUM** |
| `backend/watchmen_test.db` | Database | Active SQLite relational database containing 7,005 telemetry records | **CRITICAL** |

---

## 26. END-TO-END SYSTEM WALKTHROUGH

1. **Provisioning:** The guard launches the app; `SplashActivity` opens `SetupActivity`. The guard selects Urdu, enters device nickname "Guard-North", project "SITE-101", and saves.
2. **Dynamic Endpoint Resolution:** `BackendDiscoveryManager` listens for `_watchmen._tcp` via mDNS. Upon discovering `http://192.168.1.150:8000`, `BackendEndpointManager` sets the active URL and notifies listeners.
3. **Tracking Service Initialization:** `TrackingService` starts as a foreground service with a persistent notification. It acquires a partial wake lock, registers accelerometer/step sensors, and starts continuous GPS polling via `FusedLocationProviderClient`.
4. **WebSocket Handshake:** `TrackingService` opens a WebSocket connection to `ws://192.168.1.150:8000/ws?type=device&deviceid=SITE-101_Guard-North_a1b2c3d4` and sends `device_handshake`.
5. **Backend Registration:** The backend registers the socket in `active_connections` and broadcasts `device_connected` to all dashboards.
6. **Telemetry Transmission:**
   - The guard walks on patrol.
   - `MovementAuthenticityDetector` verifies accelerometer variance is consistent with human gait.
   - `GpsSpoofingDetector` confirms mock location flags are false.
   - `KalmanPedestrian` smooths coordinates.
   - `TrackingService` posts telemetry JSON to `/telemetry`.
7. **Database Storage & Geofence Evaluation:** The backend checks for duplicates, inserts a new row into `telemetry`, and checks distance against geofence perimeters.
8. **Dashboard Visualization:** The backend broadcasts the point over WebSocket to the dashboard. The dashboard moves the guard's marker, extends the polyline breadcrumb trail, and updates battery/speed stats.
9. **Emergency Panic Dispatch:** The guard encounters an intruder and presses the panic button in `MainActivity`. The app transmits a `PANIC` alert to POST `/alert`, plays a local TTS warning, and opens the SMS app with pre-filled GPS coordinates to the supervisor.
10. **Control Room Intervention:** The dashboard sounds an audible chime and pulses the guard's marker in bright red. The operator clicks "START SIREN" in the Command Center. The backend dispatches `{"type":"command","command":"START_SIREN"}` over the WebSocket.
11. **Mobile Siren Execution:** `TrackingService` receives the command, executes `sirenController.startSiren()` (sounding max alarm volume and vibrating), and returns a `command_ack` to the dashboard.

---

## 27. CURRENT STATE SUMMARY

- **What Works Today:** End-to-end GPS telemetry pipeline, Kalman filtering, state machine classification, WebSocket communication, remote siren control, live map visualization, photo uploads with ML Kit liveness verification, mDNS local server discovery, crash logging, and offline track session recovery.
- **What Partially Works:** Background SMS sending (subject to Android OS restrictions; requires user tap to send via SMS app intent), client-side geofence checks (temporarily bypassed in code), and supervisor liveness failure reporting (missing backend endpoint).
- **What is Missing:** User authentication, password encryption, role-based access control, hardware NFC checkpoint scanning, and automated database retention cleanup.
- **Current Architecture Maturity:** Prototype / Early Production Stage. Core tracking, anti-fraud algorithms, and operations UI are well-engineered, but enterprise security (auth/RBAC) and database modularity must be implemented before public multi-tenant deployment.

---

## 28. FUTURE DEVELOPMENT CONTEXT

*(Areas for future consideration — NOT current requirements)*

1. **Authentication & Multi-Tenancy:** Implementation of JWT/OAuth2 authentication on FastAPI routes and WebSockets, isolating data by organization/tenant.
2. **Database Scalability:** Transitioning from SQLite to PostgreSQL / TimescaleDB for high-throughput append-only telemetry streaming.
3. **Code Modularity:** Refactoring `backend/main.py` into a FastAPI `APIRouter` package structure (`routers/telemetry.py`, `routers/alerts.py`, `routers/commands.py`, `core/ws.py`).
4. **NFC Checkpoint Integration:** Binding Android `NfcAdapter` in `MainActivity` to enable physical tag scanning along patrol routes.
5. **Route Cleanup:** Removing shadowed duplicate routes in `main.py` and implementing the missing `POST /reportFailure` handler.

---

## 29. FINAL PROJECT ARCHITECTURE SUMMARY

```text
                        WATCHMEN TRACKER
                               │
               ┌───────────────┴───────────────┐
               ▼                               ▼
       MOBILE CLIENT                   OPERATIONS CONSOLE
      (Android Kotlin)                  (Leaflet SPA JS)
       • TrackingService                 • Live Fleet Tracking
       • Anti-Fraud Engine               • Command Center
       • Biometric Check-In              • Geofence Manager
               │                               ▲
               │ Telemetry & Panic             │ Real-Time Broadcasts
               │ (HTTP / WebSocket)            │ (WebSocket / REST)
               ▼                               │
        FASTAPI BACKEND ───────────────────────┘
        (Async Python 3)
         • REST Routing & Duplicate Engine
         • Two-Way WebSocket Router
         • mDNS Zeroconf Discovery
               │
               ▼
        RELATIONAL DATABASE
        (backend/watchmen_test.db)
         • Telemetry (7,005 rows)
         • Security Alerts (1,541 rows)
         • Alerts, Geofences, Incidents
```

---

## 30. ANALYSIS METHODOLOGY & CONFIDENCE

### Methodology
- **Scanned:** Complete repository filesystem across `app/`, `backend/`, and build configurations.
- **Inspected Files:**
  - Android manifests, Kotlin source files (`TrackingService.kt`, `MainActivity.kt`, `BackendEndpointManager.kt`, etc.), Gradle build scripts.
  - Backend source code (`main.py`, `test_discovery.py`, `test_ws_e2e.py`, `requirements.txt`).
  - Frontend markup, scripts, and stylesheets (`dashboard.html`, `dashboard.js`, `watchmen-elite.css`).
  - Active SQLite database (`backend/watchmen_test.db`) directly inspected using Python `sqlite3` PRAGMA queries.
- **Tests Evaluated:** Unit tests in `BackendEndpointManagerTest.kt`, integration tests in `test_discovery.py`, and E2E WebSocket tests in `test_ws_e2e.py`.

### Findings Confidence Classification
- **HIGH (Directly confirmed from code implementation):**
  - All tech stack choices, library versions, and Gradle/Python dependencies.
  - Mobile sensor tracking, Kalman filter parameters, and state machine transitions.
  - Complete list of REST API routes and WebSocket message types.
  - Database schema, table structures, column definitions, and active row counts.
  - Remote command handling (`START_SIREN`, `STOP_SIREN`, `GET_LOCATION`, `WIPE_CACHE`, `RESTART_SERVICE`).
  - Known gaps (shadowed routes, missing `/reportFailure` endpoint, bypassed geofence check).
- **MEDIUM (Strongly inferred from implementation):**
  - Operational problem statement and use-case justifications (inferred from domain models, multilingual speech strings, and anti-spoofing algorithms).
- **LOW (Could not be conclusively determined):**
  - None. All architectural claims in this document are backed by verifiable code citations.
