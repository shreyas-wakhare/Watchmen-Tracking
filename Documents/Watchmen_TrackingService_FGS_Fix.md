# Watchmen Tracker — TrackingService Android 14 Location FGS Crash Fix

## 1. Executive Summary & Root Cause Analysis

### Symptom
During physical testing on Android 14 (targetSdk 34), the app crashed with:
```text
FATAL EXCEPTION: main
java.lang.RuntimeException: Unable to create service com.watchmen.tracker.TrackingService:
java.lang.SecurityException: Starting FGS with type location callerApp=... targetSDK=34 requires permissions:
[android.permission.FOREGROUND_SERVICE_LOCATION] and one of: [android.permission.ACCESS_COARSE_LOCATION, android.permission.ACCESS_FINE_LOCATION]
and the app must be in the eligible state/exemptions to access the foreground-only permission.

Crash location: TrackingService.onCreate() (TrackingService.kt:822)
```

### Root Cause Analysis
1. **Premature Service Start**: `BootReceiver.kt` and `TrackingJobService.kt` attempted to launch `TrackingService` via `startForegroundService()` on device boot / background job execution without verifying whether the user was logged in or whether location runtime permissions (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`) were granted.
2. **Android 14 Location FGS Restrictions**: On Android 14 (`targetSdk 34`), starting a Foreground Service declared with `android:foregroundServiceType="location"` requires:
   - Location runtime permissions (`ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`) to be granted *before* starting the service.
   - Calling `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)` explicitly specifying the location service type on API level 29+.
   - When launched while unauthenticated or before location permission is granted, `startForeground()` throws `SecurityException`.

---

## 2. Correct Application Lifecycle

```text
                                App Launch / Boot
                                        │
                                BackendEndpointManager.init()
                                (mDNS Discovery Starts)
                                        │
                                        ▼
                                 SplashActivity
                                        │
                                Is Authenticated?
                                 /             \
                              NO                YES
                              │                  │
                              ▼                  ▼
                        LoginActivity       MainActivity
                              │                  │
                        Login Success            │
                              │                  ▼
                              └─────────► Check Location Permissions
                                                 │
                                           Are Granted?
                                            /        \
                                          NO          YES
                                          │            │
                                    Request Perms      ▼
                                          │     Start TrackingService
                                          └──────────► (Foreground Service with
                                                        TYPE_LOCATION)
```

---

## 3. Key Fixes Implemented

### 1. `TrackingService.kt`
- Updated `startForeground()` call in `onCreate()` to include `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION` on API 29+ (`Build.VERSION_CODES.Q`).
- Added safety checks in `onCreate()`: verifies `AuthManager.isLoggedIn(applicationContext)` and location permissions before promoting to foreground service. If missing, stops service gracefully (`stopSelf()`) instead of throwing `SecurityException`.

### 2. `BootReceiver.kt`
- Checks both `AuthManager.isLoggedIn(context)` AND `ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED` before attempting `startForegroundService()`.

### 3. `TrackingJobService.kt`
- Checks `AuthManager.isLoggedIn(applicationContext)` AND location permissions before attempting service restart.

### 4. Manifest & Endpoint Discovery Preservation
- Verified `AndroidManifest.xml` contains all required permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) and service declaration `android:foregroundServiceType="location"`.
- Preserved `BackendEndpointManager` mDNS discovery lifecycle starting at app launch.

---

## 4. Files Changed

1. [`TrackingService.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/TrackingService.kt): Added `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION` to `startForeground()` and pre-start permission/auth checks in `onCreate()`.
2. [`BootReceiver.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/BootReceiver.kt): Added authentication and location permission validation before starting service.
3. [`TrackingJobService.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/TrackingJobService.kt): Added authentication and location permission validation before job execution.
4. [`Watchmen_TrackingService_FGS_Fix.md`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/Watchmen_TrackingService_FGS_Fix.md): Documentation file.

---

## 5. Verification Strategy

- **Static Analysis**: Verify unauthenticated launch does not invoke `startTrackingService()`.
- **Gradle Build & Unit Tests**: Execute `./gradlew testDebugUnitTest assembleDebug`.
- **Physical Samsung Device Test**:
  1. Cold start unauthenticated app -> confirm no `SecurityException` crash and `TrackingService` is not started.
  2. Verify mDNS discovery operates on `LoginActivity`.
  3. Log in successfully -> `MainActivity` opens -> Location permission requested -> `TrackingService` starts cleanly with location foreground notification.
