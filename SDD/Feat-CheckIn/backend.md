# Watchmen Tracker — Check-In / Check-Out
# Backend Architecture Specification (`backend.md`)

---

## 1. Executive Summary & Objective

This document specifies the backend architectural layer implemented for the **Check-In / Check-Out**, **Scheduled Shift Management**, and **Attendance Lifecycle Tracking** feature in the Watchmen Tracker system (Phase 3).

The implementation satisfies all **5 Locked Business Rules** defined in [`database.md`](file:///c:/Users/Shreyas-Wakhare/Desktop/WatchmenTracker/SDD/Feat-CheckIn/database.md):
1. **Simple Current Schedule**: Exactly one active recurring schedule per watchman. Shift updates deactivate/replace the current schedule without mutating historical records.
2. **One Continuous Shift Per Working Day**: Strict constraint of one continuous duty shift per `work_date`. Split shifts are rejected at both application and PostgreSQL levels (`uq_attendance_user_work_date`).
3. **Server-Authoritative Time & Idempotent Retries**: Client clocks are never authoritative. Official clock times are strictly server-generated (`utc_now()`). Retries with identical `request_id` (UUID) return the created record idempotently without creating duplicates.
4. **Forgotten Clock Out**: Server never fabricates or auto-guesses clock-out timestamps; `clock_out_at` remains `NULL`. Open shifts block new shifts across any calendar date (`uq_user_single_active_shift`).
5. **Site Timezone as Authority**: All calculations use the schedule's site timezone (`Asia/Dubai`). `work_date` represents the local calendar date on which the shift started, cleanly anchoring overnight shifts to their start date.

Android and Web Dashboard integrations are strictly decoupled from this phase and will consume these API contracts in subsequent phases.

---

## 2. Directory & Module Organization

The attendance backend is cleanly modularized under `backend/attendance/` and integrated into the core FastAPI application:

```text
backend/
├── auth/
│   ├── dependencies.py          # JWT authentication dependency (get_current_user)
│   ├── router.py                # Signup and Login endpoints (/auth/signup, /auth/login)
│   ├── schemas.py               # Auth request/response schemas
│   └── security.py              # Argon2 hashing & JWT encoding/decoding
├── attendance/
│   ├── __init__.py              # Package export (attendance_router)
│   ├── router.py                # FastAPI endpoints (/attendance/*) & event broadcasting
│   ├── schemas.py               # Pydantic request & response validation schemas
│   └── service.py               # Core attendance business logic, transactions, idempotency
├── db/
│   ├── base.py                  # PostgreSQL engine & session factory
│   ├── models.py                # SQLAlchemy ORM models (UserShiftSchedule, AttendanceRecord)
│   └── __init__.py              # Model exports
├── main.py                      # FastAPI root application, WebSocket ConnectionManager
└── tests/
    ├── test_attendance.py       # Full automated test suite (41/41 passing)
    ├── test_auth_phase2d.py     # Auth regression test suite (34/34 passing)
    └── test_config.py           # Configuration regression test suite (10/10 passing)
```

---

## 3. Authentication & Security Layer

### 3.1 Authenticated User Dependency (`get_current_user`)
- Located in `backend/auth/dependencies.py`.
- Extracts Bearer token from the standard `Authorization: Bearer <token>` HTTP header via `HTTPBearer(auto_error=False)`.
- Validates token signature using `get_jwt_secret_key()` and `get_jwt_algorithm()` (HS256).
- Verifies token claims:
  - `type == "access"` (Rejects refresh tokens or invalid token types).
  - Expiration timestamp (`exp`).
  - Subject claim (`sub`) parsed to integer `user_id`.
- Queries `User` from PostgreSQL by `id`.
- Security checks:
  - Missing or malformed token $\rightarrow$ HTTP `401 Unauthorized` (`WWW-Authenticate: Bearer`).
  - Expired token $\rightarrow$ HTTP `401 Unauthorized` (`detail="Token has expired"`).
  - Inactive user (`user.is_active is False`) $\rightarrow$ HTTP `403 Forbidden` (`detail="User account is inactive"`).
- **User Scoping**: The client request body never accepts `user_id`. All attendance queries, mutations, and history records are strictly scoped to `current_user.id`. Cross-user inspection or mutation is physically impossible.

---

## 4. API Specification

All endpoints are mounted under the prefix `/attendance`.

### 4.1 Schedule Management

#### 4.1.1 Get Active Schedule
- **Method & Path**: `GET /attendance/schedule`
- **Authentication**: Required (`Bearer <access_token>`)
- **Responses**:
  - `200 OK`:
    ```json
    {
      "id": 1,
      "user_id": 10,
      "shift_name": "Day Shift",
      "start_time": "07:00:00",
      "end_time": "19:00:00",
      "timezone": "Asia/Dubai",
      "days_of_week": "1,2,3,4,5,6,7",
      "is_active": true,
      "created_at": "2026-09-10T07:00:00Z",
      "updated_at": "2026-09-10T07:00:00Z"
    }
    ```
  - `404 Not Found`: If no active schedule has been created yet.

#### 4.1.2 Create or Replace Active Schedule
- **Method & Path**: `PUT /attendance/schedule`
- **Authentication**: Required (`Bearer <access_token>`)
- **Request Body**:
  ```json
  {
    "shift_name": "Day Shift",
    "start_time": "07:00:00",
    "end_time": "19:00:00",
    "timezone": "Asia/Dubai",
    "days_of_week": "1,2,3,4,5,6,7"
  }
  ```
- **Validation Rules**:
  - `shift_name`: Non-blank string, max length 50.
  - `start_time` & `end_time`: Valid wall-clock times. Cannot be identical (0-minute shifts rejected with 422). Overnight shifts (e.g. `19:00` to `07:00`) are fully supported.
  - `timezone`: Valid IANA timezone name verified via Python `zoneinfo.ZoneInfo` (e.g. `Asia/Dubai`). Invalid timezones rejected with 422.
  - `days_of_week`: Comma-separated ISO day digits between 1 and 7 (1=Mon, 7=Sun). No duplicates allowed.
- **Behavior**:
  - Deactivates any existing active schedule for the user (`is_active = FALSE`).
  - Inserts new active schedule (`is_active = TRUE`).
  - Protected by PostgreSQL partial unique index `uq_user_active_schedule`.
- **Response**: `200 OK` (returns updated `ScheduleResponse`).

---

### 4.2 Duty Lifecycle (Clock In & Clock Out)

#### 4.2.1 Clock In
- **Method & Path**: `POST /attendance/clock-in`
- **Authentication**: Required
- **Request Body**:
  ```json
  {
    "request_id": "c7a8b9d0-1234-5678-9abc-def012345678",
    "device_id": "SiteA_GuardPhone_a1b2c3d4",
    "latitude": 25.2048,
    "longitude": 55.2708
  }
  ```
  *(Note: `device_id`, `latitude`, and `longitude` are optional. However, if GPS coordinates are provided, both `latitude` and `longitude` must be provided together; partial coordinates are rejected with `422 Unprocessable Content`).*
- **Authoritative Server Logic**:
  1. Authenticates user.
  2. Resolves active schedule. (Rejects with `400 Bad Request` if no schedule configured).
  3. Evaluates site timezone (`schedule.timezone`). Computes local current date (`site_date`).
  4. Checks scheduled working days (`schedule.days_of_week`). (Rejects with `400 Bad Request` if today is an off day).
  5. Checks for unclosed previous shift (`WHERE user_id = :uid AND clock_out_at IS NULL`). (Rejects with `409 Conflict` under Locked Rule #4).
  6. Checks for existing shift on same date (`WHERE user_id = :uid AND work_date = :site_date`). (Rejects with `409 Conflict` under Locked Rule #2).
  7. Checks idempotency (`clock_in_request_id = :request_id`). If identical request is received, returns the existing record immediately (`200 OK`).
  8. Captures authoritative server timestamp (`clock_in_at = utc_now()`). Ignores any client-supplied timestamps.
  9. Snapshots `scheduled_start_time`, `scheduled_end_time`, and `timezone` into the attendance row.
  10. Broadcasts WebSocket event to connected dashboards: `{"type": "ATTENDANCE_EVENT", "event": "CLOCK_IN", ...}`.
- **Response**: `200 OK`
  ```json
  {
    "id": 5001,
    "user_id": 10,
    "work_date": "2026-09-10",
    "scheduled_start_time": "07:00:00",
    "scheduled_end_time": "19:00:00",
    "timezone": "Asia/Dubai",
    "clock_in_at": "2026-09-10T02:54:00Z",
    "clock_out_at": null,
    "clock_in_request_id": "c7a8b9d0-1234-5678-9abc-def012345678",
    "clock_out_request_id": null,
    "clock_in_device_id": "SiteA_GuardPhone_a1b2c3d4",
    "clock_out_device_id": null,
    "clock_in_lat": 25.2048,
    "clock_in_lon": 55.2708,
    "clock_out_lat": null,
    "clock_out_lon": null,
    "total_worked_minutes": null,
    "status": "CLOCKED_IN",
    "notes": null,
    "created_at": "2026-09-10T02:54:00Z",
    "updated_at": "2026-09-10T02:54:00Z"
  }
  ```

#### 4.2.2 Clock Out
- **Method & Path**: `POST /attendance/clock-out`
- **Authentication**: Required
- **Request Body**:
  ```json
  {
    "request_id": "d8b9c0e1-4321-8765-fedc-ba9876543210",
    "device_id": "SiteA_GuardPhone_a1b2c3d4",
    "latitude": 25.2050,
    "longitude": 55.2710
  }
  ```
  *(Note: `device_id`, `latitude`, and `longitude` are optional. If GPS coordinates are provided, both `latitude` and `longitude` must be provided together; partial coordinates are rejected with `422 Unprocessable Content`).*
- **Authoritative Server Logic**:
  1. Authenticates user.
  2. Checks idempotency (`clock_out_request_id = :request_id`). If identical request is received, returns the completed record immediately (`200 OK`).
  3. Finds user's currently open shift (`WHERE user_id = :uid AND clock_out_at IS NULL`). If none exists, rejects with `409 Conflict` (`"No active attendance session found to clock out from."`).
  4. Generates authoritative server timestamp (`clock_out_at = utc_now()`).
  5. Calculates `total_worked_minutes` strictly from actual timestamps:
     $$\text{total\_worked\_minutes} = \max\left(0, \left\lfloor \frac{\text{clock\_out\_at} - \text{clock\_in\_at}}{60} \right\rfloor \right)$$
     (Never calculated from scheduled times).
  6. Enforces constraint `clock_out_at >= clock_in_at`.
  7. Updates record with device/GPS metadata and duration.
  8. Broadcasts WebSocket event to connected dashboards: `{"type": "ATTENDANCE_EVENT", "event": "CLOCK_OUT", ...}`.
- **Response**: `200 OK`
  ```json
  {
    "id": 5001,
    "user_id": 10,
    "work_date": "2026-09-10",
    "scheduled_start_time": "07:00:00",
    "scheduled_end_time": "19:00:00",
    "timezone": "Asia/Dubai",
    "clock_in_at": "2026-09-10T02:54:00Z",
    "clock_out_at": "2026-09-10T15:02:00Z",
    "clock_in_request_id": "c7a8b9d0-1234-5678-9abc-def012345678",
    "clock_out_request_id": "d8b9c0e1-4321-8765-fedc-ba9876543210",
    "clock_in_device_id": "SiteA_GuardPhone_a1b2c3d4",
    "clock_out_device_id": "SiteA_GuardPhone_a1b2c3d4",
    "clock_in_lat": 25.2048,
    "clock_in_lon": 55.2708,
    "clock_out_lat": 25.2050,
    "clock_out_lon": 55.2710,
    "total_worked_minutes": 728,
    "status": "CLOCKED_OUT",
    "notes": null,
    "created_at": "2026-09-10T02:54:00Z",
    "updated_at": "2026-09-10T15:02:00Z"
  }
  ```

---

### 4.3 Attendance State Inspection

#### 4.3.1 Get Today's State
- **Method & Path**: `GET /attendance/today`
- **Authentication**: Required
- **Purpose**: Single call for clients upon opening the app or refreshing the dashboard.
- **Derived States**:
  - `NOT_STARTED`: No shift record exists for today's `work_date`.
  - `CLOCKED_IN`: Guard has an active open shift started today (`work_date == site_date` and `clock_out_at IS NULL`). `running_minutes` is dynamically provided.
  - `CLOCKED_OUT`: Guard completed today's duty shift (`work_date == site_date` and `clock_out_at IS NOT NULL`).
  - `PENDING_RESOLUTION`: Guard has an unclosed shift from a past calendar date (`work_date < site_date` and `clock_out_at IS NULL`). Prompts user/supervisor to resolve forgotten clock-out.
- **Response**: `200 OK`
  ```json
  {
    "state": "CLOCKED_IN",
    "work_date": "2026-09-10",
    "schedule": { ... },
    "attendance": { ... },
    "running_minutes": 142,
    "server_time": "2026-09-10T05:16:00Z"
  }
  ```

#### 4.3.2 Get Active Shift
- **Method & Path**: `GET /attendance/active`
- **Authentication**: Required
- **Purpose**: App crash recovery, phone reboot recovery, timer reconstruction.
- **Response**: `200 OK`
  ```json
  {
    "has_active": true,
    "attendance": { ... },
    "running_minutes": 142,
    "server_time": "2026-09-10T05:16:00Z"
  }
  ```
  *(If no shift is open: `{"has_active": false, "attendance": null, "running_minutes": null, "server_time": "..."}`)*.

#### 4.3.3 Get Attendance History
- **Method & Path**: `GET /attendance/history`
- **Authentication**: Required
- **Query Parameters**:
  - `limit`: Integer (1 to 100, default 20)
  - `offset`: Integer (default 0)
  - `start_date`: Optional ISO date (`YYYY-MM-DD`)
  - `end_date`: Optional ISO date (`YYYY-MM-DD`). If both `start_date` and `end_date` are provided, `start_date <= end_date` is strictly enforced (reversed date ranges return `422 Unprocessable Content`).
- **Ordering**: Strict descending chronological order (`work_date DESC, clock_in_at DESC`).
- **Response**: `200 OK`
  ```json
  {
    "total": 42,
    "items": [ ... ],
    "limit": 20,
    "offset": 0
  }
  ```

---

## 5. Concurrency, Transactions & Idempotency

### 5.1 Multi-Threaded Concurrency Guarantees
PostgreSQL constraints are the authoritative final guardrail:
- `uq_attendance_user_work_date`: Prevents double clock-in on the same date.
- `uq_user_single_active_shift`: Prevents multiple concurrent open shifts.
- `uq_attendance_clock_in_request_id`: Prevents duplicate clock-in records.
- `uq_attendance_clock_out_request_id`: Prevents duplicate clock-out records.

### 5.2 Exception Handling & Race Recovery
In `backend/attendance/service.py`:
- All mutations run within standard SQLAlchemy session transactions.
- On `IntegrityError` (database race condition):
  1. The transaction is immediately rolled back (`db.rollback()`).
  2. The service queries PostgreSQL for the client's `request_id`:
     - If the identical `request_id` was committed by a competing simultaneous worker thread for the *same authenticated user*, it is returned as an idempotent success (`200 OK`).
     - If the matching `request_id` belongs to a *different user*, it raises `409 Conflict` (`"Request ID has already been used by another operation."`).
     - If a competing thread created an open shift or completed shift with a *different* `request_id`, it translates the conflict cleanly into HTTP `409 Conflict`.
  3. No raw SQL errors, stack traces, or uncontrolled HTTP 500 exceptions are exposed.

---

## 6. Real-Time WebSocket Event Architecture

Attendance events hook directly into `main.py`'s existing `ConnectionManager` (`manager.broadcast`):
- **Clock In Event**:
  ```json
  {
    "type": "ATTENDANCE_EVENT",
    "event": "CLOCK_IN",
    "user_id": 10,
    "user_name": "Michael",
    "work_date": "2026-09-10",
    "clock_in_at": "2026-09-10T02:54:00Z",
    "scheduled_start": "07:00:00",
    "scheduled_end": "19:00:00",
    "timezone": "Asia/Dubai",
    "device_id": "SiteA_GuardPhone_a1b2c3d4",
    "attendance_id": 5001
  }
  ```
- **Clock Out Event**:
  ```json
  {
    "type": "ATTENDANCE_EVENT",
    "event": "CLOCK_OUT",
    "user_id": 10,
    "user_name": "Michael",
    "work_date": "2026-09-10",
    "clock_out_at": "2026-09-10T15:02:00Z",
    "total_worked_minutes": 728,
    "device_id": "SiteA_GuardPhone_a1b2c3d4",
    "attendance_id": 5001
  }
  ```
- **Semantic Separation**: Attendance events are marked with `"type": "ATTENDANCE_EVENT"`, cleanly separated from security alarms (`alerts`), hardware tampering (`security_alerts`), or field incidents (`incidents`).

---

## 7. Automated Test Suite Results

All 41 requirements specified in Phase 3 were verified using `backend/tests/test_attendance.py` against live PostgreSQL and uvicorn:

| Test Group | ID Range | Tests | Pass Rate |
| :--- | :--- | :---: | :---: |
| **Authentication** | `ATT-AUTH-001` to `004` | 4 | 100% |
| **Schedule Management** | `ATT-SCHED-005` to `010` | 6 | 100% |
| **Clock In** | `ATT-CLKIN-011` to `020` | 10 | 100% |
| **Active & Forgotten Clock Out** | `ATT-ACT-028` to `030` | 3 | 100% |
| **Clock Out** | `ATT-CLKOUT-021` to `027` | 7 | 100% |
| **Timezone & Overnight** | `ATT-TZ-031` to `033` | 3 | 100% |
| **History & Pagination** | `ATT-HIST-034` to `036` | 3 | 100% |
| **Concurrency & Idempotency** | `ATT-CONC-037` to `039` | 3 | 100% |
| **WebSocket Events & Regression** | `ATT-EVT-040` to `041` | 2 | 100% |
| **Total** | | **41** | **100% (41/41)** |

Existing regression suites also passed 100%:
- `test_auth_phase2d.py`: 34/34 passed.
- `test_config.py`: 10/10 passed.
- `alembic check`: 0 schema drift detected.

---

## 8. Consumer Guidelines for Upcoming Phases

### 8.1 Android Integration (Phase 4 / Phase 5)
1. **Never generate timestamps**: Always let the server generate clock times.
2. **Generate client UUID**: When user taps Clock In / Clock Out, generate a UUID (`UUID.randomUUID().toString()`) and store it before transmission. On network error/timeout, retry with the *same* UUID.
3. **App Launch Flow**: Call `GET /attendance/today`.
   - If `state == "CLOCKED_IN"`: Initialize local UI counter with `now - clock_in_at` and show [CLOCK OUT] button.
   - If `state == "NOT_STARTED"`: Show [CLOCK IN] button with scheduled shift start time.
   - If `state == "CLOCKED_OUT"`: Show shift completed summary card.
   - If `state == "PENDING_RESOLUTION"`: Display alert banner indicating previous shift is unclosed.

### 8.2 Web Dashboard Integration
1. **Live Feed**: Listen on WebSocket `/ws?type=dashboard`. When receiving message with `"type": "ATTENDANCE_EVENT"`, update the Live Guard Duty Roster table instantly.
2. **Aggregations**: Fetch `GET /attendance/history` for guard duty logs and audit reports.
