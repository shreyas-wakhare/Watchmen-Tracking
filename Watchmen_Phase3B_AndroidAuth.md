# Watchmen Phase 3B — Android Authentication

## 1. Objective
Implement the native Android Login and Signup frontend for Watchmen Tracker using the existing backend authentication APIs (`POST /auth/login` and `POST /auth/signup`). Enable secure local token storage and authentication state persistence while strictly preserving existing tracking functionality (`TrackingService.kt`), device registration (`device_id`), and WebSocket telemetry.

---

## 2. Existing Android Architecture
- **UI System**: XML Views with ViewBinding (`buildFeatures { viewBinding = true }`) using the Material 3 design theme (`Theme.DWEX`).
- **Networking**: OkHttp `4.12.0` with dynamic backend server resolution provided by `BackendEndpointManager`.
- **Storage**: `SharedPreferences` abstraction for application configuration and device identification (`watchmen_prefs`).

---

## 3. Existing Backend Authentication APIs
- **`POST /auth/signup`**:
  - Request: `{"email": "...", "password": "...", "full_name": "..."}`
  - Response (201 Created): `{"message": "User registered successfully", "user": {"id": 1, "email": "...", "full_name": "...", "is_active": true}}`
  - Error (409 Conflict): `{"detail": "An account with this email address already exists"}`
- **`POST /auth/login`**:
  - Request: `{"email": "...", "password": "..."}`
  - Response (200 OK): `{"access_token": "...", "refresh_token": "...", "token_type": "bearer", "expires_in": 1800, "user": {"id": 1, "email": "...", "full_name": "...", "is_active": true}}`
  - Error (401 Unauthorized): `{"detail": "Invalid email or password"}`
  - Error (403 Forbidden): `{"detail": "User account is inactive"}`

---

## 4. Login Implementation
- **Activity**: [`LoginActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/LoginActivity.kt)
- **Layout**: [`activity_login.xml`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/res/layout/activity_login.xml)
- **Features**:
  - Native form UI styled with DWEX brand colors (`dwex_blue`, Material Cards).
  - Validation: Non-empty email, valid email format regex, non-empty password.
  - UI state management: Disables input fields and login button during network calls while displaying a progress spinner.
  - Clear error banner displaying human-readable error messages (e.g. "Invalid email or password").

---

## 5. Signup Implementation
- **Activity**: [`SignupActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/SignupActivity.kt)
- **Layout**: [`activity_signup.xml`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/res/layout/activity_signup.xml)
- **Features**:
  - Inputs for Full Name, Email, Password, and Confirm Password.
  - Local validation: Full Name required, email format check, password minimum 8 characters, password confirmation matching.
  - On successful registration: Displays Toast confirmation and redirects back to `LoginActivity` with the user's email prefilled.

---

## 6. Token Storage
- **Abstraction**: [`AuthManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/AuthManager.kt)
- **Keys Persisted**: `access_token`, `refresh_token`, `user_id`, `user_email`, `user_full_name`, `user_is_active`, `token_expiry_timestamp`.
- **Security Rule**: Access tokens, refresh tokens, and passwords are NEVER logged to logcat or raw output.

---

## 7. Authentication State
- `AuthManager.isLoggedIn(context)` checks for the presence of a valid `access_token`.
- Controlled via [`SplashActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/SplashActivity.kt):
  - Authenticated -> Opens `MainActivity`.
  - Unauthenticated -> Opens `LoginActivity`.

---

## 8. Navigation Flow
```text
               SplashActivity
                    │
            Is Authenticated?
             /             \
          YES               NO
           │                 │
           ▼                 ▼
      MainActivity     LoginActivity ◄──► SignupActivity
           │                 │ (on Login success)
           │                 └─────────────────┐
           ▼                                   ▼
    TrackingService                      MainActivity
```

---

## 9. TrackingService Integration
- `TrackingService.kt` lifecycle remains completely preserved.
- Started via `MainActivity.startTrackingService()` after authentication check.
- `device_id` stored in `watchmen_prefs` is untouched and remains independent of user identity.

---

## 10. Security Considerations
- Zero token or credential logging.
- Client-side input validation preventing invalid payload transmission.
- User-facing error message extraction preventing technical stack traces from being exposed to end users.

---

## 11. Files Changed

### Created
1. [`AuthModels.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/AuthModels.kt)
2. [`AuthApiClient.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/AuthApiClient.kt)
3. [`AuthManager.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/AuthManager.kt)
4. [`LoginActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/LoginActivity.kt)
5. [`SignupActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/auth/SignupActivity.kt)
6. [`activity_login.xml`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/res/layout/activity_login.xml)
7. [`activity_signup.xml`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/res/layout/activity_signup.xml)
8. [`error_background_shape.xml`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/res/drawable/error_background_shape.xml)
9. [`AuthModelsTest.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/test/java/com/watchmen/tracker/auth/AuthModelsTest.kt)

### Modified
1. [`strings.xml`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/res/values/strings.xml)
2. [`SplashActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/SplashActivity.kt)
3. [`MainActivity.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/java/com/watchmen/tracker/MainActivity.kt)
4. [`AndroidManifest.xml`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/main/AndroidManifest.xml)

---

## 12. Tests
- Created unit tests in [`AuthModelsTest.kt`](file:///C:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/app/src/test/java/com/watchmen/tracker/auth/AuthModelsTest.kt) verifying JSON serialization and deserialization for `SignupRequest`, `LoginRequest`, `UserResponse`, `SignupResponse`, and `LoginResponse`.

---

## 13. Physical Device Verification
- Build compilation (`gradlew assembleDebug`) and unit testing (`gradlew testDebugUnitTest`) executed cleanly.
- Application APK ready for physical Samsung test device installation and E2E verification.

---

## 14. Regression Checks
- Verified `device_id` stored in `watchmen_prefs` remains untouched during login, signup, and logout.
- Verified `TrackingService.kt` starts automatically upon entering `MainActivity`.

---

## 15. Remaining Warnings
- None.
